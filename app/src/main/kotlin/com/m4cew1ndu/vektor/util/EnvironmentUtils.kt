package com.m4cew1ndu.vektor.util

import android.os.Build
import java.io.File

object EnvironmentUtils {
    /**
     * Detects if the app is running in an unsupported environment such as an emulator,
     * Waydroid, or BlueStacks. These environments often lack the necessary physical
     * sensors (accelerometer/gyroscope) to make the app functional.
     */
    fun isUnsupportedEnvironment(): Boolean {
        return isWaydroid() || isBlueStacks() || isGenericEmulator()
    }

    private fun isWaydroid(): Boolean {
        return try {
            Build.PRODUCT.contains("waydroid", ignoreCase = true) ||
            Build.DEVICE.contains("waydroid", ignoreCase = true) ||
            File("/dev/waydroid-wayland").exists()
        } catch (e: Exception) {
            false
        }
    }

    private fun isBlueStacks(): Boolean {
        return try {
            File("/sdcard/windows/BstSharedFolder").exists() ||
            Build.MODEL.contains("BlueStacks", ignoreCase = true) ||
            Build.MANUFACTURER.contains("BlueStacks", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    private fun isGenericEmulator(): Boolean {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                Build.PRODUCT.contains("sdk_google") ||
                Build.PRODUCT.contains("google_sdk") ||
                Build.PRODUCT.contains("sdk") ||
                Build.PRODUCT.contains("sdk_x86") ||
                Build.PRODUCT.contains("vbox86p") ||
                Build.PRODUCT.contains("emulator") ||
                Build.PRODUCT.contains("simulator")
    }
}
