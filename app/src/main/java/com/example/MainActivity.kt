package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.KafkaRepository
import com.example.ui.screens.ActiveSessionScreen
import com.example.ui.screens.ActivityHistoryScreen
import com.example.ui.screens.AddDeviceScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.DeviceDashboardScreen
import com.example.ui.screens.LiveLocationMapScreen
import com.example.ui.screens.ModeSelectionScreen
import com.example.ui.screens.MonitorDashboardScreen
import com.example.ui.screens.PairingCodeScreen
import com.example.ui.screens.PrivacyScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.KafkaTheme
import com.example.ui.theme.Obsidian
import com.example.viewmodel.AuthUiState
import com.example.viewmodel.AuthViewModel
import com.example.viewmodel.DeviceViewModel
import com.example.viewmodel.MonitorViewModel
import com.example.viewmodel.PairedDeviceDetails
import com.example.webrtc.WebRtcSessionManager
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

enum class KafkaScreen {
    MODE_SELECTION,
    MONITOR_DASHBOARD,
    DEVICE_DASHBOARD,
    ADD_DEVICE,
    PAIRING_CODE_INPUT,
    LIVE_MAP,
    ACTIVE_SESSION,
    ACTIVITY_HISTORY,
    PRIVACY,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            KafkaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Obsidian
                ) {
                    KafkaAppRoot()
                }
            }
        }
    }
}

@Composable
fun KafkaAppRoot() {
    val context = LocalContext.current
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose {
            Firebase.auth.removeAuthStateListener(listener)
        }
    }

    val repository = remember { KafkaRepository(context) }
    val sessionManager = remember { WebRtcSessionManager(context, repository) }
    val authViewModel = remember { AuthViewModel(repository) }

    LaunchedEffect(Unit) {
        authViewModel.attemptSilentSignIn(context)
        authViewModel.initMode(context)
    }

    if (currentUser == null) {
        AuthScreen(authViewModel = authViewModel)
    } else {
        val uid = currentUser!!.uid
        key(uid) {
            MainFeatureFlow(
                authViewModel = authViewModel,
                repository = repository,
                sessionManager = sessionManager
            )
        }
    }
}

