package com.antigravity.copilot.domain.repository

import com.antigravity.copilot.core.persistence.FullBackupManager.BackupResult

/** Full backup of trips, fuel log and settings. */
interface BackupRepository {
    suspend fun createFullBackup(): BackupResult
}
