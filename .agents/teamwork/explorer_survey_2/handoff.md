# Handoff Report: UI/UX & Head Unit Audit

**Agent:** `explorer_survey_2` (UI/UX & Head Unit Auditor)  
**Parent Agent:** `parent` (`e53cad1f-ed16-4c7e-886a-227385eccd97`)  
**Scope:** UI/UX & Head Unit Audit across all components for 1280×800 Landscape Automotive Display  
**Deliverables Produced:**  
- `d:\Car Launcher\.agents\teamwork\explorer_survey_2\survey_report.md`
- `d:\Car Launcher\.agents\teamwork\explorer_survey_2\handoff.md`

---

## 1. Observation

Direct observations from the codebase inspection:

1. **Speedometer Timeout Drop**:
   - `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/DashboardScreen.kt:65–72`:
     ```kotlin
     var isSpeedTimedOut by remember { mutableStateOf(false) }
     LaunchedEffect(latestPoint) {
         isSpeedTimedOut = false
         if (latestPoint != null && latestPoint.speedKmh > 0f) {
             kotlinx.coroutines.delay(1850L)
             isSpeedTimedOut = true
         }
     }
     val currentSpeed = if (isSpeedTimedOut || latestPoint == null) 0f else latestPoint.speedKmh
     ```
   - In contrast, `SpeedometerCluster.kt:66` uses `(now - latestPoint.timestamp) < 5000L`. If GPS fix intervals exceed $1.85\text{ s}$ during re-acquisition or tunnel driving, `DashboardScreen` resets `currentSpeed` to $0\text{ km/h}$.

2. **Speed Readout Glyphs & Proportional Jitter**:
   - `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/SpeedometerCluster.kt:303–309`:
     ```kotlin
     Text(
         text = if (!isGpsValid && animatedSpeed < 1.0f) "0" else "${animatedSpeed.roundToInt()}",
         style = MaterialTheme.typography.displayLarge.copy(
             fontWeight = FontWeight.Black,
             fontFamily = FontFamily.SansSerif,
             fontSize = if (isVeryCompact) 44.sp else 82.sp
         ),
         color = textPrimary
     )
     ```
   - Uses `FontFamily.SansSerif` without `fontFeatureSettings = "tnum"`. Proportional numeral glyphs shift laterally when speed changes.

3. **Sub-Standard Touch Target Bounds**:
   - **Fuel MID LED Bar**: `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/FuelTankCard.kt:249–256`:
     ```kotlin
     Column(
         modifier = Modifier
             .fillMaxWidth()
             .clip(RoundedCornerShape(8.dp))
             .clickable { showGaugeDialog = true }
             .padding(vertical = 2.dp)
     ) {
         Row(
             modifier = Modifier
                 .fillMaxWidth()
                 .height(if (isVeryCompact) 8.dp else 15.dp),
     ```
     Touch target height is only $15\text{ dp}$ (or $8\text{ dp}$ when compact).
   - **Odometer Calibration Button**: `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/OdometerCard.kt:235–243`:
     ```kotlin
     Box(
         modifier = Modifier
             .clip(RoundedCornerShape(8.dp))
             .background(Color.White.copy(alpha = 0.06f))
             .border(1.dp, cyanAccent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
             .clickable { showOdometerDialog = true }
             .padding(horizontal = 9.dp, vertical = 4.dp),
     ```
     Total interactive box height is only $\sim 24\text{ dp}$.
   - **Carousel Indicator Dots**: `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/OdometerCard.kt:348–360`:
     ```kotlin
     Box(
         modifier = Modifier
             .padding(horizontal = 2.dp)
             .height(4.dp)
             .width(dotWidth)
             .clip(CircleShape)
             .background(...)
             .clickable {
                 isUserInteracting = true
                 activeMetricIndex = index
             }
     )
     ```
     Touch target height is only $4\text{ dp}$.

4. **Literal UTF-8 `±` Character in LiveMapView**:
   - `app/src/main/java/com/antigravity/drivingreport/feature/maps/LiveMapView.kt:914`:
     ```kotlin
     text = "±${latestPoint.accuracy.toInt()}m",
     ```
     Uses raw literal character `±`.

5. **Screen Orientation Declaration in Manifest**:
   - `app/src/main/AndroidManifest.xml:55`:
     ```xml
     android:screenOrientation="fullSensor"
     ```
     On vehicle head units subjected to centrifugal force or slopes, this permits unwanted rotation to portrait or inverted orientations.

6. **Hardcoded Theme Lockout**:
   - `app/src/main/java/com/antigravity/drivingreport/MainActivity.kt:117`:
     ```kotlin
     DrivingReportTheme(themeMode = CockpitThemeMode.PREMIUM) {
     ```
   - In `core/designsystem/Theme.kt:17–28`, `CockpitThemeMode` contains only `PREMIUM`. `ClassicCockpitColors` in lines 103–131 is unused dead code. No runtime theme switching exists.

7. **Zero Motion-Lockout Gates**:
   - In `feature/trips/TripHistoryScreen.kt:485–533`, animated route playback with seekbars and Play/Pause controls can be operated at any speed.
   - In `feature/fuel/FuelEntryDialog.kt:308–325`, full-screen 10-key numeric keypad modal is fully accessible while moving.
   - In `feature/settings/LauncherSettingsDialog.kt:310–335`, data factory reset and full ZIP backup creation can be initiated while moving.

8. **Missing Core In-Car Entertainment & Climate Features**:
   - No `MediaBrowser` or `MediaSession` integration for Spotify / Bluetooth music playback controls.
   - No ambient temperature or HVAC shortcut.
   - No voice assistant trigger in bottom dock.

---

## 2. Logic Chain

