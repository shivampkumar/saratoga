import Foundation
import cactus

/// Saratoga clinical co-pilot engine.
final class Saratoga {

    struct Chunk {
        let id: String
        let tau: String
        let text: String
    }
    struct Hit {
        let chunk: Chunk
        let score: Float
    }
    struct TaskOutput {
        let tau: String
        let card: String
        let topHit: Hit?
    }
    struct Outcome {
        let tasksFired: [TaskOutput]
        let fhirBundle: String?
    }

    private var embed: CactusModelT?
    private var reason: CactusModelT?
    private var chunks: [Chunk] = []
    private var vectors: [[Float]] = []
    private var prevHash = "GENESIS"
    private let logURL: URL
    private let fhirURL: URL

    private let gate: [String: Float] = ["tau1": 0.42, "tau3": 0.48, "tau4": 0.48]
    private let labels: [String: String] = [
        "tau1": "Differential chunks",
        "tau3": "Red-flag chunks",
        "tau4": "Medication chunks"
    ]

    private let combinedSystem = """
    You are Saratoga, an on-device clinical co-pilot. A clinician is seeing a patient.
    Given the transcript and retrieved guideline chunks for up to three tasks, emit
    EXACTLY these three sections in this order. Use 'none' for any section with no
    relevant chunks.

    Formatting rules (strict):
    - Plain text only. NO LaTeX.
    - Numbers as plain digits or words (50, RR 58, SpO2 90%).
    - Cite chunk ids in [brackets].
    - MUST-RULE-OUT: only conditions the transcript raises suspicion for.
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
    * <specific step>
    * <specific step>
    * <specific step>

    ## MED REC
    MED FLAG: <drug(s)>
    RISK: <one line> [chunk_id]
    ACTION: <one line>

    Under 200 words total. No preamble. Stop after MED REC.
    """

    init(documentsURL: URL) {
        let dir = documentsURL.appendingPathComponent("evidence")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyyMMdd-HHmmss"
        let stamp = fmt.string(from: Date())
        logURL = dir.appendingPathComponent("session-\(stamp).jsonl")
        fhirURL = dir.appendingPathComponent("session-\(stamp).fhir-queue.jsonl")
    }

    func load(weightsDir: URL) throws {
        // iPhone free Dev memory budget is tight (~3-5 GB RSS). Strategy:
        // 1. Load precomputed index from app bundle (no embed calls during init).
        // 2. Load embedder ONLY (small, ~500 MB) for runtime query embeds.
        // 3. DEFER reason model load to first handle() call.
        let fm = FileManager.default
        let e2b = weightsDir.appendingPathComponent("gemma-4-e2b-it")
        let e4b = weightsDir.appendingPathComponent("gemma-4-e4b-it")
        let reasonDir: URL = fm.fileExists(atPath: e2b.appendingPathComponent("config.txt").path)
            ? e2b : e4b
        Saratoga.unprotectTree(weightsDir.appendingPathComponent("qwen3-embedding-0.6b"))
        Saratoga.unprotectTree(reasonDir)
        self.reasonWeightsPath = reasonDir.path

        if !loadPrecomputedIndex() {
            // fallback: old path (will embed on device)
            let embedPath = weightsDir.appendingPathComponent("qwen3-embedding-0.6b").path
            embed = try cactusInit(embedPath, nil, false)
            loadCorpus()
            try buildIndex()
            return
        }
        // Normal path — tiny embedder for query-time only
        let embedPath = weightsDir.appendingPathComponent("qwen3-embedding-0.6b").path
        embed = try cactusInit(embedPath, nil, false)
    }

    private func loadPrecomputedIndex() -> Bool {
        guard let url = Bundle.main.url(forResource: "corpus_index",
                                         withExtension: "json") else { return false }
        guard let data = try? Data(contentsOf: url),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let chunkList = obj["chunks"] as? [[String: Any]],
              let vecList = obj["vectors"] as? [[Double]] else { return false }
        chunks.removeAll()
        vectors.removeAll()
        for (i, c) in chunkList.enumerated() {
            guard let id = c["id"] as? String,
                  let topicRaw = c["topic"] as? String,
                  let text = c["text"] as? String,
                  i < vecList.count else { continue }
            // Map corpus topic names to our τ keys.
            let tau: String
            if topicRaw.hasPrefix("tau1") { tau = "tau1" }
            else if topicRaw.hasPrefix("tau3") { tau = "tau3" }
            else if topicRaw.hasPrefix("tau4") { tau = "tau4" }
            else { continue }
            chunks.append(Chunk(id: id, tau: tau, text: text))
            vectors.append(vecList[i].map { Float($0) })
        }
        return !chunks.isEmpty
    }

