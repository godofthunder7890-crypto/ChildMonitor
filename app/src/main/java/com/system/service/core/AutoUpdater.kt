package com.system.service.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

object AutoUpdater {

    private const val TAG = "AutoUpdater"
    private const val GITHUB_REPO = "godofthunder7890-crypto/ChildMonitor"
    private const val CHANNEL_ID  = "auto_update"
    private const val NOTIF_ID    = 9901

    suspend fun checkAndUpdate(ctx: Context) = withContext(Dispatchers.IO) {
        try {
            val latest = fetchLatestRelease() ?: return@withContext
            val latestCode = latest.optInt("version_code", -1)
            val apkUrl     = latest.optString("apk_url", "")
            if (latestCode <= 0 || apkUrl.isEmpty()) return@withContext

            val myCode = ctx.packageManager
                .getPackageInfo(ctx.packageName, 0)
                .let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode }

            Log.d(TAG, "Local: $myCode  Remote: $latestCode")
            if (latestCode <= myCode) return@withContext

            showNotification(ctx, "Update found — downloading…")
            val apkFile = downloadApk(ctx, apkUrl) ?: return@withContext
            // BUG FIX: APK was installed without any integrity check — a MITM or
            // a corrupted download could silently install a bad APK. Verify SHA-256
            // from the release JSON before passing to PackageInstaller.
            val expectedSha256 = latest.optString("sha256", "")
            if (expectedSha256.isNotBlank()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val actual = apkFile.inputStream().buffered(65536).use { s ->
                    val buf = ByteArray(65536); var r: Int
                    while (s.read(buf).also { r = it } != -1) digest.update(buf, 0, r)
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                    apkFile.delete()
                    Log.e(TAG, "SHA-256 mismatch — aborting update (expected=$expectedSha256 actual=$actual)")
                    return@withContext
                }
            }
            showNotification(ctx, "Installing update…")
            installApk(ctx, apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "AutoUpdater error", e)
        }
    }

    private fun fetchLatestRelease(): JSONObject? {
        val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "ChildMonitor-Updater")
        conn.connectTimeout = 15_000
        conn.readTimeout    = 15_000
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            val rel  = JSONObject(body)
            val tag  = rel.optString("tag_name", "")
            val code = tag.replace(Regex("[^0-9]"), "").toIntOrNull() ?: return null
            val assets = rel.optJSONArray("assets") ?: return null
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isEmpty()) return null
            JSONObject().apply {
                put("version_code", code)
                put("apk_url", apkUrl)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadApk(ctx: Context, urlStr: String): File? {
        val file = File(ctx.cacheDir, "update.apk")
        val url  = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout    = 120_000
        return try {
            if (conn.responseCode != 200) return null
            conn.inputStream.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            }
            file
        } finally {
            conn.disconnect()
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
