package com.antigravity.copilot.baselineprofile

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * Critical user journeys shared by [BaselineProfileGenerator] and [FrameTimingBenchmark].
 *
 * Selectors were taken from live `uiautomator dump`s on the 1280x800 head unit. Every flow is
 * read-only: dialogs are always left through Cancel / Close / back, never Save, Delete or Reset,
 * so running the suite never changes the user's data or settings.
 */
enum class Journey {
    HOME,
    TRIPS,
    FUEL,
    MAPS,
    APPS,
    LOG_FUEL,
    CALIBRATE_ODOMETER,
    CALIBRATE_GAUGE,
    SETTINGS,
}

/** Puts the app on the Drive screen so every journey starts from the same place. */
fun UiDevice.goDrive() {
    rail("Drive")
    awaitText("SPEEDOMETER")
}

fun UiDevice.runJourney(journey: Journey) {
    when (journey) {
        Journey.HOME -> homeMap()
        Journey.TRIPS -> trips()
        Journey.FUEL -> fuel()
        Journey.MAPS -> fullScreenMap()
        Journey.APPS -> apps()
        Journey.LOG_FUEL -> logFuel()
        Journey.CALIBRATE_ODOMETER -> calibrateOdometer()
        Journey.CALIBRATE_GAUGE -> calibrateGauge()
        Journey.SETTINGS -> settings()
    }
    waitForIdle()
}

// ---------------------------------------------------------------------------------------------
// Journeys
// ---------------------------------------------------------------------------------------------

private fun UiDevice.homeMap() {
    goDrive()
    val map = mapView() ?: return
    SystemClock.sleep(TILE_SETTLE_MS)
    panMap(map, rounds = 1)
    pinchMap(map, rounds = 1)
    tapDesc("Recenter")
}

private fun UiDevice.trips() {
    rail("Trips")
    awaitText("Trip Records") ?: return
    // Yearly charts panel open/close.
    if (tapDesc("Show yearly charts")) {
        wait(Until.findObject(By.textStartsWith("DISTANCE BY MONTH")), TIMEOUT_MS)
        tapDesc("Hide yearly charts")
    }
    // Year pill (re-selects the current year; there is one pill per year with trips).
    clickRightmost(By.text(YEAR))
    waitForIdle()
    // Trip detail tabs only exist once a trip is selected (the newest one is auto-selected).
    listOf("Timeline", "Trip Charts", "Route Replay").forEach { tapText(it) }
    scrollArea(x = 280, top = 250, bottom = 700)
}

private fun UiDevice.fuel() {
    rail("Fuel")
    awaitText("Fuel Records") ?: return
    clickRightmost(By.text(YEAR))
    waitForIdle()
    // Month group collapse / expand.
    if (tapDesc("Collapse")) tapDesc("Expand")
    // Edit refuel dialog -> Cancel.
    if (tapDesc("Edit Refuel")) cancel()
    // Delete confirmation -> Cancel (never Delete).
    if (tapDesc("Delete Refuel")) cancel()
    // Add Fuel dialog from the Fuel screen -> Close.
    if (tapText("Add Fuel")) {
        awaitText("Amount paid")
        tapDesc("Close")
    }
    scrollArea(x = 840, top = 300, bottom = 720)
}

private fun UiDevice.fullScreenMap() {
    rail("Maps")
    wait(Until.findObject(By.desc("Map options")), TIMEOUT_MS)
    val map = mapView() ?: return
    // Let the offline PMTiles style and first tiles load.
    SystemClock.sleep(TILE_SETTLE_MS * 2)
    panMap(map, rounds = 3)
    pinchMap(map, rounds = 2)
    panMap(map, rounds = 1)
    // Map menu -> Offline maps manager dialog -> Close.
    if (tapDesc("Map options")) {
        if (tapText("Offline maps")) {
            SystemClock.sleep(SHORT_MS)
            tapDesc("Close")
        }
        tapDesc("Close map menu")
    }
    tapDesc("Resume follow")
}

private fun UiDevice.apps() {
    rail("Apps")
    awaitText("All Applications") ?: return
    // The drawer is a horizontal pager (6x5 grid per page): page forward, then back.
    val y = sy(450)
    repeat(2) {
        swipe(sx(1100), y, sx(250), y, PAGE_STEPS)
        waitForIdle()
    }
    repeat(2) {
        swipe(sx(250), y, sx(1100), y, PAGE_STEPS)
        waitForIdle()
    }
}

private fun UiDevice.logFuel() {
    goDrive()
    if (!tapText("Add Fuel")) return
    awaitText("Amount paid") ?: return
    tapText("Amount paid")
    listOf("5", "0", "0").forEach { keypad(it) }
    tapText("Price / L")
    tapDesc("Backspace")
    keypad("9")
    tapText("Odometer (optional, tap to enter)")
    keypad("Clear")
    // Toggle "Full tank fill" off and back on (dialog-local state, nothing is saved).
    if (tapText("Full tank fill")) tapText("Full tank fill")
    scrollArea(x = 450, top = 250, bottom = 650)
    tapDesc("Close")
    wait(Until.gone(By.text("Amount paid")), TIMEOUT_MS)
}

private fun UiDevice.calibrateOdometer() {
    goDrive()
    if (!tapText("Odometer")) return
    awaitText("Calibrate odometer") ?: return
    keypad("Clear")
    listOf("9", "4", "3", "5").forEach { keypad(it) }
    tapDesc("Backspace")
    // The dialog has no Cancel button: dismiss with back (nothing is saved).
    pressBack()
    wait(Until.gone(By.text("Calibrate odometer")), TIMEOUT_MS)
}

