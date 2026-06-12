package com.vektor.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vektor.app.service.VektorService
import com.vektor.app.ui.theme.VektorTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val hasOverlayPermission = mutableStateOf(false)
    private val isServiceRunningState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VektorTheme {
                MainScreen(
                    hasPermission = hasOverlayPermission.value,
                    isServiceRunning = isServiceRunningState.value,
                    onRequestPermission = { requestOverlayPermission() },
                    onToggleService = { toggleService() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasOverlayPermission.value = Settings.canDrawOverlays(this)
        isServiceRunningState.value = VektorService.isRunning
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun toggleService() {
        val intent = Intent(this, VektorService::class.java)
        if (VektorService.isRunning) {
            stopService(intent)
            isServiceRunningState.value = false
        } else {
            if (Settings.canDrawOverlays(this)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                isServiceRunningState.value = true
            } else {
                requestOverlayPermission()
            }
        }
    }
}

@Composable
fun MainScreen(
    hasPermission: Boolean,
    isServiceRunning: Boolean,
    onRequestPermission: () -> Unit,
    onToggleService: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vektor_settings", Context.MODE_PRIVATE) }

    // Settings state backing sliders
    var dotSize by remember { mutableFloatStateOf(prefs.getFloat("dot_size", 16f)) }
    var dotOpacity by remember { mutableFloatStateOf(prefs.getFloat("dot_opacity", 0.5f)) }
    var sensitivity by remember { mutableFloatStateOf(prefs.getFloat("sensitivity", 20f)) }
    var dotSpacing by remember { mutableIntStateOf(prefs.getInt("dot_spacing", 60)) }
    var dotColorHex by remember { mutableLongStateOf(prefs.getLong("dot_color", 0xFF4FD8EB)) }

    val colorsList = listOf(
        0xFF4FD8EB, // Electric Cyan
        0xFF00E676, // Neon Mint
        0xFFFF8A80, // Coral Pink
        0xFFD1C4E9, // Lavender Soft
        0xFFFFFFFF, // Off-White
        0xFFFFEB3B  // Bright Yellow
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24dp),
            verticalArrangement = Arrangement.spacedBy(20dp)
        ) {
            // Header Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12dp)
            ) {
                Text(
                    text = "Vektor",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = (-1).sp
                )
                Text(
                    text = "Anti-Nausea Motion Cues",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }

            // Status Card with dynamic color change animation
            val statusCardColor by animateColorAsState(
                targetValue = if (isServiceRunning) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                animationSpec = spring(dampingRatio = 0.8f)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = statusCardColor),
                shape = RoundedCornerShape(24dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isServiceRunning) "Cues Active" else "Cues Paused",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isServiceRunning) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(
                            text = if (isServiceRunning) "Drifting dots are aligning with movement." else "Service is idle. Turn on to start cues.",
                            fontSize = 13.sp,
                            color = if (isServiceRunning) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            }
                        )
                    }
                    Switch(
                        checked = isServiceRunning,
                        onCheckedChange = { onToggleService() },
                        enabled = hasPermission
                    )
                }
            }

            // Permission Warning Card
            AnimatedVisibility(visible = !hasPermission) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.5dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(24dp)
                        )
                        .clickable { onRequestPermission() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(24dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20dp),
                        verticalArrangement = Arrangement.spacedBy(8dp)
                    ) {
                        Text(
                            text = "Permission Required",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Vektor needs the \"Display over other apps\" permission to show dots while you use other apps. Tap here to grant this permission in settings.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Customization Options Section (only interactable if permission is granted)
            Text(
                text = "Preferences",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4dp, top = 8dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(24dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20dp),
                    verticalArrangement = Arrangement.spacedBy(20dp)
                ) {
                    // Dot Color Picker
                    Column(verticalArrangement = Arrangement.spacedBy(8dp)) {
                        Text(
                            text = "Dot Color",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            colorsList.forEach { colorHex ->
                                val isSelected = dotColorHex == colorHex
                                Box(
                                    modifier = Modifier
                                        .size(36dp)
                                        .clip(CircleShape)
                                        .background(Color(colorHex))
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            dotColorHex = colorHex
                                            prefs.edit().putLong("dot_color", colorHex).apply()
                                        }
                                )
                            }
                        }
                    }

                    // Dot Size Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Dot Size",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${dotSize.roundToInt()} dp",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Slider(
                            value = dotSize,
                            onValueChange = { newValue ->
                                dotSize = newValue
                                prefs.edit().putFloat("dot_size", newValue).apply()
                            },
                            valueRange = 10f..30f
                        )
                    }

                    // Dot Opacity Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Opacity",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${(dotOpacity * 100).roundToInt()}%",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Slider(
                            value = dotOpacity,
                            onValueChange = { newValue ->
                                dotOpacity = newValue
                                prefs.edit().putFloat("dot_opacity", newValue).apply()
                            },
                            valueRange = 0.1f..1.0f
                        )
                    }

                    // Sensitivity Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Motion Sensitivity",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${sensitivity.roundToInt()}x",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Slider(
                            value = sensitivity,
                            onValueChange = { newValue ->
                                sensitivity = newValue
                                prefs.edit().putFloat("sensitivity", newValue).apply()
                            },
                            valueRange = 5f..40f
                        )
                    }

                    // Dot Spacing/Density Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Dot Spacing",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${dotSpacing} dp",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Slider(
                            value = dotSpacing.toFloat(),
                            onValueChange = { newValue ->
                                dotSpacing = newValue.roundToInt()
                                prefs.edit().putInt("dot_spacing", newValue.roundToInt()).apply()
                            },
                            valueRange = 40f..100f
                        )
                    }
                }
            }

            // Footer Information
            Text(
                text = "Vektor aligns visual motion clues with physical movement, reducing the sensory conflict that triggers vehicle motion sickness. Made for everyone, completely FOSS.",
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12dp)
            )
        }
    }
}
