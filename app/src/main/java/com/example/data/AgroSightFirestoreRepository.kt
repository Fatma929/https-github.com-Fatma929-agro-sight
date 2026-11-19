package com.example.data

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.example.R
import com.example.model.UserRole
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

/**
 * Redacts sensitive authentication and personal identifiers (emails, full UIDs, phone numbers)
 * before writing structured diagnostics to logcat.
 */
fun redactUidForLog(uid: String?): String {
    if (uid.isNullOrBlank()) return "anonymous"
    return if (uid.length <= 4) "***" else "${uid.take(4)}***"
}

fun redactPathForLog(path: String?): String {
    if (path.isNullOrBlank()) return "unknown"
    return path.replace(Regex("users/([^/]+)")) { matchResult ->
        val uid = matchResult.groupValues.getOrNull(1)
        "users/${redactUidForLog(uid)}"
    }
}

fun handleFirestoreError(exception: Throwable, operationType: OperationType, path: String?): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val auth = try {
        FirebaseAuth.getInstance()
    } catch (_: Exception) {
        null
    }
    val currentUser = auth?.currentUser

    // Privacy-by-design: log only provider IDs, never raw email addresses or tokens
    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("isSignedIn", currentUser != null)
        put("redactedUserId", redactUidForLog(currentUser?.uid))
        put("emailVerified", currentUser?.isEmailVerified ?: false)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorCode = (rootError as? FirebaseFirestoreException)?.code?.name ?: "UNKNOWN"
    val errorInfoJson = JSONObject().apply {
        put("errorCode", errorCode)
        put("error", (rootError.message ?: rootError.javaClass.simpleName).take(160))
        put("operationType", operationType.value)
        put("path", redactPathForLog(path))
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Operation Failed: $jsonString")
    return jsonString
}

/**
 * Cloud-synced agricultural workspace stored in `/users/{userId}`.
 *
 * Security & Data Minimization Policy (Phase 10):
 * - Separates authentication/personal identifiers from agricultural telemetry.
 * - National IDs (`nationalId`), personal phone numbers (`whatsappPhone`), and raw email addresses
 *   are NEVER uploaded to Firestore `/users/{userId}` documents and are never fabricated with zeros
 *   or placeholder defaults.
 */
