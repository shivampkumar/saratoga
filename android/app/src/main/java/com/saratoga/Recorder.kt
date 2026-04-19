package com.saratoga

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/** 16 kHz mono PCM16 capture. Start -> stop returns raw PCM bytes. */
class Recorder {
    private val sampleRate = 16000
    private val channel = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT
    private val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)

    private var recorder: AudioRecord? = null
    private var job: Job? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile private var recording = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (recording) return
        buffer.reset()
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate, channel, encoding, minBuf * 4
        )
        recorder?.startRecording()
        recording = true
        job = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(minBuf)
            while (recording) {
                val n = recorder?.read(buf, 0, buf.size) ?: 0
                if (n > 0) synchronized(buffer) { buffer.write(buf, 0, n) }
            }
        }
    }

    suspend fun stop(): ByteArray {
        recording = false
        job?.join()
        recorder?.stop()
        recorder?.release()
        recorder = null
        return synchronized(buffer) { buffer.toByteArray() }
    }

    fun isRecording(): Boolean = recording
}
