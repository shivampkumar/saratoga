package com.saratoga

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
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
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var transcript: TextView
    private lateinit var tau1View: TextView
    private lateinit var tau1Meta: TextView
    private lateinit var tau3View: TextView
    private lateinit var tau3Meta: TextView
    private lateinit var tau4View: TextView
    private lateinit var tau4Meta: TextView
    private lateinit var fhirView: TextView
    private lateinit var fhirBadge: TextView
    private lateinit var btnLoad: Button
    private lateinit var btnRecord: Button
    private lateinit var btnSync: Button

    private val transcriptLog = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.statusDot)
        statusLabel = findViewById(R.id.statusLabel)
        transcript = findViewById(R.id.transcript)
        tau1View = findViewById(R.id.tau1); tau1Meta = findViewById(R.id.tau1Meta)
        tau3View = findViewById(R.id.tau3); tau3Meta = findViewById(R.id.tau3Meta)
        tau4View = findViewById(R.id.tau4); tau4Meta = findViewById(R.id.tau4Meta)
        fhirView = findViewById(R.id.fhir)
        fhirBadge = findViewById(R.id.fhirBadge)
        btnLoad = findViewById(R.id.btnLoad)
        btnRecord = findViewById(R.id.btnRecord)
        btnSync = findViewById(R.id.btnSync)

        ensureMicPermission()
        setStatus("boot", ready = false)

        val weightsDir = File(getExternalFilesDir(null), "weights")
        status.text = "weights: $weightsDir"

        btnLoad.setOnClickListener {
            btnLoad.isEnabled = false
            setStatus("loading", busy = true)
            status.text = "loading ASR + embedder + E4B..."
            lifecycleScope.launch {
                try {
                    val t0 = System.currentTimeMillis()
                    asr = Asr(weightsDir)
                    withContext(Dispatchers.IO) { asr.load() }
                    saratoga = Saratoga(this@MainActivity, weightsDir)
                    saratoga.load()
                    val dt = System.currentTimeMillis() - t0
                    setStatus("ready", ready = true)
                    status.text = "loaded in ${dt}ms. press RECORD."
                    btnLoad.visibility = View.GONE
                    btnRecord.isEnabled = true
                    btnSync.isEnabled = true
                    ready = true
                    refreshSync()
                } catch (e: Throwable) {
                    setStatus("err", ready = false)
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
            val n = saratoga.syncQueueSize()
            if (n == 0) { status.text = "queue empty"; return@setOnClickListener }
            setStatus("sync", busy = true)
            status.text = "POST /Bundle × $n → HAPI stub..."
            lifecycleScope.launch {
                val (drained, ids) = saratoga.drainSyncQueue()
                status.text = "✓ synced $drained Bundle(s) → HAPI stub"
                fhirView.text = "✓ reconciled → ${ids.joinToString(", ")}"
                refreshSync()
                setStatus("ready", ready = true)
            }
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) { ensureMicPermission(); return }
        recorder.start()
        btnRecord.text = "■ STOP"
        setStatus("rec", rec = true)
        status.text = "recording..."
    }

    private fun stopAndProcess() {
        btnRecord.isEnabled = false
        btnRecord.text = "● RECORD"
        setStatus("asr", busy = true)
        status.text = "transcribing..."
        lifecycleScope.launch {
            val pcm = recorder.stop()
            val tAsr = System.currentTimeMillis()
            val text = withContext(Dispatchers.Default) { asr.transcribe(pcm, cacheDir) }
            val asrMs = System.currentTimeMillis() - tAsr
            if (text.isBlank()) {
                status.text = "(empty transcript — try again)"
                setStatus("ready", ready = true)
                btnRecord.isEnabled = true
                return@launch
            }
            transcriptLog.append("• ").append(text).append('\n')
            transcript.text = transcriptLog.toString()

            setStatus("think", busy = true)
            runOnUiThread {
                tau1View.text = "…"; tau1Meta.text = ""
                tau3View.text = "…"; tau3Meta.text = ""
                tau4View.text = "…"; tau4Meta.text = ""
            }
            status.text = "asr ${asrMs}ms · RAG gate → LLM..."
            val t0 = System.currentTimeMillis()
            val out = saratoga.handle(
                utterance = text,
                onQuick = { tau, chunkId, score, preview ->
                    runOnUiThread {
                        val (view, meta) = panes(tau)
                        meta.text = "⚡ quick [$chunkId s=${"%.2f".format(score)}]"
                        view.text = "$preview\n\n(E4B filling in...)"
                    }
                },
                onSection = { tau, soFar ->
                    runOnUiThread {
                        val (view, meta) = panes(tau)
                        meta.text = "✓ streaming"
                        view.text = soFar
                    }
                },
            )
            val dt = System.currentTimeMillis() - t0
            finalizeOutcome(out, asrMs, dt)
            btnRecord.isEnabled = true
            refreshSync()
            setStatus("ready", ready = true)
        }
    }

    private fun panes(tau: String): Pair<TextView, TextView> = when (tau) {
        "tau1" -> tau1View to tau1Meta
        "tau3" -> tau3View to tau3Meta
        else -> tau4View to tau4Meta
    }

    private fun finalizeOutcome(out: Saratoga.Outcome, asrMs: Long, dt: Long) {
        val fired = out.tasksFired
        val firedSet = fired.map { it.tau }.toSet()
        for (tau in listOf("tau1", "tau3", "tau4")) {
            val (view, meta) = panes(tau)
            val t = fired.firstOrNull { it.tau == tau }
            if (t == null && tau !in firedSet) {
                meta.text = "not triggered"
                if (view.text.isNullOrBlank() || view.text == "…") view.text = "—"
            } else if (t != null) {
                val s = t.topHit?.score ?: 0f
                meta.text = "[${t.topHit?.chunk?.id ?: "?"} s=${"%.2f".format(s)}]"
            }
        }
        status.text = "asr=${asrMs}ms  total=${dt}ms  fired=${firedSet.joinToString(",")}"
        out.fhirBundle?.let {
            fhirView.text = "⇢ queued $it"
        }
    }

    private fun refreshSync() {
        if (!::saratoga.isInitialized) return
        val n = saratoga.syncQueueSize()
        btnSync.text = "SYNC $n"
        fhirBadge.text = "$n queued"
    }

    private fun setStatus(label: String, ready: Boolean = false, rec: Boolean = false, busy: Boolean = false) {
        statusLabel.text = label
        statusDot.setBackgroundResource(
            when {
                rec -> R.drawable.dot_rec
                busy -> R.drawable.dot_busy
                ready -> R.drawable.dot_ready
                else -> R.drawable.dot_busy
            }
        )
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 42)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::saratoga.isInitialized) saratoga.close()
        if (::asr.isInitialized) asr.close()
    }
}
