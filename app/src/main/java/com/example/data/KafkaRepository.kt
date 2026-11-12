package com.example.data

import android.content.Context
import com.example.R
import com.example.model.ActivityLog
import com.example.model.DeviceInfo
import com.example.model.DevicePair
import com.example.model.LocationUpdate
import com.example.model.PairingCode
import com.example.model.SessionCandidate
import com.example.model.SessionRequest
import com.example.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.UUID

class KafkaRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    // --- User Profile ---
    suspend fun getOrCreateUserProfile(email: String, displayName: String): UserProfile {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        return try {
            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                snapshot.toObject(UserProfile::class.java) ?: UserProfile(userId = uid, email = email, displayName = displayName)
            } else {
                val profile = UserProfile(
                    userId = uid,
                    email = email,
                    displayName = displayName
                )
                val payload = mapOf(
                    "userId" to uid,
                    "email" to email,
                    "displayName" to displayName,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
                profile
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            UserProfile(userId = uid, email = email, displayName = displayName)
        }
    }

    // --- Device Registration & Telemetry ---
    suspend fun registerDevice(device: DeviceInfo) {
        val uid = requireUserId()
        val docRef = db.collection("devices").document(device.deviceId)
        val payload = mapOf(
            "deviceId" to device.deviceId,
            "userId" to uid,
            "name" to device.name,
            "model" to device.model,
            "mode" to device.mode,
            "isOnline" to device.isOnline,
            "batteryPercentage" to device.batteryPercentage,
            "isCharging" to device.isCharging,
            "locationSharingPaused" to device.locationSharingPaused,
            "lastActive" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun updateDeviceTelemetry(
        deviceId: String,
        batteryPercentage: Int,
        isCharging: Boolean,
        isOnline: Boolean
    ) {
        val uid = requireUserId()
        val docRef = db.collection("devices").document(deviceId)
        val updates = mapOf(
            "batteryPercentage" to batteryPercentage,
            "isCharging" to isCharging,
            "isOnline" to isOnline,
            "lastActive" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.update(updates).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
        }
    }

    suspend fun setLocationSharingPaused(deviceId: String, paused: Boolean) {
        val uid = requireUserId()
        val docRef = db.collection("devices").document(deviceId)
        try {
            docRef.update(
                mapOf(
                    "locationSharingPaused" to paused,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    fun observeDevice(deviceId: String): Flow<DeviceInfo?> {
        val docRef = db.collection("devices").document(deviceId)
        return docRef.snapshots()
            .map { it.toObject(DeviceInfo::class.java) }
            .catch { e ->
                handleFirestoreError(e, OperationType.GET, docRef.path)
                emit(null)
            }
    }

    // --- Device Pairing ---
    suspend fun generatePairingCode(
        monitorDeviceId: String,
        monitorDeviceName: String
    ): Result<PairingCode> {
        val uid = requireUserId()
        // Generate random 6-digit numeric string
        val code = (100000..999999).random().toString()
        val expiresAt = Timestamp(Date(System.currentTimeMillis() + 5 * 60 * 1000)) // 5 minutes
        val docRef = db.collection("pairing_codes").document(code)

        val payload = mapOf(
            "code" to code,
            "monitorDeviceId" to monitorDeviceId,
            "monitorUserId" to uid,
            "monitorDeviceName" to monitorDeviceName,
            "isUsed" to false,
            "expiresAt" to expiresAt,
            "createdAt" to FieldValue.serverTimestamp()
        )

        return try {
            docRef.set(payload).await()
            Result.success(
                PairingCode(
                    code = code,
                    monitorDeviceId = monitorDeviceId,
                    monitorUserId = uid,
                    monitorDeviceName = monitorDeviceName,
                    isUsed = false,
                    expiresAt = expiresAt
                )
            )
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun verifyAndRedeemPairingCode(
        code: String,
        childDeviceId: String,
        childDeviceName: String
    ): Result<DevicePair> {
        val childUid = requireUserId()
        val codeDocRef = db.collection("pairing_codes").document(code)

        return try {
            val snapshot = codeDocRef.get().await()
            if (!snapshot.exists()) {
                return Result.failure(IllegalArgumentException("Pairing code not found"))
            }

            val pairingCode = snapshot.toObject(PairingCode::class.java)
                ?: return Result.failure(IllegalArgumentException("Invalid pairing code format"))

            if (pairingCode.isUsed) {
                return Result.failure(IllegalStateException("Pairing code has already been used"))
            }

            val now = Timestamp.now()
            if (pairingCode.expiresAt != null && pairingCode.expiresAt.seconds < now.seconds) {
                return Result.failure(IllegalStateException("Pairing code has expired"))
            }

            // Mark code as used
            codeDocRef.update("isUsed", true).await()

            // Create device pair
            val pairId = "pair_${pairingCode.monitorDeviceId}_$childDeviceId"
            val pairDocRef = db.collection("device_pairs").document(pairId)
            val pairPayload = mapOf(
                "pairId" to pairId,
                "monitorDeviceId" to pairingCode.monitorDeviceId,
                "monitorUserId" to pairingCode.monitorUserId,
                "childDeviceId" to childDeviceId,
                "childUserId" to childUid,
                "childDeviceName" to childDeviceName,
                "status" to "ACTIVE",
                "pairedAt" to FieldValue.serverTimestamp()
            )
            pairDocRef.set(pairPayload).await()

            // Log activity
            logActivity(childDeviceId, "DEVICE_PAIRED", "Paired with monitor ${pairingCode.monitorDeviceName}")

            val resultPair = DevicePair(
                id = pairId,
                pairId = pairId,
                monitorDeviceId = pairingCode.monitorDeviceId,
                monitorUserId = pairingCode.monitorUserId,
                childDeviceId = childDeviceId,
                childUserId = childUid,
                childDeviceName = childDeviceName,
                status = "ACTIVE"
            )
            Result.success(resultPair)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, codeDocRef.path)
            Result.failure(e)
        }
    }

    fun observeMonitorDevicePairs(monitorUserId: String): Flow<List<DevicePair>> {
        val path = "device_pairs"
        return db.collection(path)
            .whereEqualTo("monitorUserId", monitorUserId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(DevicePair::class.java).filter { it.status == "ACTIVE" }
            }
            .catch { e ->
                handleFirestoreError(e, OperationType.LIST, path)
                emit(emptyList())
            }
    }

    fun observeChildDevicePairs(childDeviceId: String): Flow<List<DevicePair>> {
        val path = "device_pairs"
        return db.collection(path)
            .whereEqualTo("childDeviceId", childDeviceId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(DevicePair::class.java).filter { it.status == "ACTIVE" }
            }
            .catch { e ->
                handleFirestoreError(e, OperationType.LIST, path)
                emit(emptyList())
            }
    }

    suspend fun unlinkDevicePair(pairId: String) {
        val path = "device_pairs/$pairId"
        try {
            db.collection("device_pairs").document(pairId).update("status", "UNLINKED").await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            throw e
        }
    }

    // --- Real-time Location Updates ---
    suspend fun sendLocationUpdate(
        deviceId: String,
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        speed: Double
    ) {
        val uid = requireUserId()
        val docRef = db.collection("location_updates").document(deviceId)
        val payload = mapOf(
            "deviceId" to deviceId,
            "userId" to uid,
            "latitude" to latitude,
            "longitude" to longitude,
            "accuracy" to accuracy,
            "speed" to speed,
            "timestamp" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    fun observeLocationUpdate(deviceId: String): Flow<LocationUpdate?> {
        val docRef = db.collection("location_updates").document(deviceId)
        return docRef.snapshots()
            .map { it.toObject(LocationUpdate::class.java) }
            .catch { e ->
                handleFirestoreError(e, OperationType.GET, docRef.path)
                emit(null)
            }
    }

    // --- WebRTC Remote Sessions ---
    suspend fun createSessionRequest(
        monitorDeviceId: String,
        childDeviceId: String,
        childUserId: String,
        sessionType: String,
        offerSdp: String
    ): Result<String> {
        val monitorUid = requireUserId()
        val requestId = "req_${UUID.randomUUID()}"
        val docRef = db.collection("session_requests").document(requestId)

        val payload = mapOf(
            "requestId" to requestId,
            "monitorDeviceId" to monitorDeviceId,
            "monitorUserId" to monitorUid,
            "childDeviceId" to childDeviceId,
            "childUserId" to childUserId,
            "sessionType" to sessionType,
            "status" to "PENDING",
            "offerSdp" to offerSdp,
            "answerSdp" to "",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            docRef.set(payload).await()
            logActivity(childDeviceId, "${sessionType}_REQUESTED", "Monitor requested live $sessionType check-in")
            Result.success(requestId)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observePendingIncomingSessionRequests(childDeviceId: String): Flow<List<SessionRequest>> {
        val path = "session_requests"
        return db.collection(path)
            .whereEqualTo("childDeviceId", childDeviceId)
            .whereEqualTo("status", "PENDING")
            .snapshots()
            .map { it.toObjects(SessionRequest::class.java) }
            .catch { e ->
                handleFirestoreError(e, OperationType.LIST, path)
                emit(emptyList())
            }
    }

    fun observeSessionRequest(requestId: String): Flow<SessionRequest?> {
        val docRef = db.collection("session_requests").document(requestId)
        return docRef.snapshots()
            .map { it.toObject(SessionRequest::class.java) }
            .catch { e ->
                handleFirestoreError(e, OperationType.GET, docRef.path)
                emit(null)
            }
    }

    suspend fun respondToSessionRequest(
        requestId: String,
        status: String, // "ACCEPTED" or "DECLINED"
        answerSdp: String = ""
    ) {
        val docRef = db.collection("session_requests").document(requestId)
        val updates = mutableMapOf<String, Any>(
            "status" to status,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (answerSdp.isNotEmpty()) {
            updates["answerSdp"] = answerSdp
        }
        try {
            docRef.update(updates).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
        }
    }

    suspend fun endSession(requestId: String) {
        val docRef = db.collection("session_requests").document(requestId)
        try {
            docRef.update(
                mapOf(
                    "status" to "ENDED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
        }
    }

    // --- ICE Candidates ---
    suspend fun sendIceCandidate(
        requestId: String,
        sender: String,
        sdpMid: String,
        sdpMLineIndex: Int,
        sdp: String
    ) {
        val candidateId = "cand_${UUID.randomUUID()}"
        val docRef = db.collection("session_candidates")
            .document(requestId)
            .collection("candidates")
            .document(candidateId)

        val payload = mapOf(
            "candidateId" to candidateId,
            "sender" to sender,
            "sdpMid" to sdpMid,
            "sdpMLineIndex" to sdpMLineIndex,
            "sdp" to sdp,
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
        }
    }

    fun observeRemoteIceCandidates(
        requestId: String,
        remoteSender: String
    ): Flow<List<SessionCandidate>> {
        val path = "session_candidates/$requestId/candidates"
        return db.collection("session_candidates")
            .document(requestId)
            .collection("candidates")
            .whereEqualTo("sender", remoteSender)
            .snapshots()
            .map { it.toObjects(SessionCandidate::class.java) }
            .catch { e ->
                handleFirestoreError(e, OperationType.LIST, path)
                emit(emptyList())
            }
    }

    // --- Activity Logs ---
    suspend fun logActivity(deviceId: String, eventType: String, details: String) {
        val uid = auth.currentUser?.uid ?: return
        val logId = "log_${UUID.randomUUID()}"
        val docRef = db.collection("activity_logs").document(logId)
        val payload = mapOf(
            "logId" to logId,
            "deviceId" to deviceId,
            "userId" to uid,
            "eventType" to eventType,
            "details" to details,
            "timestamp" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
        }
    }

    fun observeActivityLogs(deviceId: String): Flow<List<ActivityLog>> {
        val path = "activity_logs"
        return db.collection(path)
            .whereEqualTo("deviceId", deviceId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(ActivityLog::class.java)
                    .sortedByDescending { it.timestamp?.seconds ?: 0L }
            }
            .catch { e ->
                handleFirestoreError(e, OperationType.LIST, path)
                emit(emptyList())
            }
    }
}