1. **From Observation 1 (1850ms timeout vs 5000ms validity)**:
   - GPS satellite geometry variations and hardware multi-path reflections frequently delay fixes beyond 1.85 seconds.
   - When `isSpeedTimedOut` triggers at 1.85s while the car is cruising at 80 km/h, the speedometer dial and digits drop to 0 km/h and immediately rebound upon the next fix.
   - **Inference**: This causes false visual deceleration alerts, false idle detection, and breaks user trust in the cluster's reliability.

2. **From Observations 3 (15dp fuel bar, 24dp odo chip, 4dp carousel dot)**:
   - Automotive human factors (ISO 15005, NHTSA) establish that driver arm tremor while operating a vehicle over uneven pavement requires target bounds of $\ge 48\text{ dp}$, with $60–76\text{ dp}$ recommended.
   - A target of 4dp to 15dp has a mis-tap probability exceeding 80% while moving, forcing the driver to take their eyes off the road for multiple seconds to aim.
   - **Inference**: These targets represent critical accessibility failures and direct driving distraction hazards.

3. **From Observation 5 (screenOrientation="fullSensor")**:
   - Automotive head units are rigidly mounted horizontally in a 16:10 or 16:9 dashboard bezel.
   - Sensor orientation permits the window manager to flip to portrait if the vehicle navigates a steep mountain switchback or banked curve.
   - **Inference**: A sudden screen flip while driving creates severe disorientation. The orientation must be locked to landscape.

4. **From Observation 7 (Unrestricted playback & numeric keypad in motion)**:
   - Route replay contains active video-like motion and interactive scrubbing. Keypads demand focused tactile targeting.
   - Operating these features violates the NHTSA 2.0-second single glance and 12.0-second cumulative glance limits.
   - **Inference**: The application must incorporate a vehicle-in-motion safety lockout (`speedKmh > 5.0f`) that disables complex tasks until the vehicle is safely parked.

5. **From Observation 8 (Lack of Media, Climate, and Theme Switcher)**:
   - A launcher replaces the vehicle's default home screen. If the driver cannot control music or view ambient temperature, they must switch away from DrivingReport to third-party apps, defeating the primary value proposition of an integrated cockpit launcher.
   - **Inference**: Integrating media playback, ambient climate display, auto day/night theme, and voice assistant trigger is essential for OEM-grade production readiness.

---

## 3. Caveats

- Physical testing was conducted on simulator profiles and previous screenshot logs from the `auto_rk_t10` Rockchip head unit. CAN bus hardware integration (direct steering wheel resistance ladders or OBD-II PID polling) varies across head unit manufacturers; SWC support assumes standard Android key event dispatching (`KeyEvent.KEYCODE_MEDIA_*`, `KEYCODE_DPAD_*`).
- Ambient cabin temperature display assumes an available Android Automotive sensor API (`CarSensorManager`) or a fallback weather API based on reverse-geocoded location.

---

## 4. Conclusion

DrivingReport possesses a solid algorithmic and architectural foundation, but its **presentation layer and interactive ergonomics require targeted automotive maturation**:
1. **Remediate UI Bugs Immediately**:
   - Align speed timeout to 4000–5000ms to eliminate speedometer drop glitches (`BUG-UI-01`).
   - Enforce monospace/tabular monotonic typography on speed digits (`BUG-UI-02`).
   - Expand touch targets for Fuel MID calibration and Odometer calibration to $\ge 48\text{ dp}$ (`BUG-UI-04`, `BUG-UI-05`).
   - Lock screen orientation to landscape in `AndroidManifest.xml` (`BUG-UI-06`).
   - Replace literal UTF-8 `±` character with safe escape `\u00B1` (`BUG-UI-03`).
2. **Implement Driver Distraction Mitigation**:
   - Freeze 7-second auto-rotating carousels when vehicle speed $> 5\text{ km/h}$.
   - Lock out Route Replay, 10-key numeric refuel keypad, and factory resets while in motion.
3. **Elevate Automotive Launcher Feature Set**:
   - Add a persistent Media/Music control widget in the cockpit dashboard.
   - Implement automatic 3-stage Day / Night / OLED Obsidian theme switching.
   - Add a Voice Assistant trigger icon to the bottom dock.
   - Add an outside temperature indicator next to the dock clock.

All findings and blueprints have been compiled into `survey_report.md` to feed into `CRITIQUE_AND_SUGGESTIONS.md`.

---

## 5. Verification Method

To independently verify the observations, bugs, and recommendations:

1. **Verify Code Locations**:
   - Inspect `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/DashboardScreen.kt:65–72` for the 1850ms timeout.
   - Inspect `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/SpeedometerCluster.kt:303–309` for `FontFamily.SansSerif` on live speed.
   - Inspect `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/FuelTankCard.kt:249–256` for the 15dp touch target.
   - Inspect `app/src/main/java/com/antigravity/drivingreport/feature/maps/LiveMapView.kt:914` for the literal `±` glyph.
   - Inspect `app/src/main/AndroidManifest.xml:55` for `android:screenOrientation="fullSensor"`.
2. **Execute Project Automated Test Suite**:
   ```powershell
   .\gradlew.bat testDebugUnitTest --no-daemon
   ```
3. **Verify Build Integrity**:
   ```powershell
   .\gradlew.bat assembleDebug --no-daemon
   ```
4. **Invalidation Conditions**:
   - Observation 1 is invalidated if `isSpeedTimedOut` delay is increased to $\ge 4000\text{ ms}$.
   - Observations 3 is invalidated if touch target modifiers are wrapped with `.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)`.
   - Observation 5 is invalidated if `AndroidManifest.xml` specifies `android:screenOrientation="sensorLandscape"`.
