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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
    private val dotCount = mutableIntStateOf(20)

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        loadSettings()
    }

    // Physics state
    private val offsetX = mutableFloatStateOf(0f)
    private val offsetY = mutableFloatStateOf(0f)

    // High-pass filter variables to simulate linear acceleration from raw accelerometer
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private val alpha = 0.98f // Increased for much better gravity stability

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    // Extract gravity with a high-quality low-pass filter
                    gravityX = alpha * gravityX + (1 - alpha) * event.values[0]
                    gravityY = alpha * gravityY + (1 - alpha) * event.values[1]
                    gravityZ = alpha * gravityZ + (1 - alpha) * event.values[2]

                    // Remove gravity to get pure linear acceleration
                    val linearX = event.values[0] - gravityX
                    val linearY = event.values[1] - gravityY
                    val linearZ = event.values[2] - gravityZ

                    // Use assignment instead of accumulation to stop infinite drift.
                    // This mirrors the "original working version" but with gravity removed.
                    offsetX.floatValue = -linearX * sensitivity.floatValue
                    
                    // Unified Longitudinal Logic: 
                    // Accelerating Forward -> Inertia is Backward. 
                    // Backward inertia is felt as +Z (upright) or -Y (flat).
                    // We want dots to move UP (negative offsetY) in this case.
                    val longitudinalInertia = linearZ - linearY
                    offsetY.floatValue = -longitudinalInertia * sensitivity.floatValue
                }
                Sensor.TYPE_GYROSCOPE -> {
                    // Gyroscope can be integrated here for rotational drift compensation
                    val rotZ = event.values[2] // rotation around Z axis
                    // Modify offsetX based on rotational velocity for centripetal correction
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
        
        // Reload settings if service is toggled/restarted
        loadSettings()

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        
        // Unregister settings listener
        val prefs = getSharedPreferences("vektor_settings", MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        
        // Unregister sensors
        sensorManager?.unregisterListener(sensorListener)

        // Remove overlay window
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
        sensitivity.floatValue = prefs.getFloat("sensitivity", 20f)
        dotCount.intValue = prefs.getInt("dot_count", 20)
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
            // Supply necessary owners to ComposeView
            setViewTreeLifecycleOwner(this@VektorService)
            setViewTreeViewModelStoreOwner(this@VektorService)
            setViewTreeSavedStateRegistryOwner(this@VektorService)
            
            setContent {
                MotionOverlayCanvas(
                    offsetX = offsetX.floatValue,
                    offsetY = offsetY.floatValue,
                    dotSize = dotSizePx.floatValue,
                    dotOpacity = dotOpacity.floatValue,
                    dotCount = dotCount.intValue,
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
    dotCount: Int,
    colorHex: Long
) {
    val baseColor = Color(colorHex)
    val finalColor = baseColor.copy(alpha = dotOpacity)

    // Smooth out the motion using a spring animation
    val animatedX by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.8f),
        label = "offsetX"
    )
    val animatedY by animateFloatAsState(
        targetValue = offsetY,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.8f),
        label = "offsetY"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Calculate spacing based on dotCount (using dotCount as "dots per dimension" roughly)
        // We'll aim for approx dotCount total dots on screen. 
        // totalDots = (width/spacing) * (height/spacing)
        // spacing = sqrt(width * height / totalDots)
        val totalArea = width * height
        val spacingPx = kotlin.math.sqrt(totalArea / dotCount.coerceAtLeast(1))
        
        // Wrap offsets to stay within [0, spacingPx)
        val gridOffsetX = animatedX % spacingPx
        val gridOffsetY = animatedY % spacingPx

        val exclusionRadiusPx = 180 * density
        val exclusionRadiusSq = exclusionRadiusPx * exclusionRadiusPx

        var x = -spacingPx
        while (x < width + spacingPx) {
            var y = -spacingPx
            while (y < height + spacingPx) {
                val drawX = x + gridOffsetX
                val drawY = y + gridOffsetY
                
                // Only render dots outside the central screen area
                val dx = drawX - (width / 2)
                val dy = drawY - (height / 2)
                val distFromCenterSq = dx * dx + dy * dy
                
                if (distFromCenterSq > exclusionRadiusSq) {
                    drawCircle(
                        color = finalColor,
                        radius = dotSize / 2,
                        center = Offset(drawX, drawY)
                    )
                }
                y += spacingPx
            }
            x += spacingPx
        }
    }
}
