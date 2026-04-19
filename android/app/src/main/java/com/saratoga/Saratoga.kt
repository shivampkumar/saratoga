package com.saratoga

import android.content.Context
import com.cactus.CactusTokenCallback
import com.cactus.cactusComplete
import com.cactus.cactusDestroy
import com.cactus.cactusEmbed
import com.cactus.cactusInit
import com.cactus.cactusReset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Saratoga — on-device voice-driven clinical co-pilot.
 *
 * Per utterance: embed → per-τ top-k retrieval → if gate passes, fire task via E4B.
 * Tasks (MVP): τ1 differential, τ3 red flags, τ4 med reconciliation.
 * All on-device. FHIR-lite evidence log SHA-chained.
 */
class Saratoga(private val ctx: Context, private val weightsDir: File) {

    data class Chunk(val id: String, val tau: String, val text: String)
    data class Hit(val chunk: Chunk, val score: Float)
    data class TaskOutput(val tau: String, val card: String, val topHit: Hit?)
    data class Outcome(
        val tasksFired: List<TaskOutput>,
        val fhirBundle: String? = null
    )

    fun interface OnSection {
        fun update(tau: String, textSoFar: String)
    }

    fun interface OnQuick {
        fun fire(tau: String, chunkId: String, score: Float, preview: String)
    }

    private var embedHandle: Long = 0L
    private var reasonHandle: Long = 0L
    private val chunks = mutableListOf<Chunk>()
    private val vectors = mutableListOf<FloatArray>()
    private var prevHash = "GENESIS"
    private val logFile: File
    private val fhirFile: File

    // Per-τ gate thresholds (cosine).
    private val gateThresholds = mapOf(
        "tau1" to 0.42f,
        "tau3" to 0.48f,
        "tau4" to 0.48f,
    )

    private val taskTitles = mapOf(
        "tau1" to "DIFFERENTIAL + MUST-RULE-OUTS",
        "tau3" to "RED FLAG",
        "tau4" to "MEDICATION REC",
    )

    private val combinedSystem = """
        You are Saratoga, an on-device clinical co-pilot. A clinician is seeing a patient.
        Given the transcript and retrieved guideline chunks for up to three tasks, emit
        EXACTLY these three sections in this order. Use 'none' for any section with no
        relevant chunks.

        Formatting rules (strict):
        - Plain text only. NO LaTeX ($...$, \text, \ge, etc).
        - Numbers as plain digits or words (50, RR 58, SpO2 90%), no math mode.
        - Cite chunk ids in [brackets] at end of each line.
        - MUST-RULE-OUT: list only conditions the transcript actually raises suspicion for.
        - In ACTION, include specific drugs with doses when the chunk provides them.

        ## DDX
        1. <condition> — <key feature> [chunk_id]
        2. <condition> — <key feature> [chunk_id]
        3. <condition> — <key feature> [chunk_id]
        MUST-RULE-OUT:
        * <item> [chunk_id]
        * <item> [chunk_id]

        ## RED FLAG
        ALERT: <condition> [chunk_id]
        ACTION:
        * <specific drug + dose or specific step>
        * <specific drug + dose or specific step>
        * <specific drug + dose or specific step>

        ## MED REC
        MED FLAG: <drug(s)>
        RISK: <one line> [chunk_id]
        ACTION: <one line with specific dose or step>

        Under 200 words total. No preamble. No disclaimers. Stop after MED REC.
    """.trimIndent()

    init {
        val dir = File(ctx.filesDir, "evidence").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        logFile = File(dir, "session-$stamp.jsonl")
        fhirFile = File(dir, "session-$stamp.fhir-queue.jsonl")
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        val embedPath = File(weightsDir, "qwen3-embedding-0.6b").absolutePath
        val reasonPath = File(weightsDir, "gemma-4-e4b-it").absolutePath
        embedHandle = cactusInit(embedPath, null, false)
        reasonHandle = cactusInit(reasonPath, null, false)
        loadCorpus()
        buildIndex()
    }

    private fun loadCorpus() {
        chunks.clear()
        val files = listOf(
            "tau1_differential.md" to "tau1",
            "tau3_redflags.md" to "tau3",
            "tau4_medrec.md" to "tau4",
        )
        for ((f, tau) in files) {
            val text = ctx.assets.open("corpus/$f").bufferedReader().use { it.readText() }
            for (block in text.split("\n---")) {
                val b = block.trim()
                val m = Regex("^##\\s+(\\S+)", RegexOption.MULTILINE).find(b) ?: continue
                val id = m.groupValues[1].trim()
                val body = b.replaceFirst(Regex("^##\\s+\\S+\\s*\\n", RegexOption.MULTILINE), "").trim()
                if (body.isNotEmpty()) chunks.add(Chunk(id, tau, body))
            }
        }
    }

