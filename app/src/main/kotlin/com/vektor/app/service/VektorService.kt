package com.vektor.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
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
import com.vektor.app.MainActivity
import com.vektor.app.R
import com.vektor.app.VektorApplication
import kotlin.math.sin

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
    private val dotSizePx = mutableStateOf(18f)
    private val dotColorValue = mutableStateOf(0xFF4FD8EB)
    private val dotOpacity = mutableStateOf(0.6f)
    private val sensitivity = mutableStateOf(15f)
    private val dotSpacingDp = mutableStateOf(60)

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        loadSettings()
    }

    // Physics state
    private val offsetX = mutableStateOf(0f)
    private val offsetY = mutableStateOf(0f)

    // Low pass filter constants
    private val filterFactor = 0.15f // lower = smoother but slower, higher = faster
    private var filteredAccX = 0f
    private var filteredAccY = 0f

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    // event.values[0] is X (left-right force)
                    // event.values[1] is Y (up-down force)
                    // Apply low-pass filter to smooth out minor vibrations (road noise)
                    val rawX = event.values[0]
                    val rawY = event.values[1]

                    filteredAccX = filteredAccX + filterFactor * (rawX - filteredAccX)
                    filteredAccY = filteredAccY + filterFactor * (rawY - filteredAccY)

                    // Update UI offset based on acceleration force
                    // Oppose vehicle movement: if accelerated right (positive rawX), dots drift left (negative offsetX)
                    offsetX.value = -filteredAccX * sensitivity.value
                    offsetY.value = filteredAccY * sensitivity.value // Car forward (positive rawY) -> dots move down (positive offsetY)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    // Gyroscope can be integrated here for rotational drift compensation
                    val rotZ = event.values[2] // rotation around Z axis
                    // Modify offsetX based on rotational velocity for centripetal correction
                    offsetX.value += rotZ * sensitivity.value * 0.5f
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
        val prefs = getSharedPreferences("vektor_settings", Context.MODE_PRIVATE)
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
        val prefs = getSharedPreferences("vektor_settings", Context.MODE_PRIVATE)
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
        val prefs = getSharedPreferences("vektor_settings", Context.MODE_PRIVATE)
        dotSizePx.value = prefs.getFloat("dot_size", 16f)
        dotOpacity.value = prefs.getFloat("dot_opacity", 0.5f)
        sensitivity.value = prefs.getFloat("sensitivity", 20f)
        dotSpacingDp.value = prefs.getInt("dot_spacing", 60)
        dotColorValue.value = prefs.getLong("dot_color", 0xFF4FD8EB)
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Register with SENSOR_DELAY_UI (approx 60Hz) to save battery while remaining responsive
        sensorManager?.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager?.registerListener(sensorListener, gyroscope, SensorManager.SENSOR_DELAY_UI)
    }

    private fun showOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        overlayView = ComposeView(this).apply {
            // Supply necessary owners to ComposeView
            setViewTreeLifecycleOwner(this@VektorService)
            setViewTreeViewModelStoreOwner(this@VektorService)
            setViewTreeSavedStateRegistryOwner(this@VektorService)
            
            setContent {
                MotionOverlayCanvas(
                    offsetX = offsetX.value,
                    offsetY = offsetY.value,
                    dotSize = dotSizePx.value,
                    dotOpacity = dotOpacity.value,
                    dotSpacing = dotSpacingDp.value,
                    colorHex = dotColorValue.value
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
    dotSpacing: Int,
    colorHex: Long
) {
    val baseColor = Color(colorHex)
    val finalColor = baseColor.copy(alpha = dotOpacity)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        val spacingPx = dotSpacing * density
        
        // Wrap offsets to stay within [0, spacingPx)
        val gridOffsetX = offsetX % spacingPx
        val gridOffsetY = offsetY % spacingPx

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
