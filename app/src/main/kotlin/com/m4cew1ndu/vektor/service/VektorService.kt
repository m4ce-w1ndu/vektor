package com.m4cew1ndu.vektor.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.m4cew1ndu.vektor.MainActivity
import com.m4cew1ndu.vektor.R
import com.m4cew1ndu.vektor.VektorApplication

class VektorService : Service(), LifecycleRegistryOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val controller = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = controller.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var sensorManager: SensorManager? = null

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    // Configuration values (can be customized via SharedPreferences)
    private val dotSizePx = mutableFloatStateOf(18f)
    private val dotColorValue = mutableLongStateOf(0xFF4FD8EB)
    private val dotOpacity = mutableFloatStateOf(0.6f)
    private val sensitivity = mutableFloatStateOf(35f)
    private val sideMarginPx = mutableFloatStateOf(35f)

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        loadSettings()
    }

    // Physics state
    private val accelX = mutableFloatStateOf(0f)
    private val accelY = mutableFloatStateOf(0f)
    private val velocityX = mutableFloatStateOf(0f)
    private val velocityY = mutableFloatStateOf(0f)
    private val offsetX = mutableFloatStateOf(0f)
    private val offsetY = mutableFloatStateOf(0f)

    // High-pass filter variables to simulate linear acceleration from raw accelerometer
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private val alpha = 0.98f

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    gravityX = alpha * gravityX + (1 - alpha) * event.values[0]
                    gravityY = alpha * gravityY + (1 - alpha) * event.values[1]
                    gravityZ = alpha * gravityZ + (1 - alpha) * event.values[2]

                    val linearX = event.values[0] - gravityX
                    val linearY = event.values[1] - gravityY
                    val linearZ = event.values[2] - gravityZ

                    // Unified Longitudinal Logic: Forward Accel -> Dots flow UP (-offsetY)
                    val longitudinalForce = -(linearY - linearZ)
                    
                    if (kotlin.math.abs(longitudinalForce) > 0.05f) {
                        // Adjusted multiplier for balanced vehicle motion sensitivity
                        accelY.floatValue = longitudinalForce * sensitivity.floatValue * 80f
                    } else {
                        accelY.floatValue = 0f
                    }

                    if (kotlin.math.abs(linearX) > 0.05f) {
                        accelX.floatValue = -linearX * sensitivity.floatValue * 8f
                    } else {
                        accelX.floatValue = 0f
                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val rotZ = event.values[2]
                    offsetX.floatValue += rotZ * sensitivity.floatValue * 0.5f
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        controller.performAttach()
        controller.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        isRunning = true
        loadSettings()
        val prefs = getSharedPreferences("vektor_settings", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        setupSensors()
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        loadSettings()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        val prefs = getSharedPreferences("vektor_settings", MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        sensorManager?.unregisterListener(sensorListener)
        windowManager?.let { wm ->
            overlayView?.let { view ->
                wm.removeView(view)
            }
        }
        store.clear()
        isRunning = false
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("vektor_settings", MODE_PRIVATE)
        dotSizePx.floatValue = prefs.getFloat("dot_size", 16f)
        dotOpacity.floatValue = prefs.getFloat("dot_opacity", 0.5f)
        sensitivity.floatValue = prefs.getFloat("sensitivity", 35f)
        sideMarginPx.floatValue = prefs.getFloat("side_margin", 35f)
        dotColorValue.longValue = prefs.getLong("dot_color", 0xFF4FD8EB)
    }

    private fun setupSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager?.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager?.registerListener(sensorListener, gyroscope, SensorManager.SENSOR_DELAY_UI)
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                if (android.os.Build.VERSION.SDK_INT >= 35) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    @Suppress("DEPRECATION")
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@VektorService)
            setViewTreeViewModelStoreOwner(this@VektorService)
            setViewTreeSavedStateRegistryOwner(this@VektorService)
            
            setContent {
                var lastTime by remember { mutableLongStateOf(0L) }
                LaunchedEffect(Unit) {
                    while (true) {
                        withFrameNanos { time ->
                            if (lastTime != 0L) {
                                val dt = (time - lastTime) / 1e9f
                                val friction = if (accelX.floatValue == 0f && accelY.floatValue == 0f) 0.85f else 0.96f
                                velocityX.floatValue = (velocityX.floatValue + accelX.floatValue * dt) * friction
                                velocityY.floatValue = (velocityY.floatValue + accelY.floatValue * dt) * friction
                                offsetX.floatValue += velocityX.floatValue * dt
                                offsetY.floatValue += velocityY.floatValue * dt
                                if (accelX.floatValue == 0f && kotlin.math.abs(velocityX.floatValue) < 0.1f) {
                                    offsetX.floatValue *= 0.95f
                                }
                            }
                            lastTime = time
                        }
                    }
                }

                MotionOverlayCanvas(
                    offsetX = offsetX.floatValue,
                    offsetY = offsetY.floatValue,
                    dotSize = dotSizePx.floatValue,
                    dotOpacity = dotOpacity.floatValue,
                    sideMargin = sideMarginPx.floatValue,
                    colorHex = dotColorValue.longValue
                )
            }
        }
        windowManager?.addView(overlayView, params)
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, VektorApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_description))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 8808
        @Volatile
        var isRunning = false
    }
}