    private fun buildIndex() {
        vectors.clear()
        for (c in chunks) {
            val v = cactusEmbed(embedHandle, c.text, true)
            vectors.add(v)
        }
    }

    private fun ragTopPerTau(query: String, perTauK: Int = 3): Map<String, List<Hit>> {
        val q = cactusEmbed(embedHandle, query, true)
        val scored = chunks.indices.map { i ->
            val v = vectors[i]; var dot = 0f
            for (j in v.indices) dot += v[j] * q[j]
            Hit(chunks[i], dot)
        }
        return scored.groupBy { it.chunk.tau }
            .mapValues { (_, lst) -> lst.sortedByDescending { it.score }.take(perTauK) }
    }

    private fun composeAllStreaming(
        utterance: String,
        perTau: Map<String, List<Hit>>,
        firedTaus: Set<String>,
        onSection: OnSection?,
    ): Map<String, String> {
        val labels = mapOf(
            "tau1" to "Differential chunks",
            "tau3" to "Red-flag chunks",
            "tau4" to "Medication chunks",
        )
        val blocks = firedTaus.map { tau ->
            val hits = perTau[tau].orEmpty().take(2)
            val lines = hits.joinToString("\n") { "- [${it.chunk.id}] ${it.chunk.text.take(180)}" }
            "${labels[tau]}:\n$lines"
        }.joinToString("\n\n")
        val user = "Transcript:\n\"$utterance\"\n\n$blocks\n\nEmit the three sections."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", combinedSystem))
            .put(JSONObject().put("role", "user").put("content", user))
        val opts = JSONObject().put("max_tokens", 260).put("temperature", 0.2).toString()

        val buf = StringBuilder()
        // Strict header anchor to avoid accidental substring matches mid-stream.
        val headerRe = Regex("(?m)^##\\s+(DDX|RED FLAG|MED REC)\\b.*$")
        val cb = if (onSection != null) CactusTokenCallback { token, _ ->
            buf.append(token)
            val full = buf.toString()
            val matches = headerRe.findAll(full).toList()
            if (matches.isEmpty()) return@CactusTokenCallback
            // Update all firing tau panes with their respective slice (cleaner mid-stream).
            for ((i, m) in matches.withIndex()) {
                val t = headerToTau(m.groupValues[1]) ?: continue
                if (t !in firedTaus) continue
                val start = m.range.last + 1
                // Strip any partial trailing header fragment.
                var end = if (i + 1 < matches.size) matches[i + 1].range.first else full.length
                val tail = Regex("\\n##[^\\n]*$").find(full.substring(start, end))
                if (tail != null) end = start + tail.range.first
                val body = stripLatex(full.substring(start, end).trim())
                if (body.isNotEmpty()) onSection.update(t, body)
            }
        } else null

        cactusReset(reasonHandle)
        cactusComplete(reasonHandle, messages.toString(), opts, null, cb)
        return parseSections(buf.toString(), firedTaus)
    }

    private fun headerToTau(h: String): String? = when (h) {
        "DDX" -> "tau1"
        "RED FLAG" -> "tau3"
        "MED REC" -> "tau4"
        else -> null
    }

    private fun parseSections(text: String, firedTaus: Set<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val regex = Regex("(?m)^##\\s+(DDX|RED FLAG|MED REC)\\b.*$")
        val matches = regex.findAll(text).toList()
        for ((i, m) in matches.withIndex()) {
            val header = m.groupValues[1]
            val tau = when (header) {
                "DDX" -> "tau1"
                "RED FLAG" -> "tau3"
                "MED REC" -> "tau4"
                else -> continue
            }
            if (tau !in firedTaus) continue
            val start = m.range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            val body = stripLatex(text.substring(start, end).trim())
            if (body.isNotBlank() && !body.equals("none", ignoreCase = true)) out[tau] = body
        }
        return out
    }

    private fun stripLatex(s: String): String {
        // Remove \text{X}, $...$, \ge, \le, etc. Keep readable text.
        var t = s
        t = Regex("\\\\text\\{([^}]*)\\}").replace(t) { it.groupValues[1] }
        t = Regex("\\$([^$]*)\\$").replace(t) { it.groupValues[1] }
        t = t.replace("\\ge", "≥").replace("\\le", "≤")
            .replace("\\times", "×").replace("\\pm", "±")
            .replace("\\%", "%").replace("\\_", "_")
        t = Regex("\\\\[a-zA-Z]+\\b").replace(t, "")
        return t
    }