@Composable
fun MainFeatureFlow(
    authViewModel: AuthViewModel,
    repository: KafkaRepository,
    sessionManager: WebRtcSessionManager
) {
    val context = LocalContext.current
    val selectedMode by authViewModel.selectedMode.collectAsState()

    val monitorViewModel = remember { MonitorViewModel(repository, sessionManager) }
    val deviceViewModel = remember { DeviceViewModel(repository, sessionManager) }

    var currentScreen by remember {
        mutableStateOf(
            when (selectedMode) {
                "MONITOR" -> KafkaScreen.MONITOR_DASHBOARD
                "DEVICE" -> KafkaScreen.DEVICE_DASHBOARD
                else -> KafkaScreen.MODE_SELECTION
            }
        )
    }

    var selectedMapDevice by remember { mutableStateOf<PairedDeviceDetails?>(null) }

    LaunchedEffect(selectedMode) {
        when (selectedMode) {
            "MONITOR" -> {
                monitorViewModel.initMonitor(context)
                if (currentScreen == KafkaScreen.MODE_SELECTION) {
                    currentScreen = KafkaScreen.MONITOR_DASHBOARD
                }
            }
            "DEVICE" -> {
                deviceViewModel.initDevice(context)
                if (currentScreen == KafkaScreen.MODE_SELECTION) {
                    currentScreen = KafkaScreen.DEVICE_DASHBOARD
                }
            }
            else -> {
                currentScreen = KafkaScreen.MODE_SELECTION
            }
        }
    }

    when (currentScreen) {
        KafkaScreen.MODE_SELECTION -> {
            ModeSelectionScreen(
                authViewModel = authViewModel,
                onModeSelected = { mode ->
                    currentScreen = if (mode == "MONITOR") KafkaScreen.MONITOR_DASHBOARD else KafkaScreen.DEVICE_DASHBOARD
                }
            )
        }

        KafkaScreen.MONITOR_DASHBOARD -> {
            MonitorDashboardScreen(
                monitorViewModel = monitorViewModel,
                onNavigateToAddDevice = { currentScreen = KafkaScreen.ADD_DEVICE },
                onNavigateToMap = { device ->
                    selectedMapDevice = device
                    currentScreen = KafkaScreen.LIVE_MAP
                },
                onNavigateToHistory = {
                    currentScreen = KafkaScreen.ACTIVITY_HISTORY
                },
                onNavigateToSettings = { currentScreen = KafkaScreen.SETTINGS },
                onNavigateToActiveSession = { currentScreen = KafkaScreen.ACTIVE_SESSION }
            )
        }

        KafkaScreen.DEVICE_DASHBOARD -> {
            DeviceDashboardScreen(
                deviceViewModel = deviceViewModel,
                onNavigateToPairingInput = { currentScreen = KafkaScreen.PAIRING_CODE_INPUT },
                onNavigateToPrivacy = { currentScreen = KafkaScreen.PRIVACY },
                onNavigateToSettings = { currentScreen = KafkaScreen.SETTINGS },
                onNavigateToActiveSession = { currentScreen = KafkaScreen.ACTIVE_SESSION }
            )
        }

        KafkaScreen.ADD_DEVICE -> {
            BackHandler { currentScreen = KafkaScreen.MONITOR_DASHBOARD }
            AddDeviceScreen(
                monitorViewModel = monitorViewModel,
                onNavigateBack = { currentScreen = KafkaScreen.MONITOR_DASHBOARD }
            )
        }

        KafkaScreen.PAIRING_CODE_INPUT -> {
            BackHandler { currentScreen = KafkaScreen.DEVICE_DASHBOARD }
            PairingCodeScreen(
                deviceViewModel = deviceViewModel,
                onNavigateBack = { currentScreen = KafkaScreen.DEVICE_DASHBOARD },
                onPairingSuccess = { currentScreen = KafkaScreen.DEVICE_DASHBOARD }
            )
        }

        KafkaScreen.LIVE_MAP -> {
            BackHandler { currentScreen = KafkaScreen.MONITOR_DASHBOARD }
            selectedMapDevice?.let { dev ->
                LiveLocationMapScreen(
                    monitorViewModel = monitorViewModel,
                    deviceDetails = dev,
                    onNavigateBack = { currentScreen = KafkaScreen.MONITOR_DASHBOARD },
                    onNavigateToActiveSession = { currentScreen = KafkaScreen.ACTIVE_SESSION }
                )
            } ?: run {
                currentScreen = KafkaScreen.MONITOR_DASHBOARD
            }
        }

        KafkaScreen.ACTIVE_SESSION -> {
            BackHandler {
                sessionManager.endCurrentSession()
                currentScreen = if (selectedMode == "DEVICE") KafkaScreen.DEVICE_DASHBOARD else KafkaScreen.MONITOR_DASHBOARD
            }
            ActiveSessionScreen(
                sessionManager = sessionManager,
                onSessionEnded = {
                    currentScreen = if (selectedMode == "DEVICE") KafkaScreen.DEVICE_DASHBOARD else KafkaScreen.MONITOR_DASHBOARD
                }
            )
        }

        KafkaScreen.ACTIVITY_HISTORY -> {
            BackHandler { currentScreen = KafkaScreen.MONITOR_DASHBOARD }
            ActivityHistoryScreen(
                monitorViewModel = monitorViewModel,
                onNavigateBack = { currentScreen = KafkaScreen.MONITOR_DASHBOARD }
            )
        }

        KafkaScreen.PRIVACY -> {
            val returnScreen = if (selectedMode == "DEVICE") KafkaScreen.DEVICE_DASHBOARD else KafkaScreen.SETTINGS
            BackHandler { currentScreen = returnScreen }
            PrivacyScreen(
                onNavigateBack = { currentScreen = returnScreen }
            )
        }

        KafkaScreen.SETTINGS -> {
            val returnScreen = if (selectedMode == "DEVICE") KafkaScreen.DEVICE_DASHBOARD else KafkaScreen.MONITOR_DASHBOARD
            BackHandler { currentScreen = returnScreen }
            SettingsScreen(
                authViewModel = authViewModel,
                onNavigateBack = { currentScreen = returnScreen },
                onSwitchMode = { currentScreen = KafkaScreen.MODE_SELECTION },
                onNavigateToPrivacy = { currentScreen = KafkaScreen.PRIVACY }
            )
        }
    }
}
