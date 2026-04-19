import SwiftUI

struct ContentView: View {

    @State private var ready = false
    @State private var recording = false
    @State private var status = "boot"
    @State private var transcript = ""
    @State private var tau1 = "—"
    @State private var tau1Meta = ""
    @State private var tau3 = "—"
    @State private var tau3Meta = ""
    @State private var tau4 = "—"
    @State private var tau4Meta = ""
    @State private var fhirText = "(no encounters yet)"
    @State private var fhirN = 0
    @State private var loading = false
    @State private var asrMs: Int = 0
    @State private var totalMs: Int = 0
    @State private var quickMode: Bool = false

    private let recorder = Recorder()
    @State private var asr: Asr?
    @State private var saratoga: Saratoga?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                header
                buttons
                statusLine
                transcriptCard
                tauCard(title: "τ1  DIFFERENTIAL · MUST-RULE-OUTS",
                        accent: .blue, text: tau1, meta: tau1Meta)
                tauCard(title: "τ3  RED FLAG", accent: .red,
                        text: tau3, meta: tau3Meta, bold: true)
                tauCard(title: "τ4  MEDICATION REC", accent: .green,
                        text: tau4, meta: tau4Meta)
                fhirCard
            }
            .padding(14)
        }
        .background(Color(red: 3/255, green: 7/255, blue: 18/255))
        .preferredColorScheme(.dark)
        .onAppear {
            recorder.requestPermission { _ in }
        }
    }

    // MARK: - Subviews

    private var header: some View {
        HStack {
            Text("SARATOGA")
                .font(.system(size: 26, weight: .bold, design: .monospaced))
                .foregroundColor(.white)
                .kerning(2)
            Spacer()
            Circle()
                .fill(statusDotColor)
                .frame(width: 10, height: 10)
            Text(status)
                .font(.system(size: 11, design: .monospaced))
                .foregroundColor(.gray)
        }
    }

    private var buttons: some View {
        VStack(spacing: 6) {
            Toggle(isOn: $quickMode) {
                Text(quickMode ? "QUICK MODE · RAG only (<1s)" : "DEEP MODE · E2B reasoning")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(quickMode ? .green : .orange)
                    .kerning(1.5)
            }
            .toggleStyle(SwitchToggleStyle(tint: .orange))
            .onChange(of: quickMode) { newValue in
                saratoga?.quickOnly = newValue
            }
            if !ready {
                Button(action: onLoad) {
                    Text("LOAD MODELS")
                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                        .kerning(2)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color(red: 37/255, green: 99/255, blue: 235/255))
                        .cornerRadius(8)
                }
                .disabled(loading)
            }
            HStack(spacing: 6) {
                Button(action: onRecordToggle) {
                    Text(recording ? "■  STOP" : "●  RECORD")
                        .font(.system(size: 15, weight: .bold, design: .monospaced))
                        .kerning(2)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Color(red: 220/255, green: 38/255, blue: 38/255))
                        .cornerRadius(8)
                }.disabled(!ready)
                Button(action: onSync) {
                    Text("SYNC \(fhirN)")
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .kerning(2)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Color(red: 37/255, green: 99/255, blue: 235/255))
                        .cornerRadius(8)
                }.disabled(!ready)
            }
        }
    }

    private var statusLine: some View {
        Text("asr=\(asrMs)ms · total=\(totalMs)ms")
            .font(.system(size: 10, design: .monospaced))
            .foregroundColor(Color.gray)
            .padding(.top, 4)
    }

    private var transcriptCard: some View {
        cardWithAccent(color: Color.orange) {
            VStack(alignment: .leading, spacing: 4) {
                Text("TRANSCRIPT").font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundColor(.orange).kerning(2)
                Text(transcript.isEmpty ? "—" : transcript)
                    .font(.system(size: 13))
                    .foregroundColor(Color(white: 0.9))
            }
        }
    }

    private func tauCard(title: String, accent: Color, text: String, meta: String, bold: Bool = false) -> some View {
        cardWithAccent(color: accent) {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(title).font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundColor(accent).kerning(2)
                    Spacer()
                    Text(meta).font(.system(size: 9, design: .monospaced))
                        .foregroundColor(.gray)
                }
                Text(text)
                    .font(.system(size: 12, weight: bold ? .bold : .regular, design: .monospaced))
                    .foregroundColor(accent.opacity(0.85))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var fhirCard: some View {
        cardWithAccent(color: Color.orange) {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("FHIR QUEUE · offline-buffered")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundColor(.orange).kerning(2)
                    Spacer()
                    Text("\(fhirN) queued")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundColor(.orange)
                }
                Text(fhirText).font(.system(size: 10, design: .monospaced))
                    .foregroundColor(.gray)
            }
        }
    }

    private func cardWithAccent<Content: View>(color: Color, @ViewBuilder _ content: () -> Content) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Rectangle().fill(color).frame(width: 3)
            content().frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(10)
        .background(Color(red: 17/255, green: 24/255, blue: 39/255))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(Color(red: 31/255, green: 41/255, blue: 55/255), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private var statusDotColor: Color {
        switch status {
        case "ready": return .green
        case "rec": return .red
        case "err": return .red
        default: return .orange
        }
    }

    // MARK: - Actions

    private func weightsDir() -> URL {
        let fm = FileManager.default
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        // Prefer Documents/weights if it exists and has at least one model.
        let nested = docs.appendingPathComponent("weights")
        let nestedHasModel = (try? fm.contentsOfDirectory(atPath: nested.path))?.contains(where: { $0.hasPrefix("gemma") || $0.hasPrefix("qwen") || $0.hasPrefix("moonshine") }) ?? false
        if nestedHasModel { return nested }
        // Fallback: models dropped directly in Documents root via Finder file sharing.
        let rootHasModel = (try? fm.contentsOfDirectory(atPath: docs.path))?.contains(where: { $0.hasPrefix("gemma") || $0.hasPrefix("qwen") || $0.hasPrefix("moonshine") }) ?? false
        if rootHasModel { return docs }
        try? fm.createDirectory(at: nested, withIntermediateDirectories: true)
        return nested
    }

    private func onLoad() {
        loading = true; status = "loading"
        Task.detached {
            do {
                let dir = await weightsDir()
                let a = Asr(); try a.load(from: dir)
                let s = Saratoga(documentsURL: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0])
                try s.load(weightsDir: dir)
                await MainActor.run {
                    self.asr = a; self.saratoga = s
                    s.quickOnly = quickMode
                    ready = true; status = "ready"; loading = false
                    fhirN = s.syncQueueSize()
                }
            } catch {
                await MainActor.run {
                    status = "err: \(error.localizedDescription)"; loading = false
                }
            }
        }
    }

    private func onRecordToggle() {
        guard ready else { return }
        if recording { stopAndProcess() } else { startRec() }
    }

    private func startRec() {
        do { try recorder.start(); recording = true; status = "rec" }
        catch { status = "rec err: \(error.localizedDescription)" }
    }

    private func stopAndProcess() {
        let wavURL = recorder.stop()
        recording = false; status = "asr"
        Task.detached {
            do {
                let tAsr = Date()
                let text: String
                if let url = wavURL {
                    text = try await MainActor.run { self.asr }.map { try $0.transcribeFile(at: url) } ?? ""
                } else {
                    text = ""
                }
                let asrElapsed = Int(Date().timeIntervalSince(tAsr) * 1000)
                guard !text.isEmpty else {
                    await MainActor.run { status = "empty transcript (check mic permission)" }; return
                }
                await MainActor.run {
                    asrMs = asrElapsed
                    transcript = "• \(text)\n" + transcript
                    tau1 = "…"; tau3 = "…"; tau4 = "…"
                    status = "think"
                }
                let t0 = Date()
                let out = try await MainActor.run { self.saratoga }.map { s -> Saratoga.Outcome in
                    try s.handle(
                        utterance: text,
                        onQuick: { tau, chunkId, score, preview in
                            Task { @MainActor in
                                let meta = "⚡ quick [\(chunkId) s=\(String(format: "%.2f", score))]"
                                switch tau {
                                case "tau1": tau1Meta = meta; tau1 = preview + "\n\n(E4B filling in...)"
                                case "tau3": tau3Meta = meta; tau3 = preview + "\n\n(E4B filling in...)"
                                case "tau4": tau4Meta = meta; tau4 = preview + "\n\n(E4B filling in...)"
                                default: break
                                }
                            }
                        },
                        onSection: { tau, soFar in
                            Task { @MainActor in
                                switch tau {
                                case "tau1": tau1Meta = "✓ streaming"; tau1 = soFar
                                case "tau3": tau3Meta = "✓ streaming"; tau3 = soFar
                                case "tau4": tau4Meta = "✓ streaming"; tau4 = soFar
                                default: break
                                }
                            }
                        }
                    )
                } ?? Saratoga.Outcome(tasksFired: [], fhirBundle: nil)
                let dt = Int(Date().timeIntervalSince(t0) * 1000)
                await MainActor.run {
                    totalMs = dt; status = "ready"
                    for t in out.tasksFired {
                        let metaTxt = "[\(t.topHit?.chunk.id ?? "?") s=\(String(format: "%.2f", t.topHit?.score ?? 0))]"
                        switch t.tau {
                        case "tau1": tau1 = t.card; tau1Meta = metaTxt
                        case "tau3": tau3 = t.card; tau3Meta = metaTxt
                        case "tau4": tau4 = t.card; tau4Meta = metaTxt
                        default: break
                        }
                    }
                    if let bundle = out.fhirBundle { fhirText = "⇢ queued \(bundle)" }
                    if let s = saratoga { fhirN = s.syncQueueSize() }
                }
            } catch {
                await MainActor.run { status = "err: \(error.localizedDescription)" }
            }
        }
    }

    private func onSync() {
        guard let s = saratoga else { return }
        let n = s.drainSyncQueue()
        status = "synced \(n)"; fhirText = "✓ reconciled \(n) Bundle(s)"
        fhirN = s.syncQueueSize()
    }
}

#Preview { ContentView() }