    suspend fun handle(
        utterance: String,
        onQuick: OnQuick? = null,
        onSection: OnSection? = null,
    ): Outcome = withContext(Dispatchers.Default) {
        appendLog("transcript", utterance)
        val perTau = ragTopPerTau(utterance)
        val firedTaus = perTau.entries
            .filter { it.value.isNotEmpty() && it.value[0].score >= (gateThresholds[it.key] ?: 0.5f) }
            .map { it.key }.toSet()
        // Stage 1 (sub-second): fire quick previews from RAG hits alone.
        for (tau in firedTaus) {
            val top = perTau[tau]!!.first()
            val preview = top.chunk.text.take(160)
            appendLog("quick:$tau", "${top.chunk.id} s=${"%.2f".format(top.score)}")
            onQuick?.fire(tau, top.chunk.id, top.score, preview)
        }
        val cards = if (firedTaus.isNotEmpty())
            composeAllStreaming(utterance, perTau, firedTaus, onSection)
        else emptyMap()
        val fired = firedTaus.mapNotNull { tau ->
            val card = cards[tau] ?: return@mapNotNull null
            val top = perTau[tau]?.firstOrNull()
            appendLog("task:$tau", card)
            TaskOutput(tau, card, top)
        }
        val bundle = if (fired.isNotEmpty()) emitFhirBundle(utterance, fired) else null
        Outcome(fired, bundle)
    }

    private fun emitFhirBundle(utterance: String, fired: List<TaskOutput>): String {
        val encId = "enc-${System.currentTimeMillis()}"
        val bundle = JSONObject()
            .put("resourceType", "Bundle")
            .put("type", "collection")
            .put("entry", JSONArray().apply {
                put(JSONObject().put("resource", JSONObject()
                    .put("resourceType", "Encounter")
                    .put("id", encId)
                    .put("status", "in-progress")
                    .put("class", "ambulatory")
                    .put("period", JSONObject()
                        .put("start", isoNow()))))
                put(JSONObject().put("resource", JSONObject()
                    .put("resourceType", "ClinicalImpression")
                    .put("encounter", JSONObject().put("reference", "Encounter/$encId"))
                    .put("description", utterance.take(400))
                    .put("finding", JSONArray().apply {
                        fired.forEach {
                            put(JSONObject()
                                .put("itemCodeableConcept", JSONObject()
                                    .put("text", "${it.tau}:${it.topHit?.chunk?.id ?: "?"}"))
                                .put("basis", it.card.take(600)))
                        }
                    })))
            })
        fhirFile.appendText(bundle.toString() + "\n")
        appendLog("fhir_queued", "enc=$encId")
        return encId
    }

    fun syncQueueSize(): Int = if (fhirFile.exists()) fhirFile.readLines().count { it.isNotBlank() } else 0

    fun drainSyncQueue(): Pair<Int, List<String>> {
        if (!fhirFile.exists()) return 0 to emptyList()
        val lines = fhirFile.readLines().filter { it.isNotBlank() }
        val bundles = mutableListOf<String>()
        lines.forEach { line ->
            try {
                val enc = JSONObject(line).getJSONArray("entry").getJSONObject(0)
                    .getJSONObject("resource").optString("id", "?")
                bundles.add(enc)
            } catch (_: Throwable) {
            }
        }
        fhirFile.delete()
        appendLog("sync_drained", "${lines.size}")
        return lines.size to bundles
    }

    private fun appendLog(kind: String, text: String) {
        val t = isoNow()
        val h = sha256(prevHash + t + text)
        val entry = JSONObject()
            .put("t_iso", t).put("kind", kind).put("text", text)
            .put("prev_hash", prevHash).put("hash", h)
        logFile.appendText(entry.toString() + "\n")
        prevHash = h
    }

    fun seal(): String {
        val manifest = JSONObject()
            .put("session_id", logFile.nameWithoutExtension)
            .put("tip_hash", prevHash)
            .put("sealed_at", isoNow())
        File(logFile.parentFile, "${logFile.nameWithoutExtension}.seal.json").writeText(manifest.toString(2))
        return prevHash
    }

    fun close() {
        if (reasonHandle != 0L) { cactusDestroy(reasonHandle); reasonHandle = 0L }
        if (embedHandle != 0L) { cactusDestroy(embedHandle); embedHandle = 0L }
    }

    fun taskTitle(tau: String): String = taskTitles[tau] ?: tau

    private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