interface LifecycleRegistryOwner : androidx.lifecycle.LifecycleOwner

@Composable
fun MotionOverlayCanvas(
    offsetX: Float,
    offsetY: Float,
    dotSize: Float,
    dotOpacity: Float,
    sideMargin: Float,
    colorHex: Long
) {
    val baseColor = Color(colorHex)
    val finalColor = baseColor.copy(alpha = dotOpacity)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Measurements and tuning for 1:1 reference parity
        val verticalSpacingPx = 140 * density
        val columnSpacingPx = 45 * density
        val sidePaddingPx = sideMargin * density
        val sideZoneWidth = columnSpacingPx * 2
        
        // Horizontal Movement: wrap field shift within the side zone
        val hWrapWidth = sideZoneWidth
        val hShift = (offsetX % hWrapWidth + hWrapWidth) % hWrapWidth

        // Define hardware clipping areas for side tracks
        val leftClip = androidx.compose.ui.geometry.Rect(0f, 0f, sidePaddingPx + sideZoneWidth, height)
        val rightClip = androidx.compose.ui.geometry.Rect(width - (sidePaddingPx + sideZoneWidth), 0f, width, height)

        // Draw Left Side
        clipRect(leftClip.left, leftClip.top, leftClip.right, leftClip.bottom) {
            for (track in 0 until 2) {
                val baseX = sidePaddingPx + (track * columnSpacingPx)
                // Wrap horizontal position per track within side zone
                val wrappedX = ((baseX + hShift - sidePaddingPx) % hWrapWidth + hWrapWidth) % hWrapWidth + sidePaddingPx
                
                // Vertical Staggering (Checkerboard pattern)
                val stagger = if (track % 2 != 0) verticalSpacingPx / 2f else 0f
                
                renderContinuousTrack(wrappedX, offsetY, verticalSpacingPx, height, finalColor, dotSize, stagger, leftClip)
                renderContinuousTrack(wrappedX - hWrapWidth, offsetY, verticalSpacingPx, height, finalColor, dotSize, stagger, leftClip)
            }
        }

        // Draw Right Side (Perfect Symmetry)
        clipRect(rightClip.left, rightClip.top, rightClip.right, rightClip.bottom) {
            val rightInnerEdge = width - sidePaddingPx - sideZoneWidth
            for (track in 0 until 2) {
                // Mirror positions from the right edge
                val baseX = (width - sidePaddingPx) - (track * columnSpacingPx)
                // Wrap horizontal position per track within side zone
                val wrappedX = ((baseX + hShift - rightInnerEdge) % hWrapWidth + hWrapWidth) % hWrapWidth + rightInnerEdge
                
                val stagger = if (track % 2 != 0) verticalSpacingPx / 2f else 0f
                
                renderContinuousTrack(wrappedX, offsetY, verticalSpacingPx, height, finalColor, dotSize, stagger, rightClip)
                renderContinuousTrack(wrappedX - hWrapWidth, offsetY, verticalSpacingPx, height, finalColor, dotSize, stagger, rightClip)
                renderContinuousTrack(wrappedX + hWrapWidth, offsetY, verticalSpacingPx, height, finalColor, dotSize, stagger, rightClip)
            }
        }
    }
}

/**
 * Renders a vertical line of dots with 2D alpha fading for smooth appearance/disappearance
 */
private fun DrawScope.renderContinuousTrack(
    baseX: Float,
    yOffset: Float,
    spacingPx: Float,
    height: Float,
    color: Color,
    dotSize: Float,
    stagger: Float,
    clipRect: androidx.compose.ui.geometry.Rect
) {
    val localScrollY = (yOffset % spacingPx + spacingPx) % spacingPx
    
    var y = -spacingPx
    while (y < height + spacingPx) {
        val drawY = y + localScrollY + stagger
        
        // Appearance/Disappearance Fading (2D)
        // Adjust fade margins based on dot size to ensure animation is visible for larger dots
        val vMargin = 150f + (dotSize * 1.5f)
        val hMargin = 25f + (dotSize * 0.5f)
        
        val vAlpha = when {
            drawY < vMargin -> (drawY / vMargin).coerceIn(0f, 1f)
            drawY > height - vMargin -> ((height - drawY) / vMargin).coerceIn(0f, 1f)
            else -> 1f
        }
        
        val hDistFromLeft = baseX - clipRect.left
        val hDistFromRight = clipRect.right - baseX
        val hAlpha = (kotlin.math.min(hDistFromLeft, hDistFromRight) / hMargin).coerceIn(0f, 1f)
        
        val totalAlpha = color.alpha * vAlpha * hAlpha
        if (totalAlpha > 0.01f) {
            val drawColor = color.copy(alpha = totalAlpha)
            
            // Professional triple-layer "Shiny" Dot
            // 1. Halo
            drawCircle(
                color = drawColor.copy(alpha = totalAlpha * 0.15f),
                radius = dotSize * 0.75f,
                center = Offset(baseX, drawY)
            )
            // 2. Shiny border
            drawCircle(
                color = drawColor.copy(alpha = totalAlpha * 0.4f),
                radius = dotSize * 0.55f,
                center = Offset(baseX, drawY)
            )
            // 3. Core
            drawCircle(
                color = drawColor,
                radius = dotSize / 2,
                center = Offset(baseX, drawY)
            )
        }
        y += spacingPx
    }
}
