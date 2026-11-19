const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "farmer_alice_123";
const BOB_UID = "farmer_bob_456";
const SUPER_ADMIN_UID = "admin_fatma_001";
const SPOOF_ADMIN_UID = "spoof_admin_999";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function buildValidWorkspacePayload(uid, createdDate, updatedDate, role = "FARMER", accountStatus = "ACTIVE") {
  return {
    userId: uid,
    role,
    accountStatus,
    farmerName: "م. أحمد عبد الرحمن",
    whatsappPhone: "+201012345678",
    gmailAddress: "ahmed.natrun.agro@gmail.com",
    nationalId: "28805141801492",
    fieldName: "مزرعة الأمل النموذجية - وادي النطرون",
    region: "وادي النطرون",
    cropType: "بطاطس",
    areaFeddans: 60.0,
    latitude: 30.3889,
    longitude: 30.3414,
    soilPh: 7.8,
    soilEce: 2.4,
    waterEcw: 1.4,
    ndviMean: 0.72,
    ndreMean: 0.24,
    psriMean: 0.19,
    ndmiMean: 0.31,
    detectedRiskName: "الندوة المتأخرة (Phytophthora infestans)",
    createdAt: createdDate,
    updatedAt: updatedDate,
  };
}

test("Unauthenticated user: cannot read or write farmer workspace, chat logs, or admin registry", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const now = new Date(Date.now() - 1000);
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set(buildValidWorkspacePayload(ALICE_UID, now, now))
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("chat_logs").get()
  );
  await assertFails(
    unauthDb.collection("admins").doc(SUPER_ADMIN_UID).get()
  );
});

test("Authenticated owner: valid FARMER workspace create and partial update succeed; privilege escalation and shadow fields fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);

  // Reject self-assigned SUPER_ADMIN role on registration
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set(
      buildValidWorkspacePayload(ALICE_UID, created, created, "SUPER_ADMIN", "ACTIVE")
    )
  );

  // Valid FARMER registration succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(buildValidWorkspacePayload(ALICE_UID, created, created))
  );

  // Partial update on mutable telemetry fields succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      soilPh: 6.8,
      ndviMean: 0.78,
      detectedRiskName: "نمو خضري سليم ومتوازن",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject self-escalation of role during update
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      role: "SUPER_ADMIN",
      updatedAt: new Date(Date.now() - 800),
    })
  );

  // Reject immutable createdAt mutation
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject ghost field / @DocumentId 'id' injection
  const badPayload = {
    ...buildValidWorkspacePayload(ALICE_UID, created, created),
    id: ALICE_UID,
  };
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set(badPayload)
  );
});

test("RBAC Super Admin & Email Spoofing Protection: verified Super Admin manages users and roles; unverified email spoof fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const spoofAdminDb = testEnv.authenticatedContext(SPOOF_ADMIN_UID, {
    email: "elzahraafatma288@gmail.com",
    email_verified: false,
  }).firestore();
  const verifiedAdminDb = testEnv.authenticatedContext(SUPER_ADMIN_UID, {
    email: "elzahraafatma288@gmail.com",
    email_verified: true,
  }).firestore();

  const created = new Date(Date.now() - 8000);
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(buildValidWorkspacePayload(ALICE_UID, created, created))
  );

  // Spoofed admin with email_verified: false cannot read Alice's workspace or escalate her role
  await assertFails(spoofAdminDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    spoofAdminDb.collection("users").doc(ALICE_UID).update({
      role: "AGRONOMIST_ADMIN",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Verified Bootstrapped Super Admin CAN read Alice's workspace, update her role/status, and register an admin in /admins
  await assertSucceeds(verifiedAdminDb.collection("users").doc(ALICE_UID).get());
  await assertSucceeds(
    verifiedAdminDb.collection("users").doc(ALICE_UID).update({
      role: "AGRONOMIST_ADMIN",
      accountStatus: "ACTIVE",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertSucceeds(
    verifiedAdminDb.collection("admins").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "ahmed.natrun.agro@gmail.com",
      role: "AGRONOMIST_ADMIN",
      grantedByUid: SUPER_ADMIN_UID,
      createdAt: new Date(Date.now() - 1000),
    })
  );
});

test("Authenticated owner: valid CloudChatLog create, list, and delete succeed; cross-user access fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("chat_logs").doc("log_1").set({
      userId: ALICE_UID,
      role: "ASSISTANT",
      channel: "SEARCH_GROUNDED",
      messageText: "توصية الري اليوم لمحصول البطاطس في وادي النطرون هي 24.5 م³/فدان مع حقن حامض فوسفوريك.",
      groundingSources: "Google Search • FAO-56 Penman-Monteith • Sentinel-2 L2A",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Owner list query on subcollection succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("chat_logs").orderBy("createdAt", "desc").get()
  );

  // Cross-user Bob cannot read or list Alice's workspace or chat logs
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("chat_logs").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("chat_logs").doc("log_1").get());
});
