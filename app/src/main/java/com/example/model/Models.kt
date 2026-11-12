package com.example.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DeviceInfo(
    @DocumentId val id: String = "",
    val deviceId: String = "",
    val userId: String = "",
    val name: String = "",
    val model: String = "",
    val mode: String = "MONITOR", // "MONITOR" or "DEVICE"
    val isOnline: Boolean = true,
    val batteryPercentage: Int = 100,
    val isCharging: Boolean = false,
    val locationSharingPaused: Boolean = false,
    @ServerTimestamp val lastActive: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DevicePair(
    @DocumentId val id: String = "",
    val pairId: String = "",
    val monitorDeviceId: String = "",
    val monitorUserId: String = "",
    val childDeviceId: String = "",
    val childUserId: String = "",
    val childDeviceName: String = "",
    val status: String = "ACTIVE", // "ACTIVE", "PAUSED", "UNLINKED"
    @ServerTimestamp val pairedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PairingCode(
    @DocumentId val id: String = "",
    val code: String = "",
    val monitorDeviceId: String = "",
    val monitorUserId: String = "",
    val monitorDeviceName: String = "",
    val isUsed: Boolean = false,
    val expiresAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class LocationUpdate(
    @DocumentId val id: String = "",
    val deviceId: String = "",
    val userId: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Double = 0.0,
    val speed: Double = 0.0,
    @ServerTimestamp val timestamp: Timestamp? = null
)

@IgnoreExtraProperties
data class SessionRequest(
    @DocumentId val id: String = "",
    val requestId: String = "",
    val monitorDeviceId: String = "",
    val monitorUserId: String = "",
    val childDeviceId: String = "",
    val childUserId: String = "",
    val sessionType: String = "CAMERA", // "CAMERA" or "MIC"
    val status: String = "PENDING", // "PENDING", "ACCEPTED", "DECLINED", "ENDED"
    val offerSdp: String = "",
    val answerSdp: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SessionCandidate(
    @DocumentId val id: String = "",
    val candidateId: String = "",
    val sender: String = "MONITOR", // "MONITOR" or "CHILD"
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val sdp: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ActivityLog(
    @DocumentId val id: String = "",
    val logId: String = "",
    val deviceId: String = "",
    val userId: String = "",
    val eventType: String = "",
    val details: String = "",
    @ServerTimestamp val timestamp: Timestamp? = null
)
