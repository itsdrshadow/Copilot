package com.antigravity.copilot.core.location

import android.content.Context
import com.antigravity.copilot.core.trip.TripRepository

/**
 * Maintenance barrier for factory reset and backup restore (audit N2). Live tracking is paused
 * (no fixes, engine state dropped, queued trip writes drained) before the database is wiped or
 * replaced, and resumed afterwards from whatever the database now holds. Without it a late trip
 * flush could re-insert the old active trip into a freshly reset / restored database.
 */
object TrackingMaintenance {

    /** Implemented by the running DrivingTrackingService; null when the service is not running. */
    interface Handler {
        suspend fun pauseForMaintenance()
        suspend fun resumeAfterMaintenance()
    }

    @Volatile
    var handler: Handler? = null

    suspend fun <T> runPaused(context: Context, block: suspend () -> T): T {
        val h = handler
        h?.pauseForMaintenance()
        try {
            TripRepository.getInstance(context.applicationContext).drainPendingWrites()
            return block()
        } finally {
            h?.resumeAfterMaintenance()
        }
    }
}
