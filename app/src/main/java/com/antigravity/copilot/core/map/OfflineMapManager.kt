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

/**
 * Unified OfflineMapManager:
 * Manages Scoped Storage (maps, downloads) on Android 11+ head units.
 * Supports 5 Macro-Regions + All-India Master with real-time GPS location auto-detection.
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
        COMPLETED,
        ERROR
    }

    enum class PackageType {
        VECTOR_MAP
    }

    data class GeoBoundingBox(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    ) {
        fun contains(lat: Double, lon: Double): Boolean {
            return lat in minLat..maxLat && lon in minLon..maxLon
        }
    }

    data class OfflinePackage(
        val id: String,
        val title: String,
        val description: String,
        val coveredStates: String = "",
        val type: PackageType,
        val estimatedSizeBytes: Long,
        val downloadUrl: String,
        val fileName: String,
        val boundingBox: GeoBoundingBox? = null,
        val isInstalled: Boolean = false,
        val installedSizeBytes: Long = 0L
    )

    data class StorageBudgetInfo(
        val totalDeviceBytes: Long = 0L,
        val freeDeviceBytes: Long = 0L,
        val offlineMapsUsedBytes: Long = 0L,
        val isLowStorageWarning: Boolean = false
    )

    /**
     * Map packages. MapLibre renders ONE local India vector file (PMTiles, OpenMapTiles schema)
     * via pmtiles://file://. Public tile servers forbid bulk download and Geofabrik does not
     * publish MBTiles, so the file is built on a PC (MAP_SETUP.md) and imported via SAF, or
     * downloaded from a URL the user sets (custom URL) with resume + free-space check.
     */
    const val INDIA_MAP_FILE = "india.pmtiles"
    val AVAILABLE_PACKAGES = listOf(
        OfflinePackage(
            id = "india_pmtiles",
            title = "India Vector Map (PMTiles)",
            description = "Local vector map (.pmtiles, OpenMapTiles schema). Import the file, or set your own download URL.",
            coveredStates = "All of India",
            type = PackageType.VECTOR_MAP,
            estimatedSizeBytes = 6L * 1024L * 1024L * 1024L, // ~6 GB for z0-14
            downloadUrl = "",
            fileName = INDIA_MAP_FILE,
            boundingBox = GeoBoundingBox(minLat = 6.0, maxLat = 37.5, minLon = 68.0, maxLon = 97.5)
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

    private val _currentGpsRegion = MutableStateFlow<OfflinePackage?>(null)
    val currentGpsRegion: StateFlow<OfflinePackage?> = _currentGpsRegion.asStateFlow()

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
        // One-time cleanup: free space held by the removed offline-routing feature
        managerScope.launch { File(context.getExternalFilesDir(null), "routing").let { if (it.exists()) it.deleteRecursively() } }
        getDownloadsDir(context).mkdirs()
        refreshPackageStatus(context)
        refreshStorageInfo(context)

        // Initialize last known GPS region
        val (lat, lon) = getLastKnownPosition(context)
        updateGpsLocation(lat, lon)
    }

    fun getMapsDir(context: Context): File = File(context.getExternalFilesDir(null), "maps")
    fun getDownloadsDir(context: Context): File = File(context.getExternalFilesDir(null), "downloads")

    /**
     * Determines which regional zone contains the given GPS coordinates.
     */
    fun getRegionForLocation(lat: Double, lon: Double): OfflinePackage? {
        // Match specific macro-regions first (North, West/Central, South, East, North-East)
        val macroRegions = AVAILABLE_PACKAGES.filter { 
            it.type == PackageType.VECTOR_MAP && it.id != "india_vector_map" && it.boundingBox != null 
        }
        for (region in macroRegions) {
            if (region.boundingBox?.contains(lat, lon) == true) {
                return region
            }
        }
        // Fallback to national
        return AVAILABLE_PACKAGES.find { it.id == "india_vector_map" }
    }

    /**
     * Updates current GPS position and recalculates detected regional zone.
     */
    fun updateGpsLocation(lat: Double, lon: Double) {
        val detected = getRegionForLocation(lat, lon)
        if (_currentGpsRegion.value?.id != detected?.id) {
            _currentGpsRegion.value = detected
            Log.i(TAG, "GPS Region updated to: ${detected?.title} for ($lat, $lon)")
        }
    }

    /**
     * Resolves the local PMTiles map file to render:
     * 1. Package file matching the GPS region (india.pmtiles).
     * 2. All-India master file.
     * 3. Any other imported .pmtiles file.
     */
    fun getBestMapFileForLocation(context: Context, lat: Double, lon: Double): File? {
        val mapsDir = getMapsDir(context)
        val regionalPkg = getRegionForLocation(lat, lon)
        if (regionalPkg != null) {
            val regionalFile = File(mapsDir, regionalPkg.fileName)
            if (regionalFile.exists() && regionalFile.length() > 0) {
                return regionalFile
            }
        }

        // Master All-India file
        val masterFile = File(mapsDir, INDIA_MAP_FILE)
        if (masterFile.exists() && masterFile.length() > 0) {
            return masterFile
        }

        // Any other imported .pmtiles file (largest first)
        return mapsDir.listFiles { _, name -> name.endsWith(".pmtiles", ignoreCase = true) }
            ?.filter { it.length() > 0 }
            ?.maxByOrNull { it.length() }
    }

    fun getMapFile(context: Context): File? {
        val (lat, lon) = getLastKnownPosition(context)
        return getBestMapFileForLocation(context, lat, lon)
    }

    /**
     * Refreshes the installation status of packages in Scoped Storage.
     */
    fun refreshPackageStatus(context: Context) {
        val updated = AVAILABLE_PACKAGES.map { pkg ->
            val target = File(getMapsDir(context), pkg.fileName)
            val installed = target.exists() && target.length() > 0
            val size = if (installed) target.length() else 0L
            pkg.copy(isInstalled = installed, installedSizeBytes = size)
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
        val downloadsSize = calculateFolderSize(getDownloadsDir(context))
        val totalOfflineUsed = mapsSize + downloadsSize

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

        val targetDest = File(getMapsDir(context), pkg.fileName).absolutePath

        val effectiveUrl = getEffectiveDownloadUrl(context, pkg)
        if (effectiveUrl.isBlank()) {
            _statusMessage.value = "Set a download URL for ${pkg.title} first (or use Import map file)"
            _downloadState.value = DownloadState.ERROR
            _activePackageId.value = null
            return
        }
        val workData = workDataOf(
            MapDownloadWorker.KEY_URL to effectiveUrl,
            MapDownloadWorker.KEY_DEST_PATH to targetDest,
            MapDownloadWorker.KEY_PACKAGE_ID to pkg.id,
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
            val destFile = File(getMapsDir(context), "${pkg.fileName}.download")
            if (destFile.exists()) destFile.delete()
            refreshStorageInfo(context)
        }
    }

    fun deletePackage(context: Context, packageId: String) {
        managerScope.launch {
            val pkg = AVAILABLE_PACKAGES.find { it.id == packageId } ?: return@launch
            val file = File(getMapsDir(context), pkg.fileName)
            ActiveMapFile.set(null)
            if (file.exists()) file.delete()
            refreshPackageStatus(context)
            refreshStorageInfo(context)
            _statusMessage.value = "Deleted ${pkg.title}"
        }
    }

    /**
     * Import PMTiles map packages from external USB drive, SD card, or Download folder.
     */
    fun importFromExternalStorage(
        context: Context,
        onProgress: (String, Float) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        managerScope.launch {
            try {
                var importedCount = 0
                val searchDirs = mutableListOf<File>()
                searchDirs.add(File("/storage/"))
                searchDirs.add(File("/mnt/media_rw/"))
                Environment.getExternalStorageDirectory()?.let { searchDirs.add(it) }
                try {
                    val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (dlDir != null) searchDirs.add(dlDir)
                } catch (_: Exception) {}

                val candidateFiles = mutableListOf<File>()
                fun scan(dir: File, depth: Int) {
                    if (depth > 2 || !dir.exists() || !dir.canRead()) return
                    val entries = dir.listFiles() ?: return
                    for (entry in entries) {
                        if (entry.isFile) {
                            val lower = entry.name.lowercase()
                            if (lower.endsWith(".pmtiles")) {
                                candidateFiles.add(entry)
                            }
                        } else if (entry.isDirectory && depth < 2) {
                            scan(entry, depth + 1)
                        }
                    }
                }

                for (base in searchDirs) {
                    scan(base, 0)
                }

                val distinctCandidates = candidateFiles.distinctBy { it.absolutePath }
                for (sf in distinctCandidates) {
                    if (sf.name.endsWith(".pmtiles", ignoreCase = true)) {
                        withContext(Dispatchers.Main) { onProgress("Importing ${sf.name}...", 0.5f) }
                        val dest = File(getMapsDir(context), sf.name)
                        copyFileWithProgress(sf, dest)
                        importedCount++
                    }
                }

                refreshPackageStatus(context)
                refreshStorageInfo(context)
                withContext(Dispatchers.Main) {
                    if (importedCount > 0) {
                        onComplete(true, "Successfully imported $importedCount offline package(s)")
                    } else {
                        onComplete(false, "No .pmtiles maps found on USB/SD card.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "USB Import failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onComplete(false, "Import error: ${e.message}")
                }
            }
        }
    }

    /**
     * Import a map file picked with the Storage Access Framework. Validates the PMTiles v3
     * header, checks free space against the file size, copies with progress to
     * maps/india.pmtiles (atomic rename) and refreshes the map.
     */
    fun importMapFile(
        context: Context,
        uri: android.net.Uri,
        onProgress: (String, Float) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        managerScope.launch {
            val resolver = context.contentResolver
            try {
                var size = -1L
                var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: INDIA_MAP_FILE
                // OEM file managers often return file:// URIs, which have no provider to query.
                if (uri.scheme == "file") {
                    uri.path?.let { File(it) }?.takeIf { it.isFile }?.let { size = it.length() }
                }
                val cursor = if (uri.scheme == "file") null else
                    try { resolver.query(uri, null, null, null, null) } catch (_: Exception) { null }
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                        if (ni >= 0) name = c.getString(ni) ?: name
                    }
                }
                val header = ByteArray(8)
                val headerRead = resolver.openInputStream(uri)?.use { it.read(header) } ?: -1
                if (headerRead < 8 || !com.antigravity.copilot.core.logic.MapOfflinePolicy.isPmtilesHeader(header)) {
                    onComplete(false, "\"$name\" is not a PMTiles v3 file. Build india.pmtiles as described in MAP_SETUP.md.")
                    return@launch
                }
                val mapsDir = getMapsDir(context).apply { mkdirs() }
                val free = StatFs(mapsDir.path).availableBytes
                val existing = File(mapsDir, INDIA_MAP_FILE).takeIf { it.exists() }?.length() ?: 0L
                if (size > 0 && size + LOW_STORAGE_WARNING_THRESHOLD_BYTES / 4 > free + existing) {
                    onComplete(false, "Not enough free space: need ${size / (1024 * 1024)} MB, free ${free / (1024 * 1024)} MB.")
                    return@launch
                }
                if (size > MAX_OFFLINE_STORAGE_BUDGET_BYTES) {
                    onComplete(false, "Map file exceeds the 15 GB offline budget.")
                    return@launch
                }
                val tmp = File(mapsDir, "$INDIA_MAP_FILE.importing")
                resolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1024 * 1024)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (copied - lastReport >= 32L * 1024 * 1024) {
                                lastReport = copied
                                val frac = if (size > 0) copied.toFloat() / size else 0f
                                onProgress("Importing $name… ${copied / (1024 * 1024)} MB", frac)
                            }
                        }
                    }
                } ?: throw java.io.IOException("Cannot open $uri")
                // Verify MapLibre can render it (PMTiles v3, gzip/none, vector tiles) before swapping.
                val problem = try { ActiveMapFile.headerProblem(tmp) } catch (e: Exception) { e.message ?: "unreadable" }
                if (problem != null) {
                    tmp.delete()
                    onComplete(false, "Map file rejected: $problem")
                    return@launch
                }
                val dest = File(mapsDir, INDIA_MAP_FILE)
                ActiveMapFile.set(null)
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) throw java.io.IOException("Rename failed")
                refreshPackageStatus(context)
                refreshStorageInfo(context)
                onComplete(true, "Imported $name (${dest.length() / (1024 * 1024)} MB)")
            } catch (e: Throwable) {
                // Throwable, not Exception: an OOM/linkage error here must not kill the app.
                File(getMapsDir(context), "$INDIA_MAP_FILE.importing").delete()
                Log.e(TAG, "Map import failed: ${e.message}", e)
                val hint = if (e is SecurityException) " — allow 'All files access' for Copilot in Settings." else ""
                onComplete(false, "Import failed: ${e.message}$hint")
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

    // Callbacks from MapDownloadWorker
    fun updateDownloadProgress(packageId: String, progress: Float, speedMbs: Double, downloaded: Long, total: Long) {
        _activePackageId.value = packageId
        _downloadState.value = DownloadState.DOWNLOADING
        _downloadProgress.value = progress
        _downloadSpeedMbs.value = speedMbs
        _downloadedBytes.value = downloaded
        _totalBytes.value = total
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

    fun clearDownloadError() {
        _downloadState.value = DownloadState.IDLE
        _activePackageId.value = null
        _statusMessage.value = "Offline storage ready"
    }

    fun getCustomDownloadUrl(packageId: String): String? {
        return prefs?.getString("custom_url_$packageId", null)
    }

    fun setCustomDownloadUrl(context: Context, packageId: String, url: String?) {
        val p = prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs = it }
        p.edit().apply {
            if (url.isNullOrBlank()) {
                remove("custom_url_$packageId")
            } else {
                putString("custom_url_$packageId", url.trim())
            }
        }.apply()
    }

    fun getEffectiveDownloadUrl(context: Context, pkg: OfflinePackage): String {
        val custom = getCustomDownloadUrl(pkg.id)
        return if (!custom.isNullOrBlank()) custom else pkg.downloadUrl
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

    // Last Known Position (for DrivingTrackingService and TripRepository)
    fun saveLastKnownPosition(context: Context, latitude: Double, longitude: Double) {
        updateGpsLocation(latitude, longitude)
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
