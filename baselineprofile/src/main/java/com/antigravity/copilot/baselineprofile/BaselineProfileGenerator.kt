package com.antigravity.copilot.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's baseline profile by walking the main user journeys:
 * cold start on Drive, then every navigation-rail tab with a scroll each.
 *
 * Run (rooted device on API 28-32, or any device on API 33+):
 *   ./gradlew :app:generateReleaseBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE) {
        PERMISSIONS.forEach { device.executeShellCommand("pm grant $PACKAGE android.permission.$it") }

        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        // Rail tabs, designed for a 1280x800 head unit; scaled to the actual display.
        listOf(TRIPS_Y, FUEL_Y, MAPS_Y, APPS_Y, SETTINGS_Y, DRIVE_Y).forEach { y ->
            device.tapRail(y)
            device.waitForIdle()
            device.scrollContent()
        }
    }

    private fun UiDevice.tapRail(y: Int) {
        click(sx(RAIL_X), sy(y))
    }

    private fun UiDevice.scrollContent() {
        val x = sx(1000)
        swipe(x, sy(650), x, sy(200), 20)
        waitForIdle()
        swipe(x, sy(200), x, sy(650), 20)
        waitForIdle()
    }

    private fun UiDevice.sx(x: Int) = x * displayWidth / 1280
    private fun UiDevice.sy(y: Int) = y * displayHeight / 800

    private companion object {
        const val PACKAGE = "com.antigravity.copilot"
        const val RAIL_X = 35
        const val DRIVE_Y = 35
        const val TRIPS_Y = 100
        const val FUEL_Y = 160
        const val MAPS_Y = 225
        const val APPS_Y = 285
        const val SETTINGS_Y = 765
        val PERMISSIONS = listOf(
            "ACCESS_FINE_LOCATION",
            "ACCESS_COARSE_LOCATION",
            "ACCESS_BACKGROUND_LOCATION",
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
        )
    }
}
