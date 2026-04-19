import AVFoundation
import Foundation
import cactus

/// Moonshine-base ASR via Cactus Apple SDK. PCM -> WAV -> transcribe.
final class Asr {
    private var model: CactusModelT?
    private let sampleRate = 16_000
    private let whisperPrompt = "<|startoftranscript|><|en|><|transcribe|><|notimestamps|>"

    func load(from weightsDir: URL) throws {
        let dir = weightsDir.appendingPathComponent("moonshine-base")
        Saratoga.unprotectTree(dir)
        model = try cactusInit(dir.path, nil, false)
    }

    func transcribe(pcm: Data, tmpDir: URL) throws -> String {
        guard let model = model else { return "" }
        let wav = tmpDir.appendingPathComponent("rec.wav")
        try writeWav(pcm: pcm, to: wav)
        return try transcribeFile(at: wav)
    }

    func transcribeFile(at url: URL) throws -> String {
        guard let model = model else { return "" }
        let pcm = try Asr.extractPCM16(from: url)
        NSLog("[asr] extracted pcm bytes=\(pcm.count) (~\(pcm.count / 32000)s)")
        let raw = try cactusTranscribe(model, nil, nil, nil, nil, pcm)
        NSLog("[asr] RAW JSON: \(raw)")
        guard let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        var text = (obj["response"] as? String) ?? ""
        if text.isEmpty, let segs = obj["segments"] as? [[String: Any]] {
            text = segs.compactMap { $0["text"] as? String }.joined(separator: " ")
        }
        NSLog("[asr] parsed text=\(text.prefix(120))")
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func extractPCM16(from url: URL) throws -> Data {
        let file = try AVAudioFile(forReading: url)
        let format = file.processingFormat
        let frameCount = AVAudioFrameCount(file.length)
        guard let source = AVAudioPCMBuffer(pcmFormat: format,
                                             frameCapacity: frameCount) else {
            throw NSError(domain: "Asr", code: -10,
                          userInfo: [NSLocalizedDescriptionKey: "buffer alloc failed"])
        }
        try file.read(into: source)

        guard let targetFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: 16000,
            channels: 1,
            interleaved: true
        ) else {
            throw NSError(domain: "Asr", code: -11,
                          userInfo: [NSLocalizedDescriptionKey: "target format nil"])
        }
        if format.sampleRate == 16000 && format.channelCount == 1
            && format.commonFormat == .pcmFormatInt16 {
            // already target — grab bytes directly
            if let ch = source.int16ChannelData {
                let count = Int(source.frameLength) * MemoryLayout<Int16>.size
                return Data(bytes: ch[0], count: count)
            }
        }
        guard let converter = AVAudioConverter(from: format, to: targetFormat) else {
            throw NSError(domain: "Asr", code: -12,
                          userInfo: [NSLocalizedDescriptionKey: "converter nil"])
        }
        let outCap = AVAudioFrameCount(Double(source.frameLength) *
                                        (16000.0 / format.sampleRate)) + 1024
        guard let out = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: outCap) else {
            throw NSError(domain: "Asr", code: -13,
                          userInfo: [NSLocalizedDescriptionKey: "out buffer nil"])
        }
        var err: NSError?
        var provided = false
        _ = converter.convert(to: out, error: &err) { _, status in
            if provided {
                status.pointee = .endOfStream
                return nil
            }
            status.pointee = .haveData
            provided = true
            return source
        }
        if let err = err { throw err }
        guard let ch = out.int16ChannelData else { return Data() }
        let count = Int(out.frameLength) * MemoryLayout<Int16>.size
        return Data(bytes: ch[0], count: count)
    }

    func close() {
        if let m = model { cactusDestroy(m); model = nil }
    }

    private func writeWav(pcm: Data, to url: URL) throws {
        var header = Data()
        let dataLen = UInt32(pcm.count)
        let byteRate = UInt32(sampleRate * 1 * 16 / 8)
        header.append("RIFF".data(using: .ascii)!)
        header.append(u32(UInt32(36 + Int(dataLen))))
        header.append("WAVE".data(using: .ascii)!)
        header.append("fmt ".data(using: .ascii)!)
        header.append(u32(16))
        header.append(u16(1))              // PCM
        header.append(u16(1))              // mono
        header.append(u32(UInt32(sampleRate)))
        header.append(u32(byteRate))
        header.append(u16(2))              // block align
        header.append(u16(16))             // bits/sample
        header.append("data".data(using: .ascii)!)
        header.append(u32(dataLen))
        var out = Data()
        out.append(header); out.append(pcm)
        try out.write(to: url)
    }

    private func u16(_ v: UInt16) -> Data {
        var x = v.littleEndian
        return Data(bytes: &x, count: 2)
    }
    private func u32(_ v: UInt32) -> Data {
        var x = v.littleEndian
        return Data(bytes: &x, count: 4)
    }
}