@Keep
@IgnoreExtraProperties
data class FarmerCloudWorkspace(
    @DocumentId val id: String = "",
    val userId: String = "",
    val role: String = "FARMER",
    val accountStatus: String = "ACTIVE",
    val farmerName: String = "",
    val fieldName: String = "",
    val region: String = "",
    val cropType: String = "",
    val areaFeddans: Double = 60.0,
    val latitude: Double = 30.3889,
    val longitude: Double = 30.3414,
    val soilPh: Double = 7.8,
    val soilEce: Double = 2.4,
    val waterEcw: Double = 1.4,
    val ndviMean: Double = 0.72,
    val ndreMean: Double = 0.24,
    val psriMean: Double = 0.19,
    val ndmiMean: Double = 0.31,
    val detectedRiskName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudChatLog(
    @DocumentId val id: String = "",
    val userId: String = "",
    val role: String = "ASSISTANT",
    val channel: String = "GEMINI_CHAT",
    val messageText: String = "",
    val groundingSources: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

interface CloudWorkspaceRepository {
    val currentUser: FirebaseUser?
    fun observeAuthState(): Flow<FirebaseUser?>
    suspend fun signInWithGoogleIdToken(idToken: String): Result<FirebaseUser>
    fun signOut()
    fun observeWorkspace(userId: String): Flow<FarmerCloudWorkspace?>
    fun observeCloudChatLogs(userId: String): Flow<List<CloudChatLog>>
    suspend fun upsertFarmerWorkspace(
        userId: String,
        farmerName: String,
        whatsappPhone: String = "",
        gmailAddress: String = "",
        nationalId: String = "",
        fieldName: String,
        region: String,
        cropType: String,
        areaFeddans: Float,
        latitude: Double,
        longitude: Double,
        soilPh: Float,
        soilEce: Float,
        waterEcw: Float,
        ndviMean: Float,
        ndreMean: Float,
        psriMean: Float,
        ndmiMean: Float,
        detectedRiskName: String
    ): Result<Unit>
    suspend fun adminUpdateCloudUserRoleAndStatus(
        targetUserId: String,
        newRole: String,
        newStatus: String
    ): Result<Unit>
    suspend fun appendCloudChatLog(
        userId: String,
        role: String,
        channel: String,
        messageText: String,
        groundingSources: String
    ): Result<Unit>
    suspend fun deleteCloudWorkspace(userId: String): Result<Unit> = Result.success(Unit)
}

class AgroSightFirestoreRepository(
    private val db: FirebaseFirestore?,
    private val auth: FirebaseAuth?
) : CloudWorkspaceRepository {
    constructor(context: Context) : this(
        db = try {
            val databaseId = context.applicationContext.getString(R.string.firestore_database_id)
            FirebaseFirestore.getInstance(databaseId)
        } catch (_: Exception) {
            null
        },
        auth = try {
            FirebaseAuth.getInstance()
        } catch (_: Exception) {
            null
        }
    )

    override val currentUser: FirebaseUser?
        get() = auth?.currentUser

    override fun observeAuthState(): Flow<FirebaseUser?> = callbackFlow {
        val firebaseAuth = auth
        if (firebaseAuth == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        val listener = FirebaseAuth.AuthStateListener { a ->
            trySend(a.currentUser)
        }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): Result<FirebaseUser> {
        val firebaseAuth = auth ?: return Result.failure(IllegalStateException("Firebase Auth is not initialized"))
        if (idToken.isBlank()) {
            return Result.failure(IllegalArgumentException("Empty Google ID token"))
        }
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = firebaseAuth.signInWithCredential(credential).await()
            val user = authResult.user ?: error("Google Sign-In returned null FirebaseUser")
            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AgroSightFirestoreRepo", "Google Sign-In failed: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    override fun signOut() {
        try {
            auth?.signOut()
        } catch (_: Exception) {
        }
    }

    override fun observeWorkspace(userId: String): Flow<FarmerCloudWorkspace?> {
        val firestore = db
        if (firestore == null || userId.isBlank()) {
            return callbackFlow {
                trySend(null)
                awaitClose { }
            }
        }
        return firestore.collection("users").document(userId)
            .snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        FarmerCloudWorkspace::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
            }
    }

    override fun observeCloudChatLogs(userId: String): Flow<List<CloudChatLog>> {
        val firestore = db
        if (firestore == null || userId.isBlank()) {
            return callbackFlow {
                trySend(emptyList())
                awaitClose { }
            }
        }
        return firestore.collection("users").document(userId)
            .collection("chat_logs")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(40)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    CloudChatLog::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
    }

    override suspend fun upsertFarmerWorkspace(
        userId: String,
        farmerName: String,
        whatsappPhone: String,
        gmailAddress: String,
        nationalId: String,
        fieldName: String,
        region: String,
        cropType: String,
        areaFeddans: Float,
        latitude: Double,
        longitude: Double,
        soilPh: Float,
        soilEce: Float,
        waterEcw: Float,
        ndviMean: Float,
        ndreMean: Float,
        psriMean: Float,
        ndmiMean: Float,
        detectedRiskName: String
    ): Result<Unit> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore not available"))
        val currentAuthUser = auth?.currentUser
            ?: return Result.failure(IllegalStateException("يرجى تسجيل الدخول بحساب Google أولاً للمزامنة مع السحابة"))
        val uid = currentAuthUser.uid
        if (uid.isBlank() || uid != userId) {
            return Result.failure(IllegalStateException("UID mismatch"))
        }

        // Strict input validation: reject invalid coordinates or out-of-range agronomic values
        if (latitude.isNaN() || latitude !in -90.0..90.0 || longitude.isNaN() || longitude !in -180.0..180.0) {
            return Result.failure(IllegalArgumentException("Invalid geographic coordinates"))
        }
        if (farmerName.trim().length < 2 || fieldName.trim().length < 2) {
            return Result.failure(IllegalArgumentException("Farmer name and field name must be at least 2 characters"))
        }

        val isVerifiedBootstrappedAdmin = currentAuthUser.isEmailVerified &&
            currentAuthUser.email?.equals(UserRole.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true) == true
        val initialRole = if (isVerifiedBootstrappedAdmin) "SUPER_ADMIN" else "FARMER"

        // Security & Privacy Policy:
        // Never upload nationalId, whatsappPhone, or gmailAddress to Firestore /users/{userId},
        // and never fabricate missing national IDs with zeros or fake phone numbers.
        val docRef = firestore.collection("users").document(userId)
        val safeName = farmerName.trim().take(120)
        val safeField = fieldName.trim().take(150)
        val safeRegion = region.trim().ifBlank { "غير محدد" }.take(100)
        val safeCrop = cropType.trim().ifBlank { "غير محدد" }.take(80)
        val safeRisk = detectedRiskName.trim().ifBlank { "غير محدد" }.take(200)

        return try {
            val existingSnap = docRef.get().await()
            if (!existingSnap.exists()) {
                val createPayload = mapOf(
                    "userId" to userId,
                    "role" to initialRole,
                    "accountStatus" to "ACTIVE",
                    "farmerName" to safeName,
                    "fieldName" to safeField,
                    "region" to safeRegion,
                    "cropType" to safeCrop,
                    "areaFeddans" to areaFeddans.toDouble().coerceIn(1.0, 10000.0),
                    "latitude" to latitude.coerceIn(-90.0, 90.0),
                    "longitude" to longitude.coerceIn(-180.0, 180.0),
                    "soilPh" to soilPh.toDouble().coerceIn(3.5, 10.5),
                    "soilEce" to soilEce.toDouble().coerceIn(0.0, 50.0),
                    "waterEcw" to waterEcw.toDouble().coerceIn(0.0, 50.0),
                    "ndviMean" to ndviMean.toDouble().coerceIn(-1.0, 1.0),
                    "ndreMean" to ndreMean.toDouble().coerceIn(-1.0, 1.0),
                    "psriMean" to psriMean.toDouble().coerceIn(-1.0, 1.0),
                    "ndmiMean" to ndmiMean.toDouble().coerceIn(-1.0, 1.0),
                    "detectedRiskName" to safeRisk,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            } else {
                val updatePayload = mapOf(
                    "farmerName" to safeName,
                    "fieldName" to safeField,
                    "region" to safeRegion,
                    "cropType" to safeCrop,
                    "areaFeddans" to areaFeddans.toDouble().coerceIn(1.0, 10000.0),
                    "latitude" to latitude.coerceIn(-90.0, 90.0),
                    "longitude" to longitude.coerceIn(-180.0, 180.0),
                    "soilPh" to soilPh.toDouble().coerceIn(3.5, 10.5),
                    "soilEce" to soilEce.toDouble().coerceIn(0.0, 50.0),
                    "waterEcw" to waterEcw.toDouble().coerceIn(0.0, 50.0),
                    "ndviMean" to ndviMean.toDouble().coerceIn(-1.0, 1.0),
                    "ndreMean" to ndreMean.toDouble().coerceIn(-1.0, 1.0),
                    "psriMean" to psriMean.toDouble().coerceIn(-1.0, 1.0),
                    "ndmiMean" to ndmiMean.toDouble().coerceIn(-1.0, 1.0),
                    "detectedRiskName" to safeRisk,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updatePayload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users/$userId")
            Result.failure(e)
        }
    }

    override suspend fun adminUpdateCloudUserRoleAndStatus(
        targetUserId: String,
        newRole: String,
        newStatus: String
    ): Result<Unit> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore not available"))
        if (auth?.currentUser == null) return Result.failure(IllegalStateException("Not signed in"))
        if (targetUserId.isBlank()) return Result.failure(IllegalArgumentException("Target userId is blank"))
        val validRole = if (newRole in listOf("SUPER_ADMIN", "AGRONOMIST_ADMIN", "FARMER")) newRole else "FARMER"
        val validStatus = if (newStatus in listOf("ACTIVE", "SUSPENDED", "PENDING_VERIFICATION")) newStatus else "ACTIVE"
        val docRef = firestore.collection("users").document(targetUserId)
        return try {
            docRef.update(
                mapOf(
                    "role" to validRole,
                    "accountStatus" to validStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$targetUserId")
            Result.failure(e)
        }
    }

    override suspend fun appendCloudChatLog(
        userId: String,
        role: String,
        channel: String,
        messageText: String,
        groundingSources: String
    ): Result<Unit> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore not available"))
        val uid = auth?.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        if (uid.isBlank() || uid != userId) return Result.failure(IllegalStateException("UID mismatch"))

        val logId = "log_${System.currentTimeMillis()}"
        val docRef = firestore.collection("users").document(userId)
            .collection("chat_logs").document(logId)

        val validRole = if (role in listOf("USER", "ASSISTANT", "WHATSAPP_OUTBOUND")) role else "ASSISTANT"
        val validChannel = if (channel in listOf("GEMINI_CHAT", "VOICE_CONVERSATION", "SEARCH_GROUNDED", "MAPS_GROUNDED", "WA_ME_DIRECT")) {
            channel
        } else {
            "GEMINI_CHAT"
        }

        val payload = mapOf(
            "userId" to userId,
            "role" to validRole,
            "channel" to validChannel,
            "messageText" to messageText.trim().ifBlank { "-" }.take(3900),
            "groundingSources" to groundingSources.trim().take(950),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "users/$userId/chat_logs/$logId")
            Result.failure(e)
        }
    }

    override suspend fun deleteCloudWorkspace(userId: String): Result<Unit> {
        if (userId.isBlank()) {
            return Result.failure(IllegalArgumentException("Blank userId"))
        }
        val uid = auth?.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        if (uid != userId) {
            return Result.failure(
                SecurityException("Unauthorized workspace deletion: authenticated UID does not match requested userId")
            )
        }
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore not available"))
        return try {
            val chatCollection = firestore.collection("users").document(userId).collection("chat_logs")
            var batchIteration = 0
            while (batchIteration < 50) {
                batchIteration++
                val pageSnapshot = chatCollection.limit(100).get().await()
                if (pageSnapshot.isEmpty) {
                    break
                }
                val writeBatch = firestore.batch()
                for (doc in pageSnapshot.documents) {
                    writeBatch.delete(doc.reference)
                }
                writeBatch.commit().await()
                if (pageSnapshot.size() < 100) {
                    break
                }
            }
            firestore.collection("users").document(userId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/$userId")
            Result.failure(e)
        }
    }
}

