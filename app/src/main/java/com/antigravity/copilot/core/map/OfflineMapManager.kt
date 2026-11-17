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
        EXTRACTING,
        COMPLETED,
        ERROR
    }

    enum class PackageType {
        VECTOR_MAP,
        ROUTING_GRAPH
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

    // 5 Macro-Regions + All-India National Packages
    val AVAILABLE_PACKAGES = listOf(
        OfflinePackage(
            id = "india_north",
            title = "North India Zone",
            description = "High-speed regional vector map (.mbtiles)",
            coveredStates = "Delhi-NCR, Punjab, Haryana, Rajasthan, HP, J&K, Ladakh, Chandigarh, Uttarakhand",
            type = PackageType.VECTOR_MAP,
            estimatedSizeBytes = 1200L * 1024L * 1024L, // ~1.2 GB
            downloadUrl = "https://download.geofabrik.de/asia/india/north-zone-latest.mbtiles",
            fileName = "india_north.mbtiles",
            boundingBox = GeoBoundingBox(minLat = 23.5, maxLat = 37.1, minLon = 68.5, maxLon = 81.5)
        ),
        OfflinePackage(
            id = "india_west_central",
            title = "West & Central India Zone",
            description = "High-speed regional vector map (.mbtiles)",
            coveredStates = "Maharashtra, Gujarat, Madhya Pradesh, Chhattisgarh, Goa",
            type = PackageType.VECTOR_MAP,
            estimatedSizeBytes = 1400L * 1024L * 1024L, // ~1.4 GB
            downloadUrl = "https://download.geofabrik.de/asia/india/west-central-zone-latest.mbtiles",
            fileName = "india_west_central.mbtiles",
            boundingBox = GeoBoundingBox(minLat = 15.0, maxLat = 26.9, minLon = 68.1, maxLon = 84.4)
        ),
        OfflinePackage(
            id = "india_south",
            title = "South India Zone",
            description = "High-speed regional vector map (.mbtiles)",
            coveredStates = "Karnataka, Tamil Nadu, Kerala, Andhra Pradesh, Telangana, Puducherry",
            type = PackageType.VECTOR_MAP,
            estimatedSizeBytes = 1300L * 1024L * 1024L, // ~1.3 GB
            downloadUrl = "https://download.geofabrik.de/asia/india/south-zone-latest.mbtiles",
            fileName = "india_south.mbtiles",
            boundingBox = GeoBoundingBox(minLat = 8.0, maxLat = 19.9, minLon = 74.0, maxLon = 84.8)
        ),
        OfflinePackage(
            id = "india_east",
            title = "East India Zone",
            description = "High-speed regional vector map (.mbtiles)",
            coveredStates = "West Bengal, Bihar, Jharkhand, Odisha",
            type = PackageType.VECTOR_MAP,
            estimatedSizeBytes = 1100L * 1024L * 1024L, // ~1.1 GB
            downloadUrl = "https://download.geofabrik.de/asia/india/east-zone-latest.mbtiles",
            fileName = "india_east.mbtiles",
            boundingBox = GeoBoundingBox(minLat = 17.8, maxLat = 27.5, minLon = 81.3, maxLon = 89.9)
        ),
        OfflinePackage(
            id = "india_northeast",
            title = "North-East India Zone",
            description = "High-speed regional vector map (.mbtiles)",
            coveredStates = "Assam, Meghalaya, Arunachal Pradesh, Manipur, Mizoram, Nagaland, Tripura, Sikkim",
            type = PackageType.VECTOR_MAP,
            estimatedSizeBytes = 500L * 1024L * 1024L, // ~500 MB
            downloadUrl = "https://download.geofabrik.de/asia/india/northeast-zone-latest.mbtiles",
            fileName = "india_northeast.mbtiles",
            boundingBox = GeoBoundingBox(minLat = 21.9, maxLat = 29.5, minLon = 87.9, maxLon = 97.4)
        ),
        OfflinePackage(
            id = "india_vector_map",
            title = "All-India Master Vector Map",
            description = "Complete nationwide arterial highways & all states (.mbtiles)",
            coveredStates = "All 28 States & 8 Union Territories Nationwide",
            type = PackageType.VECTOR_MAP,
            estimatedSizeBytes = 4L * 1024L * 1024L * 1024L, // ~4.0 GB
            downloadUrl = "https://download.geofabrik.de/asia/india-latest.mbtiles",
            fileName = "india.mbtiles",
            boundingBox = GeoBoundingBox(minLat = 6.5, maxLat = 37.5, minLon = 68.0, maxLon = 97.5)
        ),
        OfflinePackage(
            id = "india_routing_graph",
            title = "All-India Routing Graph",
            description = "Offline turn-by-turn navigation data for GraphHopper Core (.zip)",
            coveredStates = "Nationwide Interconnected Road Graph",
            type = PackageType.ROUTING_GRAPH,
            estimatedSizeBytes = 4500L * 1024L * 1024L, // ~4.5 GB
            downloadUrl = "https://graphhopper.com/public/maps/india-gh.zip",
            fileName = "india-gh.zip"
        ),
        OfflinePackage(
            id = "demo_sample_map",
            title = "Demo / Quick Test Vector Map",
            description = "Lightweight test vector map pack (.mbtiles) for instant testing",
            coveredStates = "OpenMapTiles Standard Test Geometry (550 KB)",
            type = PackageType.VECTOR_MAP,
            estimatedSizeBytes = 561152L, // 550 KB
            downloadUrl = "https://raw.githubusercontent.com/mapbox/node-mbtiles/master/test/fixtures/plain_1.mbtiles",
            fileName = "demo_sample.mbtiles",
            boundingBox = null
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
        getRoutingDir(context).mkdirs()
        getDownloadsDir(context).mkdirs()
        refreshPackageStatus(context)
        refreshStorageInfo(context)

        // Initialize last known GPS region
        val (lat, lon) = getLastKnownPosition(context)
        updateGpsLocation(lat, lon)
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
     * Resolves the best available local .mbtiles map file for the given location:
     * 1. Specific regional file if installed (e.g. india_north.mbtiles).
     * 2. All-India master file if installed (india.mbtiles).
     * 3. Any installed .mbtiles file as fallback.
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

        // Master All-India fallback
        val masterFile = File(mapsDir, "india.mbtiles")
        if (masterFile.exists() && masterFile.length() > 0) {
            return masterFile
        }

        // Return any valid installed .mbtiles file
        return mapsDir.listFiles { _, name -> name.endsWith(".mbtiles") }?.firstOrNull()
    }

    fun getMapFile(context: Context): File? {
        val (lat, lon) = getLastKnownPosition(context)
        return getBestMapFileForLocation(context, lat, lon)
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
                val plain1 = File(getMapsDir(context), "plain_1.mbtiles")
                val installed = (target.exists() && target.length() > 0) || (pkg.id == "demo_sample_map" && plain1.exists() && plain1.length() > 0)
                val size = if (target.exists() && target.length() > 0) target.length() else if (plain1.exists()) plain1.length() else 0L
                pkg.copy(isInstalled = installed, installedSizeBytes = size)
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

        val effectiveUrl = getEffectiveDownloadUrl(context, pkg)
        val workData = workDataOf(
            MapDownloadWorker.KEY_URL to effectiveUrl,
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
     * Import map and routing packages from external USB drive, SD card, or Download folder.
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
                            if (lower.endsWith(".mbtiles") || (lower.contains("routing") && lower.endsWith(".zip"))) {
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
                    if (sf.name.endsWith(".mbtiles", ignoreCase = true)) {
                        withContext(Dispatchers.Main) { onProgress("Importing ${sf.name}...", 0.5f) }
                        val dest = File(getMapsDir(context), sf.name)
                        copyFileWithProgress(sf, dest)
                        importedCount++

                        // If plain_1.mbtiles was downloaded from browser, also map it to demo_sample.mbtiles
                        if (sf.name.equals("plain_1.mbtiles", ignoreCase = true)) {
                            val demoDest = File(getMapsDir(context), "demo_sample.mbtiles")
                            if (!demoDest.exists()) {
                                copyFileWithProgress(sf, demoDest)
                            }
                        }
                    } else if (sf.name.contains("routing", ignoreCase = true) && sf.name.endsWith(".zip", ignoreCase = true)) {
                        withContext(Dispatchers.Main) { onProgress("Extracting ${sf.name}...", 0.7f) }
                        val destDir = File(getRoutingDir(context), "india")
                        destDir.mkdirs()
                        extractZipDirect(sf, destDir)
                        importedCount++
                    }
                }

                refreshPackageStatus(context)
                refreshStorageInfo(context)
                withContext(Dispatchers.Main) {
                    if (importedCount > 0) {
                        onComplete(true, "Successfully imported $importedCount offline package(s)")
                    } else {
                        onComplete(false, "No .mbtiles or routing archives found on USB/SD card.")
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

    fun getOfflineCacheSizeBytes(context: Context): Long {
        return calculateFolderSize(getMapsDir(context)) + calculateFolderSize(getRoutingDir(context))
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