private fun UiDevice.calibrateGauge() {
    goDrive()
    if (!tapDesc("Fuel gauge. Tap to calibrate.")) return
    wait(Until.findObject(By.desc("Bar 5")), TIMEOUT_MS) ?: return
    listOf("Bar 3", "Bar 7", "Bar 5").forEach { tapDesc(it) }
    cancel()
}

private fun UiDevice.settings() {
    rail("Settings")
    awaitText("Automotive System Settings") ?: return
    SETTINGS_TABS.forEach { tab ->
        settingsTab(tab)
        scrollArea(x = 800, top = 200, bottom = 720)
        when (tab) {
            "Trip Geofences" -> {
                if (tapText("Add Geofence")) cancel()
                if (tapDesc("Edit Geofence")) cancel()
                if (tapDesc("Delete Geofence")) cancel()
            }
            "Data & Storage" -> {
                // Step 1 warning only -> Cancel. Never reaches the password step.
                if (tapText("Factory Reset App Data")) {
                    wait(Until.findObject(By.text("Proceed to Confirmation")), TIMEOUT_MS)
                    cancel()
                }
            }
        }
    }
    settingsTab("General")
    tapDesc("Close")
    awaitText("SPEEDOMETER")
}

// ---------------------------------------------------------------------------------------------
// Helpers (coordinates are authored for 1280x800 and scaled to the real display)
// ---------------------------------------------------------------------------------------------

private fun UiDevice.rail(label: String) {
    // Rail items sit at fixed positions; a coordinate tap cannot go stale.
    click(sx(35), sy(RAIL_Y.getValue(label)))
    waitForIdle()
    SystemClock.sleep(SHORT_MS)
}

private fun UiDevice.settingsTab(label: String) {
    retryStale { findObjects(By.text(label)).firstOrNull { it.visibleBounds.centerX() < sx(340) }?.click() }
    waitForIdle()
}

private fun UiDevice.awaitText(text: String): UiObject2? =
    wait(Until.findObject(By.text(text)), TIMEOUT_MS)

private fun UiDevice.tapText(text: String): Boolean = tap(By.text(text))

private fun UiDevice.tapDesc(desc: String): Boolean = tap(By.desc(desc))

private fun UiDevice.tap(selector: BySelector): Boolean {
    val clicked = retryStale {
        val obj = wait(Until.findObject(selector), SHORT_MS) ?: return@retryStale false
        obj.click()
        true
    } ?: false
    if (clicked) {
        waitForIdle()
        SystemClock.sleep(SHORT_MS)
    }
    return clicked
}

/** Compose replaces nodes while animating; re-run [block] (which re-finds them) when one goes stale. */
private fun <T> retryStale(block: () -> T): T? {
    repeat(3) {
        try {
            return block()
        } catch (e: StaleObjectException) {
            SystemClock.sleep(SHORT_MS)
        }
    }
    return null
}

/** Dismisses the current dialog through its Cancel button, falling back to back. */
private fun UiDevice.cancel() {
    if (!tapText("Cancel")) pressBack()
    waitForIdle()
}

/** On-screen keypads sit on the right of their dialogs: pick the rightmost matching key. */
private fun UiDevice.keypad(key: String) {
    clickRightmost(By.text(key))
}

private fun UiDevice.clickRightmost(selector: BySelector) {
    retryStale { findObjects(selector).maxByOrNull { it.visibleBounds.left }?.click() }
    waitForIdle()
}

private fun UiDevice.mapView(): UiObject2? =
    wait(Until.findObject(By.descStartsWith("Showing a Map")), TIMEOUT_MS)

private fun UiDevice.panMap(map: UiObject2, rounds: Int) {
    val b = map.visibleBounds
    val cx = b.centerX()
    val cy = b.centerY()
    val dx = b.width() / 4
    val dy = b.height() / 4
    repeat(rounds) {
        swipe(cx + dx, cy, cx - dx, cy, PAN_STEPS)
        swipe(cx, cy + dy, cx, cy - dy, PAN_STEPS)
        swipe(cx - dx, cy - dy, cx + dx, cy + dy, PAN_STEPS)
        swipe(cx, cy - dy, cx, cy + dy, PAN_STEPS)
        waitForIdle()
    }
}

private fun UiDevice.pinchMap(map: UiObject2, rounds: Int) {
    map.setGestureMargin(map.visibleBounds.width() / 5)
    repeat(rounds) {
        retryStale { map.pinchOpen(0.7f, PINCH_SPEED) }
        waitForIdle()
        retryStale { map.pinchClose(0.7f, PINCH_SPEED) }
        waitForIdle()
    }
}

private fun UiDevice.scrollArea(x: Int, top: Int, bottom: Int) {
    swipe(sx(x), sy(bottom), sx(x), sy(top), SCROLL_STEPS)
    waitForIdle()
    swipe(sx(x), sy(top), sx(x), sy(bottom), SCROLL_STEPS)
    waitForIdle()
}

private fun UiDevice.sx(x: Int) = x * displayWidth / 1280
private fun UiDevice.sy(y: Int) = y * displayHeight / 800

private val YEAR: Pattern = Pattern.compile("20\\d\\d")
private val SETTINGS_TABS = listOf("General", "Vehicle", "Trip Geofences", "Data & Storage", "About")
private val RAIL_Y = mapOf("Drive" to 35, "Trips" to 100, "Fuel" to 160, "Maps" to 225, "Apps" to 285, "Settings" to 765)
private const val TIMEOUT_MS = 5_000L
private const val SHORT_MS = 400L
private const val TILE_SETTLE_MS = 1_500L
private const val PAN_STEPS = 25
private const val SCROLL_STEPS = 15
private const val PAGE_STEPS = 12
private const val PINCH_SPEED = 2_500