    private var reasonWeightsPath: String = ""

    /// Lazy-open the reason model on first use.
    private func ensureReason() throws {
        if reason != nil { return }
        reason = try cactusInit(reasonWeightsPath, nil, false)
    }

    /// Recursively mark files as NSFileProtectionNone so mmap works while the
    /// device is unlocked and while the app is running (iOS encrypts Documents
    /// by default; mmap on Class A / B files can fail intermittently).
    static func unprotectTree(_ root: URL) {
        let fm = FileManager.default
        try? (root as NSURL).setResourceValue(URLFileProtection.none, forKey: .fileProtectionKey)
        guard let e = fm.enumerator(at: root,
                                    includingPropertiesForKeys: [.isDirectoryKey],
                                    options: []) else { return }
        for case let url as URL in e {
            try? (url as NSURL).setResourceValue(URLFileProtection.none, forKey: .fileProtectionKey)
        }
    }

    private func loadCorpus() {
        chunks.removeAll()
        let files: [(String, String)] = [
            ("tau1_differential", "tau1"),
            ("tau3_redflags", "tau3"),
            ("tau4_medrec", "tau4"),
        ]
        for (stem, tau) in files {
            guard let url = Bundle.main.url(forResource: stem, withExtension: "md",
                                             subdirectory: "Resources/corpus")
                ?? Bundle.main.url(forResource: stem, withExtension: "md")
                ?? Bundle.main.url(forResource: stem, withExtension: "md",
                                    subdirectory: "corpus"),
                  let text = try? String(contentsOf: url) else { continue }
            for block in text.components(separatedBy: "\n---") {
                let b = block.trimmingCharacters(in: .whitespacesAndNewlines)
                guard let m = b.range(of: "^##\\s+(\\S+)",
                                       options: [.regularExpression, .anchored]) else { continue }
                let header = String(b[m])
                let id = header.replacingOccurrences(
                    of: "^##\\s+", with: "",
                    options: .regularExpression
                ).trimmingCharacters(in: .whitespaces)
                let body = b.replacingOccurrences(
                    of: "^##\\s+\\S+\\s*\\n", with: "",
                    options: .regularExpression
                ).trimmingCharacters(in: .whitespacesAndNewlines)
                if !body.isEmpty { chunks.append(Chunk(id: id, tau: tau, text: body)) }
            }
        }
    }

    private func buildIndex() throws {
        guard let embed = embed else { return }
        vectors.removeAll()
        for c in chunks {
            let v = try cactusEmbed(embed, c.text, true)
            vectors.append(v)
        }
    }

    private func ragTopPerTau(_ query: String, k: Int = 3) throws -> [String: [Hit]] {
        guard let embed = embed else { return [:] }
        let q = try cactusEmbed(embed, query, true)
        let scored: [Hit] = zip(chunks, vectors).map { (chunk, v) in
            var dot: Float = 0
            for j in 0..<v.count { dot += v[j] * q[j] }
            return Hit(chunk: chunk, score: dot)
        }
        let grouped = Dictionary(grouping: scored) { $0.chunk.tau }
        return grouped.mapValues { $0.sorted { $0.score > $1.score }.prefix(k).map { $0 } }
    }

