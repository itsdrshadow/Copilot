package com.antigravity.copilot.data.repository

import android.content.Context
import com.antigravity.copilot.core.persistence.FullBackupManager
import com.antigravity.copilot.core.persistence.FullBackupManager.BackupResult
import com.antigravity.copilot.domain.repository.BackupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class BackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : BackupRepository {
    override suspend fun createFullBackup(): BackupResult = FullBackupManager.createFullBackupZip(context)
}
