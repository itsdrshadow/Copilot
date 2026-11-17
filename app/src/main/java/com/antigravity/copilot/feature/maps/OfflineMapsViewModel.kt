package com.antigravity.copilot.feature.maps

import android.content.Context
import androidx.lifecycle.ViewModel
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.map.OfflineMapManager.OfflinePackage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

/**
 * UI state holder for the offline maps manager.
 *
 * USB import progress lives here (not in composition) so it survives the screen being closed
 * and reopened, and a second import cannot start while one is running.
 */
@HiltViewModel
class OfflineMapsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    val downloadState = OfflineMapManager.downloadState
    val activePackageId = OfflineMapManager.activePackageId
    val downloadProgress = OfflineMapManager.downloadProgress
    val downloadSpeedMbs = OfflineMapManager.downloadSpeedMbs
    val installedPackages = OfflineMapManager.installedPackages
    val currentGpsRegion = OfflineMapManager.currentGpsRegion
    val storageInfo = OfflineMapManager.storageInfo
    val statusMessage = OfflineMapManager.statusMessage

    /** Non-null while a USB/storage import is running; holds the latest progress text. */
    private val _usbImportProgress = MutableStateFlow<String?>(null)
    val usbImportProgress: StateFlow<String?> = _usbImportProgress.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun refresh() {
        OfflineMapManager.refreshPackageStatus(context)
        OfflineMapManager.refreshStorageInfo(context)
        val (lat, lon) = OfflineMapManager.getLastKnownPosition(context)
        OfflineMapManager.updateGpsLocation(lat, lon)
    }

    fun startDownload(packageId: String) = OfflineMapManager.startDownload(context, packageId)
    fun pauseDownload(packageId: String) = OfflineMapManager.pauseDownload(context, packageId)
    fun resumeDownload(packageId: String) = OfflineMapManager.resumeDownload(context, packageId)
    fun cancelDownload(packageId: String) = OfflineMapManager.cancelDownload(context, packageId)
    fun deletePackage(packageId: String) = OfflineMapManager.deletePackage(context, packageId)
    fun clearDownloadError() = OfflineMapManager.clearDownloadError()

    fun checkForUpdates() {
        OfflineMapManager.checkForUpdates(context)
        _messages.trySend("Checked for map updates")
    }

    fun effectiveDownloadUrl(pkg: OfflinePackage): String = OfflineMapManager.getEffectiveDownloadUrl(context, pkg)

    fun setCustomDownloadUrl(packageId: String, url: String?) =
        OfflineMapManager.setCustomDownloadUrl(context, packageId, url)

    fun importFromUsb(initialMessage: String) {
        if (_usbImportProgress.value != null) return
        _usbImportProgress.value = initialMessage
        OfflineMapManager.importFromExternalStorage(
            context = context,
            onProgress = { msg, _ -> _usbImportProgress.value = msg },
            onComplete = { _, msg ->
                _usbImportProgress.value = null
                _messages.trySend(msg)
            }
        )
    }
}
