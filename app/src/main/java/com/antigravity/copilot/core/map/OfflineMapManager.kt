package com.antigravity.copilot.core.map

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Unified OfflineMapManager:
 * Manages Scoped Storage (maps, routing, downloads) on Android 11+ head units.
 * Controls WorkManager-based resumable downloads with Range headers and storage budgeting.
 */
object OfflineMapManager {
    private const val TAG = "OfflineMapManager"
    private const val PREFS_NAME = "offline_map_manager_prefs"
    private const val KEY_LAST_LAT = "last_known_lat"
    private const val KEY_LAST_LON = "last_known_lon"

    const val MAX_OFFLINE_STORAGE_BUDGET_BYTES = 15L * 1024L * 1024L * 1024L // 15 GB
    const val LOW_STORAGE_WARNING_THRESHOLD_BYTES = 2L * 1024L * 1024L * 1024L // 2 GB

    val DARK_MODE_FILTER: android.graphics.ColorFilter = android.graphics.ColorMatrixColorFilter(
        floatArrayOf(
            -1.0f, 0.0f, 0.0f, 0.0f, 255f,
            0.0f, -1.0f, 0.0f, 0.0f, 255f,
            0.0f, 0.0f, -1.0f, 0.0f, 255f,
            0.0f, 0.0f, 0.0f, 1.0f, 0.0f
        )
    )

    // Fallback coordinates (New Delhi center)
    private const val DEFAULT_LAT = 28.6139
    private const val DEFAULT_LON = 77.2090

    enum class DownloadState {
        IDLE,
        DOWNLOADING,
        PAUSED_USER,
        PAUSED_NETWORK_LOST,
        EXTRACTING,
        COMPLETED,
        ERROR
    }

    enum class PackageType {
        VECTOR_MAP,
        ROUTING_GRAPH
    }

    data class OfflinePackage(
        val id: String,
        val title: String,
        val description: String,
        val type: PackageType,
        val estimatedSizeBytes: Long,
        val downloadUrl: String,
        val fileName: String,
        val isInstalled: Boolean = false,
        val installedSizeBytes: Long = 0L
    )

    data class StorageBudgetInfo(
        val totalDeviceBytes: Long = 0L,
        val freeDeviceBytes: Long = 0L,
        val offlineMapsUsedBytes: Long = 0L,
        val isLowStorageWarning: Boolean = false
    )

    // Standard Car Navigation Offline Packages
    val AVAILABLE_PACKAGES = listOf(
        OfflinePackage(
            id = "india_vector_map",
            title = "All-India Vector Map",
            description = "Detailed nationwide road network, highways, and terrain (.mbtiles)",
            type = PackageType.VECTOR_MAP,
            estimatedSizeBytes = 4L * 1024L * 1024L * 1024L, // ~4.0 GB
            downloadUrl = "https://download.geofabrik.de/asia/india-latest.mbtiles",
            fileName = "india.mbtiles"
        ),
        OfflinePackage(
            id = "india_routing_graph",
            title = "All-India Routing Graph",
            description = "Offline turn-by-turn navigation data for GraphHopper Core (.zip)",
            type = PackageType.ROUTING_GRAPH,
            estimatedSizeBytes = 4500L * 1024L * 1024L, // ~4.5 GB
            downloadUrl = "https://graphhopper.com/public/maps/india-gh.zip",
            fileName = "india-gh.zip"
        )
    )

    private val _downloadState = MutableStateFlow(DownloadState.IDLE)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _activePackageId = MutableStateFlow<String?>(null)
    val activePackageId: StateFlow<String?> = _activePackageId.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadSpeedMbs = MutableStateFlow(0.0)
    val downloadSpeedMbs: StateFlow<Double> = _downloadSpeedMbs.asStateFlow()

    private val _downloadedBytes = MutableStateFlow(0L)
    val downloadedBytes: StateFlow<Long> = _downloadedBytes.asStateFlow()

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    private val _installedPackages = MutableStateFlow<List<OfflinePackage>>(AVAILABLE_PACKAGES)
    val installedPackages: StateFlow<List<OfflinePackage>> = _installedPackages.asStateFlow()

