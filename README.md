# Vektor 📐

Vektor is a Free and Open Source Software (FOSS) Android application designed to combat motion sickness (kinetosis) in moving vehicles. Inspired by the "Vehicle Motion Cues" feature in iOS, Vektor overlays subtle, animated dots on the screen that react dynamically to the physical motion of the vehicle. By aligning visual cues with the inner ear's balance signals, Vektor helps prevent sensory conflict and reduces nausea without needing medication.

---

## 🚀 How it Works
Motion sickness occurs when there is a sensory mismatch: your eyes register a stationary environment (like reading a screen), while your inner ear and body sense the movement of the vehicle. 

Vektor solves this by:
1. **Detecting Motion:** Tapping into your device's high-frequency sensors (`TYPE_ACCELEROMETER` and `TYPE_GYROSCOPE`) via the Android `SensorManager`.
2. **Translating Physics:** Converting raw motion vectors (acceleration, deceleration, centripetal force, and rotation) into visual translations.
3. **Displaying Cues:** Rendering a system-wide overlay of small, non-intrusive dots that flow in opposition to the vehicle's movement (e.g., drifting left when the vehicle turns right, and moving backward when the vehicle accelerates), acting as an artificial stable horizon.

---

## 🎨 Tech Stack & Design
Vektor is built from the ground up for modern Android, focusing on light footprint, high performance, and visual elegance.

- **Language:** Kotlin
- **UI Toolkit:** Jetpack Compose & Jetpack Compose Overlay Canvas
- **Design System:** Material 3 Expressive
  - **Dynamic Theme (Material You):** Seamlessly matches your device's wallpaper and system color scheme.
  - **Fluid Motion:** Leverages Jetpack Compose spring animations for bouncy, organic transitions when toggling services.
  - **Minimalist Footprint:** A single, clean settings dashboard to toggle the service, adjust dot size, color opacity, and motion sensitivity.
- **Core Android Frameworks:**
  - `SensorManager` & `SensorEventListener` for low-latency motion detection.
  - System Overlay Window (`WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`) or an Accessibility Service to render dots system-wide over any app.

---

## 🛡️ Privacy & Philosophy (FOSS)
Vektor is developed under strict user-first principles:
- **No Accounts:** Use the app instantly; your settings are stored locally.
- **Zero Tracking:** No telemetry, no analytics, no crash reporters sending data to third parties.
- **No Network Permissions:** The app functions entirely offline.
- **Pure FOSS:** Licensed under the [GNU General Public License v2](file:///home/quark/Projects/vektor/LICENSE).

---

## 🛠️ Getting Started

### Prerequisites
- Android 8.0 (API level 26) or higher (for system overlay support)
- Android Studio Ladybug (or newer)
- Gradle 8.0+

### Permissions Required
- **Display over other apps** (`SYSTEM_ALERT_WINDOW`): Necessary to render the motion dots while you use other apps (like maps, browsers, or books).

---

## 📄 License
This project is licensed under the **GNU General Public License v2 (GPL-2.0-only)**. See the [LICENSE](file:///home/quark/Projects/vektor/LICENSE) file for details.
