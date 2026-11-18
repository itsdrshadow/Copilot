package com.antigravity.copilot.core.persistence

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Automatic full backups (audit N3 / N6): the complete ZIP format of FullBackupManager (trips,
 * points, fuel history and model, stations, road data, all prefs), written daily and at every
 * trip end into the app-private external files folder (not shared storage: location history
 * stays private). The newest [KEEP] archives are kept. A fresh install / wiped database restores
 * from the newest one at startup.
 */
object AutoBackupManager {
    private const val TAG = "AutoBackupManager"
    private const val PREFS = "auto_backup_prefs"
    private const val KEY_LAST_MS = "last_backup_ms"
    private const val PREFIX = "Auto_Backup"
    const val KEEP = 3
    const val DAILY_MS = 24L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun directory(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "AutoBackups").apply { if (!exists()) mkdirs() }
    }

    fun archives(context: Context): List<File> =
        directory(context).listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun latest(context: Context): File? = archives(context).firstOrNull { it.length() > 0L }

    /** Daily (or [force]: trip end) full backup; never two at once. */
    fun requestBackup(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_MS, 0L) in 0 until DAILY_MS) return
        scope.launch {
            if (!mutex.tryLock()) return@launch // one already running
            try {
                val result = FullBackupManager.createFullBackupZip(app, targetDir = directory(app), filePrefix = PREFIX)
                if (result.isSuccess) {
                    prefs.edit().putLong(KEY_LAST_MS, now).apply()
                    rotate(app)
                } else {
                    Log.w(TAG, "Auto backup failed: ${result.errorMessage}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Auto backup failed: ${e.message}")
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun rotate(context: Context) {
        archives(context).drop(KEEP).forEach { runCatching { it.delete() } }
    }

    /** Startup restore into an empty database from the newest archive; false when none / failed. */
    suspend fun restoreLatestIfAvailable(context: Context): Boolean = mutex.withLock {
        val file = latest(context) ?: return@withLock false
        val result = FullBackupManager.restoreFromBackupZip(context, Uri.fromFile(file), skipSafetyBackup = true)
        if (!result.isSuccess) Log.w(TAG, "Auto restore from ${file.name} failed: ${result.errorMessage}")
        result.isSuccess
    }

    fun clearAll(context: Context) {
        archives(context).forEach { runCatching { it.delete() } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