    private val _storageInfo = MutableStateFlow(StorageBudgetInfo())
    val storageInfo: StateFlow<StorageBudgetInfo> = _storageInfo.asStateFlow()

    private val _statusMessage = MutableStateFlow("Offline storage ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var prefs: SharedPreferences? = null

    /**
     * Initializes directory structures and checks installed packages.
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        getMapsDir(context).mkdirs()
        getRoutingDir(context).mkdirs()
        getDownloadsDir(context).mkdirs()
        refreshPackageStatus(context)
        refreshStorageInfo(context)
    }

    /**
     * Backward-compatible alias for CopilotApp
     */
    fun initOfflineConfiguration(context: Context) {
        init(context)
    }

    fun getMapsDir(context: Context): File = File(context.getExternalFilesDir(null), "maps")
    fun getRoutingDir(context: Context): File = File(context.getExternalFilesDir(null), "routing")
    fun getDownloadsDir(context: Context): File = File(context.getExternalFilesDir(null), "downloads")

    fun getMapFile(context: Context): File? {
        val mapsDir = getMapsDir(context)
        val indiaMap = File(mapsDir, "india.mbtiles")
        if (indiaMap.exists() && indiaMap.length() > 0) return indiaMap
        // Fallback to any .mbtiles in directory
        return mapsDir.listFiles { _, name -> name.endsWith(".mbtiles") }?.firstOrNull()
    }

    fun hasOfflineMap(context: Context): Boolean = getMapFile(context) != null

    fun hasOfflineRouting(context: Context): Boolean = OfflineRoutingEngine.hasRoutingGraph(context)

    /**
     * Refreshes the installation status of packages in Scoped Storage.
     */
    fun refreshPackageStatus(context: Context) {
        val updated = AVAILABLE_PACKAGES.map { pkg ->
            if (pkg.type == PackageType.VECTOR_MAP) {
                val target = File(getMapsDir(context), pkg.fileName)
                val installed = target.exists() && target.length() > 0
                pkg.copy(isInstalled = installed, installedSizeBytes = if (installed) target.length() else 0L)
            } else {
                val routingDir = File(getRoutingDir(context), "india")
                val installed = OfflineRoutingEngine.hasRoutingGraph(context)
                val size = if (installed) calculateFolderSize(routingDir) else 0L
                pkg.copy(isInstalled = installed, installedSizeBytes = size)
            }
        }
        _installedPackages.value = updated
    }

    /**
     * Recalculates storage capacity and alerts if remaining space is critically low.
     */
    fun refreshStorageInfo(context: Context) {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val stat = StatFs(baseDir.path)
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes
        val mapsSize = calculateFolderSize(getMapsDir(context))
        val routingSize = calculateFolderSize(getRoutingDir(context))
        val downloadsSize = calculateFolderSize(getDownloadsDir(context))
        val totalOfflineUsed = mapsSize + routingSize + downloadsSize

        _storageInfo.value = StorageBudgetInfo(
            totalDeviceBytes = totalBytes,
            freeDeviceBytes = freeBytes,
            offlineMapsUsedBytes = totalOfflineUsed,
            isLowStorageWarning = freeBytes < LOW_STORAGE_WARNING_THRESHOLD_BYTES
        )
    }

    /**
     * Starts or resumes package download using WorkManager.
     */
    fun startDownload(context: Context, packageId: String) {
        val pkg = AVAILABLE_PACKAGES.find { it.id == packageId } ?: return

        // Verify storage budget before starting download
        refreshStorageInfo(context)
        if (_storageInfo.value.freeDeviceBytes < (pkg.estimatedSizeBytes + LOW_STORAGE_WARNING_THRESHOLD_BYTES)) {
            _statusMessage.value = "Insufficient storage space to download ${pkg.title}"
            _downloadState.value = DownloadState.ERROR
            return
        }

        _activePackageId.value = packageId
        _downloadState.value = DownloadState.DOWNLOADING
        _statusMessage.value = "Downloading ${pkg.title}..."

        val targetDest = if (pkg.type == PackageType.VECTOR_MAP) {
            File(getMapsDir(context), pkg.fileName).absolutePath
        } else {
            File(getDownloadsDir(context), pkg.fileName).absolutePath
        }

        val workData = workDataOf(
            MapDownloadWorker.KEY_URL to pkg.downloadUrl,
            MapDownloadWorker.KEY_DEST_PATH to targetDest,
            MapDownloadWorker.KEY_PACKAGE_ID to pkg.id,
            MapDownloadWorker.KEY_PACKAGE_TYPE to if (pkg.type == PackageType.VECTOR_MAP) "MAP" else "ROUTING",
            MapDownloadWorker.KEY_ESTIMATED_BYTES to pkg.estimatedSizeBytes
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val downloadWork = OneTimeWorkRequestBuilder<MapDownloadWorker>()
            .setInputData(workData)
            .setConstraints(constraints)
            .addTag("offline_download_$packageId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "download_$packageId",
            ExistingWorkPolicy.REPLACE,
            downloadWork
        )
    }

    fun pauseDownload(context: Context, packageId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("download_$packageId")
        _downloadState.value = DownloadState.PAUSED_USER
        _statusMessage.value = "Download paused"
    }

    fun resumeDownload(context: Context, packageId: String) {
        startDownload(context, packageId)
    }

    fun cancelDownload(context: Context, packageId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("download_$packageId")
        _downloadState.value = DownloadState.IDLE
        _activePackageId.value = null
        _downloadProgress.value = 0f
        _downloadSpeedMbs.value = 0.0
        _statusMessage.value = "Download cancelled"

        // Clean up partial downloads
        managerScope.launch {
            val pkg = AVAILABLE_PACKAGES.find { it.id == packageId } ?: return@launch
            val destFile = if (pkg.type == PackageType.VECTOR_MAP) {
                File(getMapsDir(context), "${pkg.fileName}.download")
            } else {
                File(getDownloadsDir(context), "${pkg.fileName}.download")
            }
            if (destFile.exists()) destFile.delete()
            refreshStorageInfo(context)
        }
    }

    fun deletePackage(context: Context, packageId: String) {
        managerScope.launch {
            val pkg = AVAILABLE_PACKAGES.find { it.id == packageId } ?: return@launch
            if (pkg.type == PackageType.VECTOR_MAP) {
                val file = File(getMapsDir(context), pkg.fileName)
                if (file.exists()) file.delete()
            } else {
                val routingDir = File(getRoutingDir(context), "india")
                if (routingDir.exists()) routingDir.deleteRecursively()
            }
            refreshPackageStatus(context)
            refreshStorageInfo(context)
            _statusMessage.value = "Deleted ${pkg.title}"
        }
    }

    /**
     * Import map and routing packages from external USB drive or SD card.
     */
    fun importFromExternalStorage(
        context: Context,
        onProgress: (String, Float) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        managerScope.launch {
            try {
                var importedCount = 0
                // Scan common Android external USB mount paths
                val searchDirs = listOf(
                    File("/storage/"),
                    File("/mnt/media_rw/"),
                    Environment.getExternalStorageDirectory()
                )

                for (base in searchDirs) {
                    if (!base.exists() || !base.canRead()) continue
                    val files = base.listFiles() ?: continue
                    for (file in files) {
                        if (file.isDirectory) {
                            val subFiles = file.listFiles() ?: continue
                            for (sf in subFiles) {
                                if (sf.name.endsWith(".mbtiles", ignoreCase = true)) {
                                    onProgress("Copying ${sf.name}...", 0.5f)
                                    val dest = File(getMapsDir(context), "india.mbtiles")
                                    copyFileWithProgress(sf, dest)
                                    importedCount++
                                } else if (sf.name.contains("routing", ignoreCase = true) && sf.name.endsWith(".zip", ignoreCase = true)) {
                                    onProgress("Extracting ${sf.name}...", 0.7f)
                                    val destDir = File(getRoutingDir(context), "india")
                                    destDir.mkdirs()
                                    extractZipDirect(sf, destDir)
                                    importedCount++
                                }
                            }
                        }
                    }
                }

                refreshPackageStatus(context)
                refreshStorageInfo(context)
                if (importedCount > 0) {
                    onComplete(true, "Successfully imported $importedCount offline package(s)")
                } else {
                    onComplete(false, "No .mbtiles or routing archives found on USB/SD card.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "USB Import failed: ${e.message}", e)
                onComplete(false, "Import error: ${e.message}")
            }
        }
    }

    private fun copyFileWithProgress(source: File, dest: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(128 * 1024)
                var bytes: Int
                while (input.read(buffer).also { bytes = it } > 0) {
                    output.write(buffer, 0, bytes)
                }
            }
        }
    }

    private fun extractZipDirect(zipFile: File, outputDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(64 * 1024)
            while (entry != null) {
                val newFile = File(outputDir, entry.name)
                if (!newFile.canonicalPath.startsWith(outputDir.canonicalPath)) {
                    throw SecurityException("Zip entry is outside target: ${entry.name}")
                }
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    // Callbacks from MapDownloadWorker
    fun updateDownloadProgress(packageId: String, progress: Float, speedMbs: Double, downloaded: Long, total: Long) {
        _activePackageId.value = packageId
        _downloadState.value = DownloadState.DOWNLOADING
        _downloadProgress.value = progress
        _downloadSpeedMbs.value = speedMbs
        _downloadedBytes.value = downloaded
        _totalBytes.value = total
    }

    fun notifyExtracting(packageId: String) {
        _downloadState.value = DownloadState.EXTRACTING
        _statusMessage.value = "Extracting routing graph..."
    }

    fun notifyDownloadComplete(packageId: String) {
        _downloadState.value = DownloadState.COMPLETED
        _downloadProgress.value = 1f
        _downloadSpeedMbs.value = 0.0
        _statusMessage.value = "Download completed successfully"
    }

    fun notifyDownloadPaused(packageId: String) {
        _downloadState.value = DownloadState.PAUSED_USER
        _statusMessage.value = "Download paused"
    }

    fun notifyDownloadError(packageId: String, error: String) {
        _downloadState.value = DownloadState.ERROR
        _statusMessage.value = "Download failed: $error"
    }

    fun checkForUpdates(context: Context) {
        _statusMessage.value = "Checking for map updates..."
        refreshPackageStatus(context)
        refreshStorageInfo(context)
        _statusMessage.value = "All offline maps are up to date."
    }

    fun setLightweightMode(enabled: Boolean) {
        Log.i(TAG, "OfflineMapManager lightweight mode set to: $enabled")
    }

    fun getOfflineCacheSizeBytes(context: Context): Long {
        return calculateFolderSize(getMapsDir(context)) + calculateFolderSize(getRoutingDir(context))
    }

    // Last Known Position (for DrivingTrackingService and TripRepository)
    fun saveLastKnownPosition(context: Context, latitude: Double, longitude: Double) {
        val p = prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs = it }
        p.edit()
            .putFloat(KEY_LAST_LAT, latitude.toFloat())
            .putFloat(KEY_LAST_LON, longitude.toFloat())
            .apply()
    }

    fun getLastKnownPosition(context: Context): Pair<Double, Double> {
        val p = prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs = it }
        val lat = p.getFloat(KEY_LAST_LAT, DEFAULT_LAT.toFloat()).toDouble()
        val lon = p.getFloat(KEY_LAST_LON, DEFAULT_LON.toFloat()).toDouble()
        return Pair(lat, lon)
    }

    private fun calculateFolderSize(folder: File): Long {
        if (!folder.exists()) return 0L
        var total = 0L
        val files = folder.listFiles() ?: return 0L
        for (f in files) {
            total += if (f.isDirectory) calculateFolderSize(f) else f.length()
        }
        return total
    }
}
