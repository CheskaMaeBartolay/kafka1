package com.example.webrtc

import android.content.Context
import android.util.Log
import com.example.data.KafkaRepository
import com.example.model.SessionCandidate
import com.example.model.SessionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class ConnectionQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    DISCONNECTED
}

sealed class SessionState {
    object Idle : SessionState()
    data class Requesting(val requestId: String) : SessionState()
    data class IncomingRequest(val request: SessionRequest) : SessionState()
    data class Connecting(val requestId: String, val sessionType: String) : SessionState()
    data class Connected(
        val requestId: String,
        val sessionType: String,
        val isMuted: Boolean = false,
        val isFrontCamera: Boolean = true,
        val quality: ConnectionQuality = ConnectionQuality.EXCELLENT
    ) : SessionState()
    data class Ended(val reason: String) : SessionState()
}

class WebRtcSessionManager(
    private val context: Context,
    private val repository: KafkaRepository
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sessionListenerJob: Job? = null
    private var candidateListenerJob: Job? = null

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var currentRequestId: String? = null
    private var isChildSide: Boolean = false

    // Monitor starts request (CAMERA or MIC)
    fun initiateRequest(
        monitorDeviceId: String,
        childDeviceId: String,
        childUserId: String,
        sessionType: String,
        onComplete: (Result<String>) -> Unit
    ) {
        managerScope.launch {
            isChildSide = false
            // Create synthetic WebRTC SDP offer
            val simulatedOfferSdp = "v=0\r\no=- ${System.currentTimeMillis()} 2 IN IP4 127.0.0.1\r\ns=KafkaWebRTC\r\nt=0 0\r\nm=${if (sessionType == "CAMERA") "video" else "audio"} 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0"

            val result = repository.createSessionRequest(
                monitorDeviceId = monitorDeviceId,
                childDeviceId = childDeviceId,
                childUserId = childUserId,
                sessionType = sessionType,
                offerSdp = simulatedOfferSdp
            )

            result.onSuccess { reqId ->
                currentRequestId = reqId
                _sessionState.value = SessionState.Requesting(reqId)
                listenToSessionChanges(reqId, sessionType)
                onComplete(Result.success(reqId))
            }.onFailure { err ->
                _sessionState.value = SessionState.Ended("Failed to initiate request: ${err.message}")
                onComplete(Result.failure(err))
            }
        }
    }

    // Child listens for incoming requests
    fun listenForIncomingRequests(childDeviceId: String, onRequestReceived: (SessionRequest) -> Unit) {
        managerScope.launch {
            repository.observePendingIncomingSessionRequests(childDeviceId).collect { requests ->
                if (requests.isNotEmpty() && _sessionState.value is SessionState.Idle) {
                    val latest = requests.first()
                    _sessionState.value = SessionState.IncomingRequest(latest)
                    onRequestReceived(latest)
                }
            }
        }
    }

    // Child responds: ALLOW
    fun acceptRequest(request: SessionRequest) {
        isChildSide = true
        currentRequestId = request.requestId
        _sessionState.value = SessionState.Connecting(request.requestId, request.sessionType)

        managerScope.launch {
            val simulatedAnswerSdp = "v=0\r\no=- ${System.currentTimeMillis()} 2 IN IP4 127.0.0.1\r\ns=KafkaAnswer\r\nt=0 0\r\nm=${if (request.sessionType == "CAMERA") "video" else "audio"} 9 UDP/TLS/RTP/SAVPF 96"
            repository.respondToSessionRequest(
                requestId = request.requestId,
                status = "ACCEPTED",
                answerSdp = simulatedAnswerSdp
            )

            // Send local candidate
            repository.sendIceCandidate(
                requestId = request.requestId,
                sender = "CHILD",
                sdpMid = "0",
                sdpMLineIndex = 0,
                sdp = "candidate:1 1 UDP 2130706431 127.0.0.1 50000 typ host"
            )

            listenToSessionChanges(request.requestId, request.sessionType)

            // Connect session
            _sessionState.value = SessionState.Connected(
                requestId = request.requestId,
                sessionType = request.sessionType,
                isMuted = false,
                isFrontCamera = true,
                quality = ConnectionQuality.EXCELLENT
            )
        }
    }

    // Child responds: DECLINE
    fun declineRequest(request: SessionRequest) {
        managerScope.launch {
            repository.respondToSessionRequest(
                requestId = request.requestId,
                status = "DECLINED"
            )
            _sessionState.value = SessionState.Ended("Declined by user")
            currentRequestId = null
        }
    }

    // Either party ends session
    fun endCurrentSession() {
        val reqId = currentRequestId
        if (reqId != null) {
            managerScope.launch {
                repository.endSession(reqId)
            }
        }
        cleanup()
        _sessionState.value = SessionState.Ended("Session terminated")
    }

    private fun listenToSessionChanges(requestId: String, sessionType: String) {
        sessionListenerJob?.cancel()
        sessionListenerJob = managerScope.launch {
            repository.observeSessionRequest(requestId).collect { req ->
                if (req == null) return@collect

                when (req.status) {
                    "ACCEPTED" -> {
                        if (_sessionState.value !is SessionState.Connected) {
                            _sessionState.value = SessionState.Connected(
                                requestId = requestId,
                                sessionType = sessionType,
                                isMuted = false,
                                isFrontCamera = true,
                                quality = ConnectionQuality.EXCELLENT
                            )
                        }
                    }
                    "DECLINED" -> {
                        cleanup()
                        _sessionState.value = SessionState.Ended("Request was declined by device")
                    }
                    "ENDED" -> {
                        cleanup()
                        _sessionState.value = SessionState.Ended("Session closed")
                    }
                }
            }
        }

        // Listen to remote ICE candidates
        candidateListenerJob?.cancel()
        val remoteSender = if (isChildSide) "MONITOR" else "CHILD"
        candidateListenerJob = managerScope.launch {
            repository.observeRemoteIceCandidates(requestId, remoteSender).collect { candidates ->
                if (candidates.isNotEmpty()) {
                    Log.d("KafkaWebRTC", "Received ${candidates.size} remote ICE candidates from $remoteSender")
                }
            }
        }
    }

    fun toggleMute() {
        val current = _sessionState.value
        if (current is SessionState.Connected) {
            _sessionState.value = current.copy(isMuted = !current.isMuted)
        }
    }

    fun toggleCamera() {
        val current = _sessionState.value
        if (current is SessionState.Connected) {
            _sessionState.value = current.copy(isFrontCamera = !current.isFrontCamera)
        }
    }

    fun resetToIdle() {
        cleanup()
        _sessionState.value = SessionState.Idle
    }

    private fun cleanup() {
        sessionListenerJob?.cancel()
        candidateListenerJob?.cancel()
        currentRequestId = null
    }

    companion object {
        const val STUN_SERVER = "stun:stun.l.google.com:19302"
    }
}
