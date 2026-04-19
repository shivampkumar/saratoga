package com.saratoga

import android.content.Context
import com.cactus.cactusComplete
import com.cactus.cactusDestroy
import com.cactus.cactusEmbed
import com.cactus.cactusInit
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

    private val taskSystems = mapOf(
        "tau1" to (
            "You are Saratoga, an on-device clinical co-pilot. Based on the clinician/patient " +
                "transcript and retrieved guideline chunks, produce a ranked DIFFERENTIAL with " +
                "3-5 must-rule-out items. Format exactly:\n" +
                "DDX:\n1. <condition> — <key feature>\n2. ...\n" +
                "MUST-RULE-OUT: <bulleted top 3>\n" +
                "Cite chunk ids in brackets. Under 120 words."
        ),
        "tau3" to (
            "You are Saratoga. A red-flag condition is suspected from the transcript and guideline " +
                "chunks. Emit ONE concise alert in this format:\n" +
                "ALERT: <condition>\n" +
                "ACTION: <3 bullet actions, guideline-directed>\n" +
                "Cite chunk id. Under 80 words."
        ),
        "tau4" to (
            "You are Saratoga. Given the transcript and medication guideline chunks, flag ONE " +
                "most important interaction / Beers / renal / missing-therapy issue. Format:\n" +
                "MED FLAG: <drug(s)>\n" +
                "RISK: <one line>\n" +
                "ACTION: <one line>\n" +
                "Cite chunk id. Under 60 words."
        ),
    )

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

    private fun composeTask(
        tau: String,
        utterance: String,
        hits: List<Hit>,
    ): String {
        val cards = hits.take(3).joinToString("\n") {
            "- [${it.chunk.id}] ${it.chunk.text.take(240)}"
        }
        val system = taskSystems[tau]
            ?: return "(unknown task $tau)"
        val user = "Transcript so far:\n\"$utterance\"\n\nGuideline chunks:\n$cards\n\nEmit one response."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val opts = JSONObject().put("max_tokens", 220).put("temperature", 0.2).toString()
        val raw = cactusComplete(reasonHandle, messages.toString(), opts, null, null)
        return JSONObject(raw).optString("response").trim()
    }

    suspend fun handle(utterance: String): Outcome = withContext(Dispatchers.Default) {
        appendLog("transcript", utterance)
        val perTau = ragTopPerTau(utterance)
        val fired = mutableListOf<TaskOutput>()
        for ((tau, hits) in perTau) {
            val top = hits.firstOrNull() ?: continue
            val gate = gateThresholds[tau] ?: 0.5f
            if (top.score < gate) continue
            val card = composeTask(tau, utterance, hits)
            appendLog("task:$tau", card)
            fired.add(TaskOutput(tau, card, top))
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
