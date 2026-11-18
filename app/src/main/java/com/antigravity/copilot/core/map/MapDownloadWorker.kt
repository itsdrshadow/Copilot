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
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class MapDownloadWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "MapDownloadWorker"
        const val KEY_URL = "download_url"
        const val KEY_DEST_PATH = "dest_path"
        const val KEY_PACKAGE_ID = "package_id"
        const val KEY_ESTIMATED_BYTES = "estimated_bytes"
        /** Optional expected SHA-256 (hex) of the finished file. */
        const val KEY_SHA256 = "sha256"

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
                // Nothing left to fetch: never write the 416 error body into the archive.
                response.close()
                val problem = DownloadIntegrity.verify(tempDownloadFile, 0L, inputData.getString(KEY_SHA256))
                if (problem != null) {
                    tempDownloadFile.delete()
                    OfflineMapManager.notifyDownloadError(packageId, "Map file is damaged ($problem); please download again")
                    return@withContext Result.failure()
                }
                java.nio.file.Files.move(
                    tempDownloadFile.toPath(), targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
                OfflineMapManager.notifyDownloadComplete(packageId)
                return@withContext Result.success()
            } else if (!response.isSuccessful && responseCode != 206) {
                Log.e(TAG, "Download failed with HTTP $responseCode")
                val errorDetail = when (responseCode) {
                    404 -> "HTTP 404: File not found on server URL"
                    403 -> "HTTP 403: Access forbidden by server"
                    500, 502, 503, 504 -> "HTTP $responseCode: Server temporarily unavailable"
                    else -> "HTTP $responseCode server error"
                }
                OfflineMapManager.notifyDownloadError(packageId, errorDetail)
                if (responseCode in 400..499 && responseCode != 408 && responseCode != 429) {
                    // Client errors (404/403) will never succeed on retry
                    return@withContext Result.failure()
                }
                return@withContext if (runAttemptCount < 2) Result.retry() else Result.failure()
            }

            val body = response.body
            val contentLength = body?.contentLength() ?: 0L
            val totalBytes = if (responseCode == 206) {
                downloadedBytes + contentLength
            } else {
                if (contentLength > 0) contentLength else estimatedTotalBytes
            }

            // Free-space check against the real remaining size (plus a safety margin).
            val remaining = (totalBytes - (if (responseCode == 206) downloadedBytes else 0L)).coerceAtLeast(0L)
            val freeBytes = android.os.StatFs((tempDownloadFile.parentFile ?: tempDownloadFile).path).availableBytes
            if (remaining > 0 && remaining + 512L * 1024 * 1024 > freeBytes) {
                response.close()
                OfflineMapManager.notifyDownloadError(packageId, "Not enough free space: need ${remaining / (1024 * 1024)} MB more")
                return@withContext Result.failure()
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

            // Verify before it replaces the working map (audit J2): byte count, PMTiles sections,
            // SHA-256 (input or a "<url>.sha256" sidecar when the server has one).
            val expectedSha = inputData.getString(KEY_SHA256) ?: fetchSidecarSha256(downloadUrl)
            val problem = DownloadIntegrity.verify(tempDownloadFile, if (totalBytes > 0) totalBytes else 0L, expectedSha)
            if (problem != null) {
                Log.e(TAG, "Downloaded map rejected: $problem")
                if (problem.startsWith("incomplete")) {
                    // Keep the partial file: the next attempt resumes with a Range request.
                    OfflineMapManager.notifyDownloadError(packageId, "Download interrupted ($problem); retrying")
                    return@withContext if (runAttemptCount < 5) Result.retry() else Result.failure()
                }
                tempDownloadFile.delete()
                OfflineMapManager.notifyDownloadError(packageId, "Map file is damaged ($problem); please download again")
                return@withContext Result.failure()
            }

            // Atomic replace: the old map stays usable until the verified file takes its place.
            java.nio.file.Files.move(
                tempDownloadFile.toPath(), targetFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
            Log.i(TAG, "Vector map file moved to ${targetFile.absolutePath}")

            OfflineMapManager.notifyDownloadComplete(packageId)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Download exception for $packageId: ${e.message}", e)
            outputRaf?.close()
            val friendlyError = when {
                e is java.net.UnknownHostException -> "No internet connection (Cannot resolve server host)"
                e is java.net.SocketTimeoutException -> "Download timed out (Slow or interrupted network)"
                else -> e.message ?: "Download connection error"
            }
            OfflineMapManager.notifyDownloadError(packageId, friendlyError)
            if (isStopped) Result.success() else if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private fun fetchSidecarSha256(url: String): String? = try {
        okHttpClient.newCall(Request.Builder().url("$url.sha256").build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string()?.let { DownloadIntegrity.parseSha256Sidecar(it) } else null
        }
    } catch (_: Exception) {
        null
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
                description = "Background offline map pack downloads"
            }
            manager.createNotificationChannel(channel)
        }
    }
}
