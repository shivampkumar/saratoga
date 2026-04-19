package com.saratoga

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Html
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var saratoga: Saratoga
    private lateinit var asr: Asr
    private val recorder = Recorder()
    private var ready = false
    private var activeSpeaker: String? = null

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
    private lateinit var fhirPreview: TextView
    private lateinit var fhirSync: TextView
    private lateinit var fhirBadge: TextView
    private lateinit var btnLoad: Button
    private lateinit var btnClinician: Button
    private lateinit var btnPatient: Button
    private lateinit var btnEnd: Button
    private lateinit var btnSync: Button

    private val encounterHtml = StringBuilder()
    private val rollingUtterances = StringBuilder()

    // Minimum characters to run τ inference — avoid over-firing on short utterances.
    private val minEncounterChars = 30

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
        fhirPreview = findViewById(R.id.fhirPreview)
        fhirSync = findViewById(R.id.fhirSync)
        fhirBadge = findViewById(R.id.fhirBadge)
        btnLoad = findViewById(R.id.btnLoad)
        btnClinician = findViewById(R.id.btnClinician)
        btnPatient = findViewById(R.id.btnPatient)
        btnEnd = findViewById(R.id.btnEnd)
        btnSync = findViewById(R.id.btnSync)

        ensureMicPermission()
        setStatus("boot", ready = false)

        val weightsDir = File(getExternalFilesDir(null), "weights")

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
                    status.text = "loaded in ${dt}ms · tap CLINICIAN / PATIENT to record turns"
                    btnLoad.visibility = View.GONE
                    btnClinician.isEnabled = true
                    btnPatient.isEnabled = true
                    btnEnd.isEnabled = true
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

        btnClinician.setOnClickListener { onSpeakerTap("CLINICIAN") }
        btnPatient.setOnClickListener { onSpeakerTap("PATIENT") }
        btnEnd.setOnClickListener { endEncounter() }

        btnSync.setOnClickListener {
            if (!ready) return@setOnClickListener
            val n = saratoga.syncQueueSize()
            if (n == 0) { status.text = "queue empty"; return@setOnClickListener }
            setStatus("sync", busy = true)
            status.text = "POST /Bundle × $n → HAPI stub..."
            fhirSync.text = ""
            lifecycleScope.launch {
                animateSync(n)
                val (drained, ids) = saratoga.drainSyncQueue()
                status.text = "✓ synced $drained Bundle(s) → HAPI stub"
                fhirSync.text = "✓ 200 OK · reconciled ${ids.joinToString(", ")}"
                refreshSync()
                setStatus("ready", ready = true)
            }
        }
    }

    // Speaker tap: if not recording, start recording for this speaker.
    // If already recording the SAME speaker, finish that turn.
    // If recording a DIFFERENT speaker, finish that turn then start this one.
    private fun onSpeakerTap(speaker: String) {
        if (!ready) return
        if (recorder.isRecording()) {
            val prev = activeSpeaker ?: speaker
            finishTurn(prev) { nextSpeaker ->
                if (speaker != prev) startTurn(speaker)
                else setStatus("ready", ready = true)
            }
            if (speaker == prev) {
                // pure stop — don't auto-restart
                return
            }
        } else {
            startTurn(speaker)
        }
    }

    private fun startTurn(speaker: String) {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) { ensureMicPermission(); return }
        recorder.start()
        activeSpeaker = speaker
        updateButtonStates(recordingSpeaker = speaker)
        setStatus("rec", rec = true)
        status.text = "recording · $speaker ..."
    }

    private fun finishTurn(speaker: String, onDone: ((String?) -> Unit)? = null) {
        val savedSpeaker = speaker
        setStatus("asr", busy = true)
        status.text = "transcribing $savedSpeaker ..."
        lifecycleScope.launch {
            val pcm = recorder.stop()
            activeSpeaker = null
            updateButtonStates(recordingSpeaker = null)
            val text = withContext(Dispatchers.Default) { asr.transcribe(pcm, cacheDir) }
            if (text.isNotBlank()) {
                val color = if (savedSpeaker == "CLINICIAN") "#60A5FA" else "#F59E0B"
                encounterHtml.append(
                    "<b><font color=\"$color\">$savedSpeaker:</font></b> $text<br/>"
                )
                transcript.text = Html.fromHtml(encounterHtml.toString(), Html.FROM_HTML_MODE_LEGACY)
                rollingUtterances.append(savedSpeaker.lowercase()).append(": ").append(text).append('\n')
                status.text = "captured $savedSpeaker turn · ${text.length} chars"
                setStatus("ready", ready = true)
            } else {
                status.text = "($savedSpeaker: empty transcript)"
                setStatus("ready", ready = true)
            }
            onDone?.invoke(savedSpeaker)
        }
    }

    private fun endEncounter() {
        if (!ready) return
        if (recorder.isRecording()) {
            // stop active turn first, then end
            finishTurn(activeSpeaker ?: "CLINICIAN") { _ -> runInference() }
            return
        }
        runInference()
    }

    private fun runInference() {
        val full = rollingUtterances.toString().trim()
        if (full.length < minEncounterChars) {
            status.text = "encounter too short (<${minEncounterChars} chars) — speak more before ending"
            return
        }
        btnClinician.isEnabled = false
        btnPatient.isEnabled = false
        btnEnd.isEnabled = false
        setStatus("think", busy = true)
        runOnUiThread {
            tau1View.text = "…"; tau1Meta.text = ""
            tau3View.text = "…"; tau3Meta.text = ""
            tau4View.text = "…"; tau4Meta.text = ""
        }
        status.text = "RAG gate + LLM on full encounter ..."
        val t0 = System.currentTimeMillis()
        lifecycleScope.launch {
            val out = saratoga.handle(
                utterance = full,
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
            finalizeOutcome(out, dt)
            btnClinician.isEnabled = true
            btnPatient.isEnabled = true
            btnEnd.isEnabled = true
            refreshSync()
            setStatus("ready", ready = true)
        }
    }

    private fun updateButtonStates(recordingSpeaker: String?) {
        if (recordingSpeaker == null) {
            btnClinician.text = "● CLINICIAN"
            btnPatient.text = "● PATIENT"
            btnClinician.isEnabled = ready
            btnPatient.isEnabled = ready
            return
        }
        if (recordingSpeaker == "CLINICIAN") {
            btnClinician.text = "■ STOP CLINICIAN"
            btnPatient.text = "→ SWITCH TO PATIENT"
            btnClinician.isEnabled = true
            btnPatient.isEnabled = true
        } else {
            btnClinician.text = "→ SWITCH TO CLINICIAN"
            btnPatient.text = "■ STOP PATIENT"
            btnClinician.isEnabled = true
            btnPatient.isEnabled = true
        }
    }

    private fun panes(tau: String): Pair<TextView, TextView> = when (tau) {
        "tau1" -> tau1View to tau1Meta
        "tau3" -> tau3View to tau3Meta
        else -> tau4View to tau4Meta
    }

    private fun finalizeOutcome(out: Saratoga.Outcome, dt: Long) {
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
                if (t.card.isNotBlank()) view.text = t.card
            }
        }
        status.text = "total=${dt}ms  fired=${firedSet.joinToString(",")}"
        out.fhirBundle?.let {
            fhirView.text = "⇢ queued encounter $it"
            val preview = saratoga.latestBundlePreview() ?: ""
            fhirPreview.text = preview
        }
    }

    private fun refreshSync() {
        if (!::saratoga.isInitialized) return
        val n = saratoga.syncQueueSize()
        btnSync.text = "SYNC FHIR $n"
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

    private suspend fun animateSync(n: Int) {
        val steps = listOf(
            "→ opening TLS tunnel to HAPI FHIR endpoint",
            "→ POST /fhir/Bundle (n=$n)",
            "→ server: 201 Created",
            "→ reconciling Encounter.period.end",
            "→ drained queue"
        )
        for (s in steps) {
            fhirSync.text = (fhirSync.text.toString() + s + "\n").trim()
            delay(140)
        }
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