    private func composeAll(utterance: String,
                             perTau: [String: [Hit]],
                             fired: Set<String>,
                             onSection: ((String, String) -> Void)?) throws -> [String: String] {
        try ensureReason()
        guard let reason = reason else { return [:] }
        let blocks = fired.map { tau -> String in
            let hits = Array((perTau[tau] ?? []).prefix(2))
            let lines = hits.map { "- [\($0.chunk.id)] \(String($0.chunk.text.prefix(180)))" }
                .joined(separator: "\n")
            return "\(labels[tau] ?? tau):\n\(lines)"
        }.joined(separator: "\n\n")
        let user = "Transcript:\n\"\(utterance)\"\n\n\(blocks)\n\nEmit the three sections."
        let messages: [[String: String]] = [
            ["role": "system", "content": combinedSystem],
            ["role": "user", "content": user]
        ]
        let messagesJson = String(data: try JSONSerialization.data(withJSONObject: messages), encoding: .utf8)!
        let opts = "{\"max_tokens\":260,\"temperature\":0.2}"

        var buf = ""
        let headerRe = try NSRegularExpression(pattern: "(?m)^##\\s+(DDX|RED FLAG|MED REC)\\b.*$")
        let cb: ((String, UInt32) -> Void)? = onSection.map { callback in
            return { token, _ in
                buf += token
                let full = buf
                let matches = headerRe.matches(in: full, range: NSRange(full.startIndex..., in: full))
                if matches.isEmpty { return }
                for (i, m) in matches.enumerated() {
                    let nameRange = m.range(at: 1)
                    guard nameRange.location != NSNotFound,
                          let r = Range(nameRange, in: full) else { continue }
                    let header = String(full[r])
                    guard let t = ["DDX": "tau1", "RED FLAG": "tau3", "MED REC": "tau4"][header] else { continue }
                    if !fired.contains(t) { continue }
                    let start = full.index(full.startIndex, offsetBy: m.range.location + m.range.length)
                    let end = i + 1 < matches.count
                        ? full.index(full.startIndex, offsetBy: matches[i + 1].range.location)
                        : full.endIndex
                    var body = String(full[start..<end]).trimmingCharacters(in: .whitespacesAndNewlines)
                    body = Saratoga.stripLatex(body)
                    if !body.isEmpty { callback(t, body) }
                }
            }
        }

        cactusReset(reason)
        _ = try cactusComplete(reason, messagesJson, opts, nil, cb, nil)
        return parseSections(buf, fired: fired)
    }

    private func parseSections(_ text: String, fired: Set<String>) -> [String: String] {
        var out: [String: String] = [:]
        let headerRe = try! NSRegularExpression(pattern: "(?m)^##\\s+(DDX|RED FLAG|MED REC)\\b.*$")
        let matches = headerRe.matches(in: text, range: NSRange(text.startIndex..., in: text))
        for (i, m) in matches.enumerated() {
            guard let r = Range(m.range(at: 1), in: text) else { continue }
            let header = String(text[r])
            guard let tau = ["DDX": "tau1", "RED FLAG": "tau3", "MED REC": "tau4"][header] else { continue }
            if !fired.contains(tau) { continue }
            let start = text.index(text.startIndex, offsetBy: m.range.location + m.range.length)
            let end = i + 1 < matches.count
                ? text.index(text.startIndex, offsetBy: matches[i + 1].range.location)
                : text.endIndex
            var body = String(text[start..<end]).trimmingCharacters(in: .whitespacesAndNewlines)
            body = Saratoga.stripLatex(body)
            if !body.isEmpty && body.lowercased() != "none" { out[tau] = body }
        }
        return out
    }

    static func stripLatex(_ s: String) -> String {
        var t = s
        t = t.replacingOccurrences(
            of: "\\\\text\\{([^}]*)\\}", with: "$1", options: .regularExpression
        )
        t = t.replacingOccurrences(
            of: "\\$([^$]*)\\$", with: "$1", options: .regularExpression
        )
        t = t.replacingOccurrences(of: "\\ge", with: "≥")
            .replacingOccurrences(of: "\\le", with: "≤")
            .replacingOccurrences(of: "\\%", with: "%")
        t = t.replacingOccurrences(
            of: "\\\\[a-zA-Z]+\\b", with: "", options: .regularExpression
        )
        return t
    }

    func handle(utterance: String,
                onQuick: ((String, String, Float, String) -> Void)? = nil,
                onSection: ((String, String) -> Void)? = nil) throws -> Outcome {
        append(kind: "transcript", text: utterance)
        let perTau = try ragTopPerTau(utterance)
        let firedTaus: Set<String> = Set(perTau.compactMap { (tau, hits) -> String? in
            guard let top = hits.first else { return nil }
            return top.score >= (gate[tau] ?? 0.5) ? tau : nil
        })
        for tau in firedTaus {
            guard let top = perTau[tau]?.first else { continue }
            let preview = String(top.chunk.text.prefix(160))
            append(kind: "quick:\(tau)", text: "\(top.chunk.id) s=\(top.score)")
            onQuick?(tau, top.chunk.id, top.score, preview)
        }
        let cards = firedTaus.isEmpty
            ? [:]
            : try composeAll(utterance: utterance, perTau: perTau, fired: firedTaus, onSection: onSection)
        let fired: [TaskOutput] = firedTaus.compactMap { tau in
            guard let card = cards[tau] else { return nil }
            let top = perTau[tau]?.first
            append(kind: "task:\(tau)", text: card)
            return TaskOutput(tau: tau, card: card, topHit: top)
        }
        let bundle = fired.isEmpty ? nil : emitFhirBundle(utterance: utterance, fired: fired)
        return Outcome(tasksFired: fired, fhirBundle: bundle)
    }

