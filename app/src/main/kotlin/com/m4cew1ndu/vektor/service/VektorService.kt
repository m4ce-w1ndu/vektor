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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
    private val columnCount = mutableIntStateOf(2)

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

                    // iOS Physics: Acceleration translates to velocity (flow speed)
                    // Sideways inertia (-linearX)
                    accelX.floatValue = -linearX * sensitivity.floatValue * 5f
                    
                    // Unified Longitudinal Logic:
                    // Accelerating Forward -> Dots flow UP (-offsetY).
                    // In flat mode (Y is forward), Forward Accel = +Y, we need negative force.
                    // In upright mode (Z is inward), Forward Accel = -Z, we need negative force.
                    // Unified Longitudinal Logic:
                    // We want: Forward Accel -> Dots move UP (-offsetY)
                    // Upright (mount): Forward Accel is -Z linear.
                    // Flat: Forward Accel is +Y linear.
                    // (linearY - linearZ) produces positive for forward accel.
                    // Let's use negative of that to get negative velocity for UP flow.
                    val longitudinalForce = -(linearY - linearZ)
                    
                    // Apply a deadzone to ignore sensor noise when sitting on a table
                    if (kotlin.math.abs(longitudinalForce) > 0.05f) {
                        accelY.floatValue = longitudinalForce * sensitivity.floatValue * 40f
                    } else {
                        accelY.floatValue = 0f
                    }

                    // Sideways deadzone
                    if (kotlin.math.abs(linearX) > 0.05f) {
                        accelX.floatValue = -linearX * sensitivity.floatValue * 8f
                    } else {
                        accelX.floatValue = 0f
                    }
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
        sensitivity.floatValue = prefs.getFloat("sensitivity", 35f)
        columnCount.intValue = prefs.getInt("column_count", 2)
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
                var lastTime by remember { mutableLongStateOf(0L) }
                
                LaunchedEffect(Unit) {
                    while (true) {
                        withFrameNanos { time ->
                            if (lastTime != 0L) {
                                val dt = (time - lastTime) / 1e9f
                                
                                // Momentum-based flow: Acceleration adds to velocity, velocity decays
                                // Deceleration is aggressive when no acceleration is present to prevent drift
                                val friction = if (accelX.floatValue == 0f && accelY.floatValue == 0f) 0.85f else 0.96f
                                
                                velocityX.floatValue = (velocityX.floatValue + accelX.floatValue * dt) * friction
                                velocityY.floatValue = (velocityY.floatValue + accelY.floatValue * dt) * friction

                                // Continuously scroll the dots based on current velocity
                                offsetX.floatValue += velocityX.floatValue * dt
                                offsetY.floatValue += velocityY.floatValue * dt
                                
                                // Reset horizontal position gently if it drifted too far and we are stationary
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
                    columnCount = columnCount.intValue,
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
    columnCount: Int,
    colorHex: Long
) {
    val baseColor = Color(colorHex)
    val finalColor = baseColor.copy(alpha = dotOpacity)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Reference-based tuning:
        // Sparse vertical spacing, clean peripheral tracks.
        val verticalSpacingPx = 140 * density
        val columnSpacingPx = 50 * density
        val sidePaddingPx = 35 * density
        
        // Wrap vertical offset for seamless infinite vertical scrolling
        val gridOffsetY = (offsetY % verticalSpacingPx + verticalSpacingPx) % verticalSpacingPx

        // Lateral shift from turning
        val lateralShift = offsetX

        // Side rendering with explicit column placement
        for (col in 0 until columnCount) {
            val colOffset = col * columnSpacingPx
            
            // Left Side Track
            val leftX = sidePaddingPx + colOffset + lateralShift
            renderGlowDotColumn(leftX, gridOffsetY, verticalSpacingPx, height, finalColor, dotSize)
            
            // Right Side Track (mirrored)
            val rightX = width - sidePaddingPx - colOffset + lateralShift
            renderGlowDotColumn(rightX, gridOffsetY, verticalSpacingPx, height, finalColor, dotSize)
        }
    }
}

private fun DrawScope.renderGlowDotColumn(
    baseX: Float,
    gridOffsetY: Float,
    spacingPx: Float,
    height: Float,
    color: Color,
    dotSize: Float
) {
    var y = -spacingPx
    while (y < height + spacingPx) {
        val drawY = y + gridOffsetY
        
        // Visual Style from reference: Small dot with a very subtle outer glow
        // Outer glow
        drawCircle(
            color = color.copy(alpha = color.alpha * 0.2f),
            radius = dotSize * 0.7f,
            center = Offset(baseX, drawY)
        )
        // Core dot
        drawCircle(
            color = color,
            radius = dotSize / 2,
            center = Offset(baseX, drawY)
        )
        y += spacingPx
    }
}
