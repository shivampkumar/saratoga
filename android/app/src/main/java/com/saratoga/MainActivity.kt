package com.saratoga

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var saratoga: Saratoga
    private lateinit var asr: Asr
    private val recorder = Recorder()
    private var ready = false

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var tau1View: TextView
    private lateinit var tau3View: TextView
    private lateinit var tau4View: TextView
    private lateinit var fhirView: TextView
    private lateinit var btnLoad: Button
    private lateinit var btnRecord: Button
    private lateinit var btnSync: Button

    private val transcriptLog = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        transcript = findViewById(R.id.transcript)
        tau1View = findViewById(R.id.tau1)
        tau3View = findViewById(R.id.tau3)
        tau4View = findViewById(R.id.tau4)
        fhirView = findViewById(R.id.fhir)
        btnLoad = findViewById(R.id.btnLoad)
        btnRecord = findViewById(R.id.btnRecord)
        btnSync = findViewById(R.id.btnSync)

        ensureMicPermission()

        val weightsDir = File(getExternalFilesDir(null), "weights")
        status.text = "weights: $weightsDir"

        btnLoad.setOnClickListener {
            btnLoad.isEnabled = false
            status.text = "loading ASR + embedder + E4B..."
            lifecycleScope.launch {
                try {
                    val t0 = System.currentTimeMillis()
                    asr = Asr(weightsDir)
                    withContext(Dispatchers.IO) { asr.load() }
                    saratoga = Saratoga(this@MainActivity, weightsDir)
                    saratoga.load()
                    val dt = System.currentTimeMillis() - t0
                    status.text = "loaded in ${dt}ms. press Record to start encounter."
                    btnRecord.isEnabled = true
                    btnSync.isEnabled = true
                    ready = true
                    refreshSync()
                } catch (e: Throwable) {
                    status.text = "LOAD FAILED: ${e.message}\n${e.stackTraceToString().take(600)}"
                    btnLoad.isEnabled = true
                }
            }
        }

        btnRecord.setOnClickListener {
            if (!ready) return@setOnClickListener
            if (recorder.isRecording()) stopAndProcess() else startRecording()
        }

        btnSync.setOnClickListener {
            if (!ready) return@setOnClickListener
            val (n, ids) = saratoga.drainSyncQueue()
            status.text = "synced $n FHIR bundle(s) -> HAPI stub"
            fhirView.text = "(drained) ids=${ids.joinToString()}"
            refreshSync()
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ensureMicPermission()
            return
        }
        recorder.start()
        btnRecord.text = "Stop"
        status.text = "recording..."
    }

    private fun stopAndProcess() {
        btnRecord.isEnabled = false
        btnRecord.text = "Record"
        status.text = "transcribing..."
        lifecycleScope.launch {
            val pcm = recorder.stop()
            val tAsr = System.currentTimeMillis()
            val text = withContext(Dispatchers.Default) {
                asr.transcribe(pcm, cacheDir)
            }
            val asrMs = System.currentTimeMillis() - tAsr
            if (text.isBlank()) {
                status.text = "(empty transcript — try again)"
                btnRecord.isEnabled = true
                return@launch
            }
            transcriptLog.append("• ").append(text).append('\n')
            transcript.text = transcriptLog.toString()

            status.text = "reasoning... (asr=${asrMs}ms)"
            val t0 = System.currentTimeMillis()
            val out = saratoga.handle(text)
            val dt = System.currentTimeMillis() - t0
            renderOutcome(out, asrMs, dt)
            btnRecord.isEnabled = true
            refreshSync()
        }
    }

    private fun renderOutcome(out: Saratoga.Outcome, asrMs: Long, pipelineMs: Long) {
        val fired = out.tasksFired
        val firedTaus = fired.map { it.tau }.toSet()
        for (tau in listOf("tau1", "tau3", "tau4")) {
            val view = when (tau) { "tau1" -> tau1View; "tau3" -> tau3View; else -> tau4View }
            val existing = view.text.toString()
            val t = fired.firstOrNull { it.tau == tau }
            if (t != null) {
                val top = t.topHit
                val head = "[${top?.chunk?.id ?: "?"} s=${"%.2f".format(top?.score ?: 0f)}]"
                view.text = "$head\n${t.card}"
            } else if (existing.isBlank()) {
                view.text = "(not triggered)"
            }
        }
        val fhirMsg = if (out.fhirBundle != null) "queued ${out.fhirBundle}" else "(no bundle)"
        status.text =
            "asr=${asrMs}ms pipe=${pipelineMs}ms  fired=${firedTaus.joinToString(",")}  fhir=$fhirMsg"
    }

    private fun refreshSync() {
        if (!::saratoga.isInitialized) return
        val n = saratoga.syncQueueSize()
        btnSync.text = "Sync FHIR ($n)"
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 42
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::saratoga.isInitialized) saratoga.close()
        if (::asr.isInitialized) asr.close()
    }
}
