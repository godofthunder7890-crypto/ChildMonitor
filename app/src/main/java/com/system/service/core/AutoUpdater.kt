package com.system.service.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object AutoUpdater {

    private const val TAG        = "AutoUpdater"
    private const val CHANNEL_ID = "auto_update"
    private const val NOTIF_ID   = 9901

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun checkAndUpdate(ctx: Context) = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch latest release via OkHttp + GitHub API (redirect-safe)
            val release = VersionChecker.fetchLatest(httpClient) ?: return@withContext

            // 2. Compare version codes
            val myCode = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    it.longVersionCode.toInt()
                else
                    @Suppress("DEPRECATION") it.versionCode
            }
            Log.d(TAG, "Local: $myCode  Remote: ${release.versionCode} (${release.version})")
            if (release.versionCode <= myCode) return@withContext

            showNotification(ctx, "Update ${release.version} found — downloading…")

            // 3. Start DownloadService (foreground, shows live progress + speed/ETA)
            //    DownloadService handles SHA-256 verification internally before broadcasting COMPLETE
            val dlIntent = Intent(ctx, DownloadService::class.java).apply {
                putExtra(DownloadService.EXTRA_URL,    release.downloadUrl)
                putExtra(DownloadService.EXTRA_SHA256, release.sha256)
            }
            val apkFile = waitForDownload(ctx, dlIntent) ?: run {
                showNotification(ctx, "Update download failed — will retry next launch")
                return@withContext
            }

            // 4. Install the SHA-256-verified APK
            showNotification(ctx, "Installing ${release.version}…")
            installApk(ctx, apkFile)

        } catch (e: Exception) {
            Log.e(TAG, "AutoUpdater error", e)
        }
    }

    /**
     * Starts DownloadService and suspends until ACTION_COMPLETE or ACTION_ERROR is broadcast.
     * Returns the downloaded File on success, null on failure or cancellation.
     */
    private suspend fun waitForDownload(ctx: Context, startIntent: Intent): File? =
        suspendCancellableCoroutine { cont ->
            var receiver: BroadcastReceiver? = null
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                    receiver = null
                    when (intent.action) {
                        DownloadService.ACTION_COMPLETE -> {
                            val path = intent.getStringExtra(DownloadService.EXTRA_FILE)
                            cont.resume(if (path != null) File(path) else null)
                        }
                        DownloadService.ACTION_ERROR -> {
                            Log.e(TAG, "Download error: ${intent.getStringExtra(DownloadService.EXTRA_ERROR)}")
                            cont.resume(null)
                        }
                        else -> cont.resume(null)
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(DownloadService.ACTION_COMPLETE)
                addAction(DownloadService.ACTION_ERROR)
            }
            @Suppress("UnspecifiedRegisterReceiverFlag")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            ctx.startForegroundService(startIntent)
            cont.invokeOnCancellation {
                try { receiver?.let { ctx.unregisterReceiver(it) } } catch (_: Exception) {}
            }
        }

    private fun installApk(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val sessionId = installer.createSession(params)
        val session   = installer.openSession(sessionId)
        FileInputStream(apk).use { input ->
            session.openWrite("update", 0, apk.length()).use { out ->
                input.copyTo(out)
                session.fsync(out)
            }
        }
        val intent = Intent("com.system.service.UPDATE_DONE").setPackage(ctx.packageName)
        val pi = android.app.PendingIntent.getBroadcast(
            ctx, sessionId, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )
        session.commit(pi.intentSender)
        session.close()
    }

    private fun showNotification(ctx: Context, msg: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App Updates", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ChildMonitor Update")
            .setContentText(msg)
            .setOngoing(false)
            .build()
        nm.notify(NOTIF_ID, n)
    }
}
