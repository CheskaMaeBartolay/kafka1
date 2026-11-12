package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.KafkaRepository
import com.example.model.ActivityLog
import com.example.model.DeviceInfo
import com.example.model.DevicePair
import com.example.model.LocationUpdate
import com.example.model.PairingCode
import com.example.util.DeviceUtils
import com.example.webrtc.WebRtcSessionManager
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class PairedDeviceDetails(
    val pair: DevicePair,
    val info: DeviceInfo? = null,
    val latestLocation: LocationUpdate? = null
)

class MonitorViewModel(
    private val repository: KafkaRepository,
    val sessionManager: WebRtcSessionManager
) : ViewModel() {

    private val auth = Firebase.auth

    private val _pairedDevices = MutableStateFlow<List<PairedDeviceDetails>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDeviceDetails>> = _pairedDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<PairedDeviceDetails?>(null)
    val selectedDevice: StateFlow<PairedDeviceDetails?> = _selectedDevice.asStateFlow()

    private val _currentPairingCode = MutableStateFlow<PairingCode?>(null)
    val currentPairingCode: StateFlow<PairingCode?> = _currentPairingCode.asStateFlow()

    private val _pairingCodeSecondsRemaining = MutableStateFlow(0)
    val pairingCodeSecondsRemaining: StateFlow<Int> = _pairingCodeSecondsRemaining.asStateFlow()

    private val _activityLogs = MutableStateFlow<List<ActivityLog>>(emptyList())
    val activityLogs: StateFlow<List<ActivityLog>> = _activityLogs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var countdownJob: Job? = null
    private var pairsJob: Job? = null
    private val deviceDetailsJobs = mutableMapOf<String, Job>()

    fun initMonitor(context: Context) {
        val uid = auth.currentUser?.uid ?: return
        val deviceId = DeviceUtils.getOrCreateDeviceId(context)
        val deviceName = DeviceUtils.getCustomDeviceName(context)
        val deviceModel = DeviceUtils.getDeviceModel()

        // Register monitor device
        viewModelScope.launch {
            try {
                repository.registerDevice(
                    DeviceInfo(
                        deviceId = deviceId,
                        userId = uid,
                        name = deviceName,
                        model = deviceModel,
                        mode = "MONITOR",
                        isOnline = true,
                        batteryPercentage = 100
                    )
                )
            } catch (e: Exception) {
                _errorMessage.value = "Failed to register monitor device"
            }
        }

        observePairedDevices(uid)
    }

    private fun observePairedDevices(userId: String) {
        pairsJob?.cancel()
        pairsJob = viewModelScope.launch {
            repository.observeMonitorDevicePairs(userId).collect { pairs ->
                val currentList = _pairedDevices.value.toMutableList()
                val updatedList = mutableListOf<PairedDeviceDetails>()

                for (pair in pairs) {
                    val existing = currentList.find { it.pair.pairId == pair.pairId }
                    val details = existing ?: PairedDeviceDetails(pair = pair)
                    updatedList.add(details)
                    listenToDeviceUpdates(pair.childDeviceId, pair.pairId)
                }
                _pairedDevices.value = updatedList

                if (_selectedDevice.value != null) {
                    _selectedDevice.value = updatedList.find { it.pair.pairId == _selectedDevice.value?.pair?.pairId }
                }
            }
        }
    }

    private fun listenToDeviceUpdates(childDeviceId: String, pairId: String) {
        if (deviceDetailsJobs.containsKey(childDeviceId)) return

        val job = viewModelScope.launch {
            launch {
                repository.observeDevice(childDeviceId).collect { info ->
                    updateDeviceDetails(childDeviceId) { it.copy(info = info) }
                }
            }
            launch {
                repository.observeLocationUpdate(childDeviceId).collect { location ->
                    updateDeviceDetails(childDeviceId) { it.copy(latestLocation = location) }
                }
            }
        }
        deviceDetailsJobs[childDeviceId] = job
    }

    private fun updateDeviceDetails(childDeviceId: String, transform: (PairedDeviceDetails) -> PairedDeviceDetails) {
        val current = _pairedDevices.value.map { item ->
            if (item.pair.childDeviceId == childDeviceId) transform(item) else item
        }
        _pairedDevices.value = current
        if (_selectedDevice.value?.pair?.childDeviceId == childDeviceId) {
            _selectedDevice.value = current.find { it.pair.childDeviceId == childDeviceId }
        }
    }

    fun selectDevice(device: PairedDeviceDetails) {
        _selectedDevice.value = device
        loadActivityLogs(device.pair.childDeviceId)
    }

    fun clearSelectedDevice() {
        _selectedDevice.value = null
    }

    fun loadActivityLogs(childDeviceId: String) {
        viewModelScope.launch {
            repository.observeActivityLogs(childDeviceId).collect { logs ->
                _activityLogs.value = logs
            }
        }
    }

    fun generatePairingCode(context: Context) {
        val monitorDeviceId = DeviceUtils.getOrCreateDeviceId(context)
        val monitorName = DeviceUtils.getCustomDeviceName(context)
        _isLoading.value = true

        viewModelScope.launch {
            val result = repository.generatePairingCode(monitorDeviceId, monitorName)
            _isLoading.value = false
            result.onSuccess { code ->
                _currentPairingCode.value = code
                startCodeCountdown(300) // 5 minutes
            }.onFailure { err ->
                _errorMessage.value = err.message ?: "Failed to generate pairing code"
            }
        }
    }

    private fun startCodeCountdown(seconds: Int) {
        countdownJob?.cancel()
        _pairingCodeSecondsRemaining.value = seconds
        countdownJob = viewModelScope.launch {
            for (i in seconds downTo 0) {
                _pairingCodeSecondsRemaining.value = i
                delay(1000)
            }
            _currentPairingCode.value = null
        }
    }

    fun requestSession(sessionType: String, context: Context, onStarted: () -> Unit) {
        val dev = _selectedDevice.value ?: return
        val monitorDeviceId = DeviceUtils.getOrCreateDeviceId(context)
        _isLoading.value = true

        sessionManager.initiateRequest(
            monitorDeviceId = monitorDeviceId,
            childDeviceId = dev.pair.childDeviceId,
            childUserId = dev.pair.childUserId,
            sessionType = sessionType
        ) { result ->
            _isLoading.value = false
            result.onSuccess {
                onStarted()
            }.onFailure { err ->
                _errorMessage.value = err.message ?: "Failed to request session"
            }
        }
    }

    fun unlinkDevice(pairId: String) {
        viewModelScope.launch {
            try {
                repository.unlinkDevicePair(pairId)
                _selectedDevice.value = null
            } catch (e: Exception) {
                _errorMessage.value = "Failed to unlink device: ${e.message}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        pairsJob?.cancel()
        deviceDetailsJobs.values.forEach { it.cancel() }
    }
}
