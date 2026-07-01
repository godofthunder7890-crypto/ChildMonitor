package com.system.service.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Checks GitHub Releases API for the latest ChildMonitor APK.
 * Returns download URL + SHA-256 hash (if published alongside the APK).
 * Must be called from a background thread.
 */
object VersionChecker {

    private const val GITHUB_API =
        "https://api.github.com/repos/godofthunder7890-crypto/ChildMonitor/releases/latest"

    data class ReleaseInfo(
        val version: String,
        val versionCode: Int,
        val downloadUrl: String,
        val sha256: String = ""
    )

    fun fetchLatest(client: OkHttpClient): ReleaseInfo? {
        return try {
            val req = Request.Builder()
                .url(GITHUB_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "GuardianEye-ChildMonitor/5.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                parseRelease(client, body)
            }
        } catch (_: Exception) { null }
    }

    private fun parseRelease(client: OkHttpClient, json: String): ReleaseInfo? {
        return try {
            val root    = JSONObject(json)
            val version = root.optString("tag_name", "")
            val code    = version.replace(Regex("[^0-9]"), "").toIntOrNull() ?: return null
            val assets  = root.optJSONArray("assets") ?: return null

            var apkUrl    = ""
            var sha256Url = ""

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name  = asset.optString("name", "")
                val url   = asset.optString("browser_download_url", "")
                when {
                    name.endsWith(".apk") && !name.endsWith(".sha256") -> apkUrl = url
                    name.endsWith(".sha256") -> sha256Url = url
                }
            }

            if (apkUrl.isEmpty()) return null
            val sha256 = if (sha256Url.isNotEmpty()) fetchText(client, sha256Url) else ""
            ReleaseInfo(version, code, apkUrl, sha256.trim())
        } catch (_: Exception) { null }
    }

    private fun fetchText(client: OkHttpClient, url: String): String {
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { it.body?.string() ?: "" }
        } catch (_: Exception) { "" }
    }
}
