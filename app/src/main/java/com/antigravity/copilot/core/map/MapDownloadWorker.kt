package com.antigravity.copilot.core.map

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.antigravity.copilot.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class MapDownloadWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "MapDownloadWorker"
        const val KEY_URL = "download_url"
        const val KEY_DEST_PATH = "dest_path"
        const val KEY_PACKAGE_ID = "package_id"
        const val KEY_PACKAGE_TYPE = "package_type" // "MAP" or "ROUTING"
        const val KEY_ESTIMATED_BYTES = "estimated_bytes"

        const val PROGRESS_KEY = "progress"
        const val SPEED_KEY = "speed_mbs"
        const val STATUS_KEY = "status"

        private const val NOTIFICATION_CHANNEL_ID = "map_offline_download_channel"
        private const val NOTIFICATION_ID = 9021
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadUrl = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val destPath = inputData.getString(KEY_DEST_PATH) ?: return@withContext Result.failure()
        val packageId = inputData.getString(KEY_PACKAGE_ID) ?: "package"
        val packageType = inputData.getString(KEY_PACKAGE_TYPE) ?: "MAP"
        val estimatedTotalBytes = inputData.getLong(KEY_ESTIMATED_BYTES, 0L)

        val targetFile = File(destPath)
        val tempDownloadFile = File("${destPath}.download")
        tempDownloadFile.parentFile?.mkdirs()

        try {
            setForeground(createForegroundInfo(packageId, 0, 0.0))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to run as foreground service: ${e.message}")
        }

        var downloadedBytes = if (tempDownloadFile.exists()) tempDownloadFile.length() else 0L
        Log.i(TAG, "Starting download for $packageId from offset: $downloadedBytes bytes")

        val requestBuilder = Request.Builder().url(downloadUrl)
        if (downloadedBytes > 0) {
            requestBuilder.header("Range", "bytes=$downloadedBytes-")
        }

        var outputRaf: RandomAccessFile? = null
        try {
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val responseCode = response.code
            Log.i(TAG, "HTTP Response Code: $responseCode for $packageId")

            if (responseCode == 416) {
                // Range Not Satisfiable: File may already be fully downloaded
                Log.i(TAG, "HTTP 416 Range Not Satisfiable - checking if file complete")
            } else if (!response.isSuccessful && responseCode != 206) {
                Log.e(TAG, "Download failed with HTTP $responseCode")
                OfflineMapManager.notifyDownloadError(packageId, "HTTP $responseCode")
                return@withContext Result.retry()
            }

            val body = response.body
            val contentLength = body?.contentLength() ?: 0L
            val totalBytes = if (responseCode == 206) {
                downloadedBytes + contentLength
            } else {
                if (contentLength > 0) contentLength else estimatedTotalBytes
            }

            outputRaf = RandomAccessFile(tempDownloadFile, "rw")
            if (responseCode == 206) {
                outputRaf.seek(downloadedBytes)
            } else {
                outputRaf.seek(0)
                downloadedBytes = 0L
            }

            val inputStream = body?.byteStream()
            val buffer = ByteArray(64 * 1024) // 64 KB buffer for high I/O throughput
            var bytesRead: Int
            var lastProgressTime = System.currentTimeMillis()
            var bytesSinceLastCalc = 0L
            var currentSpeedMbs = 0.0

            if (inputStream != null) {
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (isStopped) {
                        Log.i(TAG, "Download interrupted/paused by WorkManager: $packageId")
                        outputRaf.close()
                        OfflineMapManager.notifyDownloadPaused(packageId)
                        return@withContext Result.success()
                    }

                    outputRaf.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    bytesSinceLastCalc += bytesRead

                    val now = System.currentTimeMillis()
                    val timeDelta = now - lastProgressTime
                    if (timeDelta >= 1000) {
                        currentSpeedMbs = (bytesSinceLastCalc.toDouble() / (1024.0 * 1024.0)) / (timeDelta.toDouble() / 1000.0)
                        lastProgressTime = now
                        bytesSinceLastCalc = 0L

                        val progressFraction = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                        setProgress(
                            workDataOf(
                                PROGRESS_KEY to progressFraction,
                                SPEED_KEY to currentSpeedMbs,
                                STATUS_KEY to "DOWNLOADING"
                            )
                        )
                        OfflineMapManager.updateDownloadProgress(packageId, progressFraction, currentSpeedMbs, downloadedBytes, totalBytes)
                        try {
                            setForeground(createForegroundInfo(packageId, (progressFraction * 100).toInt(), currentSpeedMbs))
                        } catch (_: Exception) {}
                    }
                }
            }

            outputRaf.close()
            outputRaf = null

            // File download complete: Handle Auto-Extraction for Routing or Move for Map
            if (packageType == "ROUTING") {
                Log.i(TAG, "Auto-extracting routing archive for $packageId...")
                OfflineMapManager.notifyExtracting(packageId)
                val routingDestDir = File(appContext.getExternalFilesDir("routing"), "india")
                routingDestDir.mkdirs()

                extractZipArchive(tempDownloadFile, routingDestDir)
                // Delete temporary zip file to strictly respect the 15 GB storage budget
                if (tempDownloadFile.exists()) {
                    tempDownloadFile.delete()
                    Log.i(TAG, "Deleted temp routing archive to conserve storage budget")
                }
            } else {
                // Vector Map (.mbtiles)
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                tempDownloadFile.renameTo(targetFile)
                Log.i(TAG, "Vector map file moved to ${targetFile.absolutePath}")
            }

            OfflineMapManager.notifyDownloadComplete(packageId)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Download exception for $packageId: ${e.message}", e)
            outputRaf?.close()
            OfflineMapManager.notifyDownloadError(packageId, e.message ?: "Download error")
            if (isStopped) Result.success() else Result.retry()
        }
    }

    private fun extractZipArchive(zipFile: File, outputDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(64 * 1024)
            while (entry != null) {
                val newFile = File(outputDir, entry.name)
                // Protect against Zip Slip vulnerability
                if (!newFile.canonicalPath.startsWith(outputDir.canonicalPath)) {
                    throw SecurityException("Zip entry is outside of target dir: ${entry.name}")
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

    private fun createForegroundInfo(packageId: String, percent: Int, speedMbs: Double): ForegroundInfo {
        createNotificationChannel()
        val speedStr = String.format("%.1f MB/s", speedMbs)
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Offline Maps Download")
            .setContentText("Downloading $packageId: $percent% ($speedStr)")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Offline Map Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background offline map pack and routing data downloads"
            }
            manager.createNotificationChannel(channel)
        }
    }
}
