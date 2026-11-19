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
const AGRONOMIST_UID = "agronomist_dr_tarek_777";
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

test("Authenticated owner (FARMER): valid workspace create/update/delete succeed; nationalId upload and self-escalation fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);

  // Reject self-assigned SUPER_ADMIN role on registration
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set(
      buildValidWorkspacePayload(ALICE_UID, created, created, "SUPER_ADMIN", "ACTIVE")
    )
  );

  // Phase 10 Protection: Reject uploading nationalId or whatsappPhone to Firestore /users/{userId}
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set({
      ...buildValidWorkspacePayload(ALICE_UID, created, created),
      nationalId: "28805141801492",
    })
  );

  // Valid FARMER registration without sensitive PII succeeds
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

  // Owner can delete their own cloud workspace (Phase 10 Data Deletion Workflow)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).delete()
  );
});

test("RBAC Least-Privilege: SUPER_ADMIN vs AGRONOMIST_ADMIN vs Spoofed Admin", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const agronomistDb = testEnv.authenticatedContext(AGRONOMIST_UID).firestore();
  const spoofAdminDb = testEnv.authenticatedContext(SPOOF_ADMIN_UID, {
    email: "elzahraafatma288@gmail.com",
    email_verified: false,
  }).firestore();
  const verifiedSuperAdminDb = testEnv.authenticatedContext(SUPER_ADMIN_UID, {
    email: "elzahraafatma288@gmail.com",
    email_verified: true,
  }).firestore();

  const created = new Date(Date.now() - 8000);
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(buildValidWorkspacePayload(ALICE_UID, created, created))
  );

  // 1. Spoofed admin with email_verified: false cannot read Alice's workspace or escalate her role
  await assertFails(spoofAdminDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    spoofAdminDb.collection("users").doc(ALICE_UID).update({
      role: "AGRONOMIST_ADMIN",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // 2. Super Admin provisions AGRONOMIST_ADMIN in /admins/{agronomistUid}
  await assertSucceeds(
    verifiedSuperAdminDb.collection("admins").doc(AGRONOMIST_UID).set({
      userId: AGRONOMIST_UID,
      email: "tarek.agronomist@agrosight.eg",
      role: "AGRONOMIST_ADMIN",
      grantedByUid: SUPER_ADMIN_UID,
      createdAt: new Date(Date.now() - 1000),
    })
  );

  // 3. Authorized AGRONOMIST_ADMIN can read Alice's workspace and update agronomic advisory fields
  await assertSucceeds(agronomistDb.collection("users").doc(ALICE_UID).get());
  await assertSucceeds(
    agronomistDb.collection("users").doc(ALICE_UID).update({
      soilPh: 7.1,
      detectedRiskName: "تمت المعاينة الحقلية: حالة مستقرة بعد معادلة الحموضة",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // 4. Least-privilege enforcement: AGRONOMIST_ADMIN cannot escalate roles, change accountStatus, delete users, or modify /admins
  await assertFails(
    agronomistDb.collection("users").doc(ALICE_UID).update({
      role: "SUPER_ADMIN",
      updatedAt: new Date(Date.now() - 400),
    })
  );
  await assertFails(agronomistDb.collection("users").doc(ALICE_UID).delete());
  await assertFails(
    agronomistDb.collection("admins").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      role: "AGRONOMIST_ADMIN",
      grantedByUid: AGRONOMIST_UID,
      createdAt: new Date(Date.now() - 300),
    })
  );

  // 5. Verified SUPER_ADMIN CAN update user role and accountStatus
  await assertSucceeds(
    verifiedSuperAdminDb.collection("users").doc(ALICE_UID).update({
      role: "AGRONOMIST_ADMIN",
      accountStatus: "ACTIVE",
      updatedAt: new Date(Date.now() - 200),
    })
  );
});

test("Cross-farmer isolation: Bob cannot read or modify Alice's workspace or private chat logs", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const agronomistDb = testEnv.authenticatedContext(AGRONOMIST_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(buildValidWorkspacePayload(ALICE_UID, created, created))
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("chat_logs").doc("log_1").set({
      userId: ALICE_UID,
      role: "ASSISTANT",
      channel: "SEARCH_GROUNDED",
      messageText: "توصية الري اليوم لمحصول البطاطس في وادي النطرون هي 24.5 م³/فدان.",
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

  // Cross-user Bob cannot delete Alice's chat logs or workspace
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("chat_logs").doc("log_1").delete());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).delete());

  // Even an AGRONOMIST_ADMIN cannot read or delete private farmer chat_logs (only owner or SUPER_ADMIN)
  await assertFails(agronomistDb.collection("users").doc(ALICE_UID).collection("chat_logs").get());
  await assertFails(agronomistDb.collection("users").doc(ALICE_UID).collection("chat_logs").doc("log_1").delete());

  // Owner Alice can batch-delete her chat_logs subcollection and parent workspace without leaving orphaned docs
  const batch = aliceDb.batch();
  batch.delete(aliceDb.collection("users").doc(ALICE_UID).collection("chat_logs").doc("log_1"));
  batch.delete(aliceDb.collection("users").doc(ALICE_UID));
  await assertSucceeds(batch.commit());
});