    private func emitFhirBundle(utterance: String, fired: [TaskOutput]) -> String {
        let encId = "enc-\(Int(Date().timeIntervalSince1970 * 1000))"
        let bundle: [String: Any] = [
            "resourceType": "Bundle",
            "type": "collection",
            "entry": [
                ["resource": [
                    "resourceType": "Encounter",
                    "id": encId,
                    "status": "in-progress",
                    "class": "ambulatory",
                    "period": ["start": isoNow()]
                ]],
                ["resource": [
                    "resourceType": "ClinicalImpression",
                    "encounter": ["reference": "Encounter/\(encId)"],
                    "description": String(utterance.prefix(400)),
                    "finding": fired.map { t in
                        [
                            "itemCodeableConcept": [
                                "text": "\(t.tau):\(t.topHit?.chunk.id ?? "?")"
                            ],
                            "basis": String(t.card.prefix(600))
                        ]
                    }
                ]]
            ]
        ]
        if let data = try? JSONSerialization.data(withJSONObject: bundle, options: [.prettyPrinted]),
           let str = String(data: data, encoding: .utf8) {
            if let fh = try? FileHandle(forWritingTo: fhirURL) {
                fh.seekToEndOfFile(); fh.write(Data((str + "\n").utf8)); fh.closeFile()
            } else {
                try? (str + "\n").write(to: fhirURL, atomically: true, encoding: .utf8)
            }
        }
        append(kind: "fhir_queued", text: "enc=\(encId)")
        return encId
    }

    func syncQueueSize() -> Int {
        guard let txt = try? String(contentsOf: fhirURL) else { return 0 }
        return txt.split(whereSeparator: \.isNewline)
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            .filter { $0.contains("resourceType") }
            .count > 0 ? 1 : 0
    }

    func drainSyncQueue() -> Int {
        guard FileManager.default.fileExists(atPath: fhirURL.path) else { return 0 }
        let n = syncQueueSize()
        try? FileManager.default.removeItem(at: fhirURL)
        append(kind: "sync_drained", text: "\(n)")
        return n
    }

    private func append(kind: String, text: String) {
        let t = isoNow()
        let h = sha256(prevHash + t + text)
        let entry: [String: Any] = [
            "t_iso": t, "kind": kind, "text": text,
            "prev_hash": prevHash, "hash": h
        ]
        if let data = try? JSONSerialization.data(withJSONObject: entry),
           let line = String(data: data, encoding: .utf8) {
            if let fh = try? FileHandle(forWritingTo: logURL) {
                fh.seekToEndOfFile(); fh.write(Data((line + "\n").utf8)); fh.closeFile()
            } else {
                try? (line + "\n").write(to: logURL, atomically: true, encoding: .utf8)
            }
        }
        prevHash = h
    }

    func seal() -> String {
        let manifest: [String: Any] = [
            "session_id": logURL.deletingPathExtension().lastPathComponent,
            "tip_hash": prevHash,
            "sealed_at": isoNow()
        ]
        if let data = try? JSONSerialization.data(withJSONObject: manifest, options: [.prettyPrinted]),
           let str = String(data: data, encoding: .utf8) {
            let sealURL = logURL.deletingPathExtension().appendingPathExtension("seal.json")
            try? str.write(to: sealURL, atomically: true, encoding: .utf8)
        }
        return prevHash
    }

    func close() {
        if let r = reason { cactusDestroy(r); reason = nil }
        if let e = embed { cactusDestroy(e); embed = nil }
    }

    private func isoNow() -> String {
        let fmt = ISO8601DateFormatter()
        fmt.formatOptions = [.withInternetDateTime, .withTimeZone]
        return fmt.string(from: Date())
    }

    private func sha256(_ s: String) -> String {
        // Simple SHA-256 via CommonCrypto.
        let data = s.data(using: .utf8) ?? Data()
        var hash = [UInt8](repeating: 0, count: 32)
        data.withUnsafeBytes { ptr in
            _ = Saratoga.ccSha256(ptr.baseAddress, CC_LONG(data.count), &hash)
        }
        return hash.map { String(format: "%02x", $0) }.joined()
    }

    private static func ccSha256(_ data: UnsafeRawPointer?, _ len: CC_LONG, _ out: UnsafeMutablePointer<UInt8>) -> UnsafeMutablePointer<UInt8> {
        return CC_SHA256(data, len, out)!
    }
}

// MARK: - CommonCrypto bridging
import CommonCrypto
