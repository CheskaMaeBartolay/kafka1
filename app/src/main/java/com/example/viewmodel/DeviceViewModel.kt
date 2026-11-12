package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.KafkaRepository
import com.example.model.DeviceInfo
import com.example.model.DevicePair
import com.example.model.SessionRequest
import com.example.service.LocationTrackingService
import com.example.util.DeviceUtils
import com.example.webrtc.WebRtcSessionManager
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceViewModel(
    private val repository: KafkaRepository,
    val sessionManager: WebRtcSessionManager
) : ViewModel() {

    private val auth = Firebase.auth

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _pairedMonitors = MutableStateFlow<List<DevicePair>>(emptyList())
    val pairedMonitors: StateFlow<List<DevicePair>> = _pairedMonitors.asStateFlow()

    private val _isLocationSharingPaused = MutableStateFlow(false)
    val isLocationSharingPaused: StateFlow<Boolean> = _isLocationSharingPaused.asStateFlow()

    private val _incomingSessionRequest = MutableStateFlow<SessionRequest?>(null)
    val incomingSessionRequest: StateFlow<SessionRequest?> = _incomingSessionRequest.asStateFlow()

    private val _pairingInputCode = MutableStateFlow("")
    val pairingInputCode: StateFlow<String> = _pairingInputCode.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var incomingRequestsJob: Job? = null
    private var pairsJob: Job? = null

    fun initDevice(context: Context) {
        val uid = auth.currentUser?.uid ?: return
        val deviceId = DeviceUtils.getOrCreateDeviceId(context)
        val deviceName = DeviceUtils.getCustomDeviceName(context)
        val deviceModel = DeviceUtils.getDeviceModel()

        viewModelScope.launch {
            val initial = DeviceInfo(
                deviceId = deviceId,
                userId = uid,
                name = deviceName,
                model = deviceModel,
                mode = "DEVICE",
                isOnline = true,
                batteryPercentage = 100,
                locationSharingPaused = false
            )
            try {
                repository.registerDevice(initial)
                _deviceInfo.value = initial
            } catch (e: Exception) {
                _errorMessage.value = "Failed to register child device: ${e.message}"
            }
        }

        // Start location tracking service
        LocationTrackingService.start(context, deviceId)

        // Observe own device document
        viewModelScope.launch {
            repository.observeDevice(deviceId).collect { info ->
                if (info != null) {
                    _deviceInfo.value = info
                    _isLocationSharingPaused.value = info.locationSharingPaused
                }
            }
        }

        // Observe paired monitors
        pairsJob?.cancel()
        pairsJob = viewModelScope.launch {
            repository.observeChildDevicePairs(deviceId).collect { pairs ->
                _pairedMonitors.value = pairs
            }
        }

        // Listen for incoming audio/video check-in requests
        incomingRequestsJob?.cancel()
        incomingRequestsJob = viewModelScope.launch {
            repository.observePendingIncomingSessionRequests(deviceId).collect { requests ->
                _incomingSessionRequest.value = requests.firstOrNull()
            }
        }
    }

    fun setPairingInputCode(code: String) {
        _pairingInputCode.value = code.filter { it.isDigit() }.take(6)
    }

    fun submitPairingCode(context: Context, onSuccess: () -> Unit) {
        val code = _pairingInputCode.value.trim()
        if (code.length != 6) {
            _errorMessage.value = "Please enter a valid 6-digit code"
            return
        }

        val deviceId = DeviceUtils.getOrCreateDeviceId(context)
        val deviceName = DeviceUtils.getCustomDeviceName(context)
        _isLoading.value = true

        viewModelScope.launch {
            val result = repository.verifyAndRedeemPairingCode(code, deviceId, deviceName)
            _isLoading.value = false
            result.onSuccess { pair ->
                _statusMessage.value = "Successfully linked to guardian!"
                _pairingInputCode.value = ""
                onSuccess()
            }.onFailure { err ->
                _errorMessage.value = err.message ?: "Failed to redeem code"
            }
        }
    }

    fun toggleLocationSharing(context: Context) {
        val currentDev = _deviceInfo.value ?: return
        val newPaused = !_isLocationSharingPaused.value
        _isLoading.value = true

        viewModelScope.launch {
            try {
                repository.setLocationSharingPaused(currentDev.deviceId, newPaused)
                _isLocationSharingPaused.value = newPaused
                repository.logActivity(
                    currentDev.deviceId,
                    "LOCATION_TOGGLE",
                    if (newPaused) "Location sharing paused by child" else "Location sharing resumed"
                )
            } catch (e: Exception) {
                _errorMessage.value = "Failed to toggle location sharing"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun acceptIncomingRequest(request: SessionRequest, onAccepted: () -> Unit) {
        sessionManager.acceptRequest(request)
        _incomingSessionRequest.value = null
        onAccepted()
    }

    fun declineIncomingRequest(request: SessionRequest) {
        sessionManager.declineRequest(request)
        _incomingSessionRequest.value = null
    }

    fun unlinkMonitor(pairId: String) {
        viewModelScope.launch {
            try {
                repository.unlinkDevicePair(pairId)
                _statusMessage.value = "Device unlinked successfully"
            } catch (e: Exception) {
                _errorMessage.value = "Failed to unlink: ${e.message}"
            }
        }
    }

    fun clearStatus() {
        _statusMessage.value = null
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        incomingRequestsJob?.cancel()
        pairsJob?.cancel()
    }
}
