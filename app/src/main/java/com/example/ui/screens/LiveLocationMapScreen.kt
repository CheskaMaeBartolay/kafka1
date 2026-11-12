package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.BatteryBadge
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusBadge
import com.example.ui.components.TelemetryChip
import com.example.ui.theme.CardBorder
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CharcoalSurfaceVariant
import com.example.ui.theme.CyberBlue
import com.example.ui.theme.CyberBlueDeep
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.Obsidian
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.MonitorViewModel
import com.example.viewmodel.PairedDeviceDetails
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun LiveLocationMapScreen(
    monitorViewModel: MonitorViewModel,
    deviceDetails: PairedDeviceDetails,
    onNavigateBack: () -> Unit,
    onNavigateToActiveSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val location = deviceDetails.latestLocation
    val info = deviceDetails.info

    val latitude = location?.latitude ?: 37.7749
    val longitude = location?.longitude ?: -122.4194
    val accuracy = location?.accuracy ?: 15.0
    val speed = location?.speed ?: 0.0

    val deviceLatLng = remember(latitude, longitude) { LatLng(latitude, longitude) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(deviceLatLng, 15.5f)
    }

    val timeFormatter = remember { SimpleDateFormat("h:mm:ss a", Locale.getDefault()) }
    val lastUpdateStr = location?.timestamp?.toDate()?.let { timeFormatter.format(it) } ?: "Waiting for GPS"

    // Infinite pulsing radar animation for accuracy ring
    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Radius"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Alpha"
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Obsidian
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Interactive Google Maps
            GoogleMap(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("google_map_view"),
                cameraPositionState = cameraPositionState,
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    compassEnabled = true
                ),
                properties = MapProperties(
                    isBuildingEnabled = true
                )
            ) {
                Marker(
                    state = MarkerState(position = deviceLatLng),
                    title = deviceDetails.pair.childDeviceName.ifEmpty { "Child Device" },
                    snippet = "Accuracy: ${"%.1f".format(accuracy)}m"
                )
            }

            // Top Overlay Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CharcoalSurface.copy(alpha = 0.9f))
                            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(CharcoalSurface.copy(alpha = 0.9f))
                            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge(isOnline = info?.isOnline ?: true)
                            Spacer(modifier = Modifier.width(8.dp))
                            BatteryBadge(
                                percentage = info?.batteryPercentage ?: 100,
                                isCharging = info?.isCharging ?: false
                            )
                        }
                    }
                }
            }

            // Bottom Telemetry & Quick Action Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(20.dp)
            ) {
                GlassCard {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = deviceDetails.pair.childDeviceName.ifEmpty { "Child Device" },
                                    color = TextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Updated: $lastUpdateStr",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CharcoalSurfaceVariant)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${"%.1f".format(accuracy)}m ACCURACY",
                                    color = CyberBlue,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // GPS Coordinates Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TelemetryChip(
                                icon = Icons.Default.MyLocation,
                                label = "Latitude",
                                value = "%.6f".format(latitude),
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryChip(
                                icon = Icons.Default.Navigation,
                                label = "Longitude",
                                value = "%.6f".format(longitude),
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryChip(
                                icon = Icons.Default.Speed,
                                label = "Speed",
                                value = "${"%.1f".format(speed)} m/s",
                                modifier = Modifier.weight(0.9f)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Communication Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    monitorViewModel.requestSession("CAMERA", context) {
                                        onNavigateToActiveSession()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("request_camera_from_map"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberBlue,
                                    contentColor = Color(0xFF00283C)
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Check Video", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            Button(
                                onClick = {
                                    monitorViewModel.requestSession("MIC", context) {
                                        onNavigateToActiveSession()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("request_mic_from_map"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CharcoalSurfaceVariant,
                                    contentColor = CyberBlue
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Check Audio", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FallbackRadarMap(
    latitude: Double,
    longitude: Double,
    pulseRadius: Float,
    pulseAlpha: Float
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.width.coerceAtMost(size.height) * 0.45f

            // Radar grid circles
            for (i in 1..4) {
                drawCircle(
                    color = CardBorder.copy(alpha = 0.4f),
                    radius = maxR * (i / 4f),
                    center = center,
                    style = Stroke(width = 1.5f)
                )
            }

            // Cross hairs
            drawLine(
                color = CardBorder.copy(alpha = 0.4f),
                start = Offset(center.x, center.y - maxR),
                end = Offset(center.x, center.y + maxR),
                strokeWidth = 1.5f
            )
            drawLine(
                color = CardBorder.copy(alpha = 0.4f),
                start = Offset(center.x - maxR, center.y),
                end = Offset(center.x + maxR, center.y),
                strokeWidth = 1.5f
            )

            // Animated accuracy pulse
            drawCircle(
                color = CyberBlue.copy(alpha = pulseAlpha * 0.4f),
                radius = maxR * pulseRadius,
                center = center
            )
            drawCircle(
                color = CyberBlue.copy(alpha = pulseAlpha),
                radius = maxR * pulseRadius,
                center = center,
                style = Stroke(width = 2f)
            )

            // Center location pin
            drawCircle(
                color = CyberBlue,
                radius = 12f,
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 5f,
                center = center
            )
        }
    }
}
