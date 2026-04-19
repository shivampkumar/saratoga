package com.saratoga

import com.cactus.cactusDestroy
import com.cactus.cactusInit
import com.cactus.cactusTranscribe
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** Moonshine-base ASR via Cactus. PCM bytes -> WAV file -> transcribe. */
class Asr(private val weightsDir: File) {
    private var handle: Long = 0L
    private val sampleRate = 16000

    fun load() {
        val path = File(weightsDir, "moonshine-base").absolutePath
        handle = cactusInit(path, null, false)
    }

    fun transcribe(pcm: ByteArray, tmpDir: File): String {
        val wav = File(tmpDir, "rec.wav")
        pcmToWav(pcm, wav)
        val raw = cactusTranscribe(handle, wav.absolutePath, null, null, null, null)
        val result = JSONObject(raw)
        return result.optString("response").trim()
    }

    fun close() {
        if (handle != 0L) { cactusDestroy(handle); handle = 0L }
    }

    private fun pcmToWav(pcm: ByteArray, out: File) {
        val byteRate = sampleRate * 1 * 16 / 8
        val totalDataLen = pcm.size + 36
        out.outputStream().use { fos ->
            val d = DataOutputStream(fos)
            val header = ByteArrayOutputStream().apply {
                write("RIFF".toByteArray())
                write(intLE(totalDataLen))
                write("WAVE".toByteArray())
                write("fmt ".toByteArray())
                write(intLE(16))          // fmt chunk size
                write(shortLE(1))         // PCM
                write(shortLE(1))         // mono
                write(intLE(sampleRate))
                write(intLE(byteRate))
                write(shortLE(2))         // block align
                write(shortLE(16))        // bits/sample
                write("data".toByteArray())
                write(intLE(pcm.size))
            }
            d.write(header.toByteArray())
            d.write(pcm)
        }
    }

    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), (v shr 8 and 0xff).toByte(),
        (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte()
    )
    private fun shortLE(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
}
