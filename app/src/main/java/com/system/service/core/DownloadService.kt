package com.system.service.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.system.service.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Foreground download service — keeps downloading even if app is backgrounded.
 * Uses OkHttp for reliable connections with redirect-following.
 * Broadcasts: ACTION_PROGRESS · ACTION_COMPLETE · ACTION_ERROR
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_PROGRESS = "com.system.service.DOWNLOAD_PROGRESS"
        const val ACTION_COMPLETE = "com.system.service.DOWNLOAD_COMPLETE"
        const val ACTION_ERROR    = "com.system.service.DOWNLOAD_ERROR"

        const val EXTRA_PERCENT  = "percent"
        const val EXTRA_MB_DONE  = "mb_done"
        const val EXTRA_MB_TOTAL = "mb_total"
        const val EXTRA_SPEED    = "speed_kbs"
        const val EXTRA_ETA      = "eta_secs"
        const val EXTRA_FILE     = "file_path"
        const val EXTRA_ERROR    = "error_msg"
        const val EXTRA_URL      = "download_url"
        const val EXTRA_SHA256   = "expected_sha256"

        private const val CHANNEL_ID = "ds_download"
        private const val NOTIF_ID   = 9001
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val executor = Executors.newSingleThreadExecutor()
    private var activeJob: Future<*>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif("Starting download…", 0, indeterminate = true))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url    = intent?.getStringExtra(EXTRA_URL) ?: run { stopSelf(); return START_REDELIVER_INTENT }
        val sha256 = intent.getStringExtra(EXTRA_SHA256) ?: ""

        getSharedPreferences("dl_prefs", MODE_PRIVATE).edit()
            .putString("last_url", url).putString("last_sha256", sha256).apply()

        val existingApk = File(cacheDir, "update.apk")
        if (existingApk.exists() && existingApk.length() > 0) {
            if (sha256.isBlank() || SHA256Helper.verify(existingApk, sha256)) {
                broadcastComplete(existingApk.absolutePath)
                stopSelf(); return START_NOT_STICKY
            }
        }
        activeJob?.cancel(true)
        activeJob = executor.submit { download(url, sha256) }
        return START_REDELIVER_INTENT
    }

    private fun download(url: String, expectedSha256: String) {
        val outFile = File(cacheDir, "update.apk")
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "ChildMonitor/${DeviceHelper.getAppVersion(this)}")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { broadcastError("Server error ${resp.code}"); return }
                val body  = resp.body ?: run { broadcastError("Empty response"); return }
                val total = body.contentLength()
                var downloaded = 0L
                val t0 = System.currentTimeMillis()

                FileOutputStream(outFile).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(32 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            if (Thread.currentThread().isInterrupted) {
                                broadcastError("Download cancelled"); return
                            }
                            out.write(buf, 0, read)
                            downloaded += read

                            val ms      = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                            val speedKb = (downloaded * 1000L / (ms * 1024L)).toInt()
                            val pct     = if (total > 0) (downloaded * 100 / total).toInt() else -1
                            val mbDone  = downloaded / 1_048_576f
                            val mbTotal = total.coerceAtLeast(0L) / 1_048_576f
                            val etaSec  = if (speedKb > 0 && total > 0)
                                ((total - downloaded) / (speedKb.toLong() * 1024L)).toInt() else -1

                            broadcastProgress(pct, mbDone, mbTotal, speedKb, etaSec)
                            notify("Downloading…", pct.coerceAtLeast(0), indeterminate = pct < 0)
                        }
                    }
                }
            }

            if (expectedSha256.isNotBlank() && !SHA256Helper.verify(outFile, expectedSha256)) {
                outFile.delete()
                broadcastError("File verification failed — file may be corrupt")
                return
            }

            broadcastComplete(outFile.absolutePath)
            notify("Download complete", 100, indeterminate = false)

        } catch (e: InterruptedException) {
            broadcastError("Download cancelled")
        } catch (e: Exception) {
            broadcastError(e.message ?: "Unknown download error")
        } finally {
            stopSelf()
        }
    }

    private fun broadcastProgress(pct: Int, mbDone: Float, mbTotal: Float, speedKbs: Int, etaSecs: Int) =
        sendBroadcast(Intent(ACTION_PROGRESS).apply {
            putExtra(EXTRA_PERCENT,  pct)
            putExtra(EXTRA_MB_DONE,  mbDone)
            putExtra(EXTRA_MB_TOTAL, mbTotal)
            putExtra(EXTRA_SPEED,    speedKbs)
            putExtra(EXTRA_ETA,      etaSecs)
        })

    private fun broadcastComplete(filePath: String) =
        sendBroadcast(Intent(ACTION_COMPLETE).putExtra(EXTRA_FILE, filePath))

    private fun broadcastError(msg: String) =
        sendBroadcast(Intent(ACTION_ERROR).putExtra(EXTRA_ERROR, msg))

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Download", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows download progress"; setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String, progress: Int, indeterminate: Boolean): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ChildMonitor")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_download)
                .setProgress(100, progress, indeterminate)
                .setOngoing(true).setOnlyAlertOnce(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setContentTitle("ChildMonitor")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_download)
                .setProgress(100, progress, indeterminate)
                .setOngoing(true).build()
        }
    }

    private fun notify(text: String, progress: Int, indeterminate: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotif(text, progress, indeterminate))
    }

    override fun onDestroy() {
        activeJob?.cancel(true)
        executor.shutdown()
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
