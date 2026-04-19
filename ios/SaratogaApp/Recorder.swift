import AVFoundation
import Foundation

/// Simple 16 kHz mono PCM16 capture via AVAudioRecorder.
/// Writes directly to a WAV file. Returns the WAV bytes on stop.
final class Recorder {
    private var recorder: AVAudioRecorder?
    private var fileURL: URL?
    private(set) var recording = false

    func requestPermission(completion: @escaping (Bool) -> Void) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            DispatchQueue.main.async { completion(granted) }
        }
    }

    func start() throws {
        if recording { return }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement)
        try session.setActive(true, options: .notifyOthersOnDeactivation)

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("sara-\(Int(Date().timeIntervalSince1970)).wav")
        try? FileManager.default.removeItem(at: tmp)
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: 16000,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ]
        let rec = try AVAudioRecorder(url: tmp, settings: settings)
        rec.isMeteringEnabled = true
        if !rec.prepareToRecord() {
            throw NSError(domain: "Recorder", code: -2,
                          userInfo: [NSLocalizedDescriptionKey: "prepareToRecord failed"])
        }
        if !rec.record() {
            throw NSError(domain: "Recorder", code: -3,
                          userInfo: [NSLocalizedDescriptionKey: "record() returned false"])
        }
        recorder = rec
        fileURL = tmp
        recording = true
        NSLog("[rec] started -> \(tmp.path)")
    }

    /// Returns the recorded WAV file URL (ready to pass into cactusTranscribe).
    func stop() -> URL? {
        guard let rec = recorder else { return nil }
        rec.stop()
        recording = false
        let url = fileURL
        recorder = nil
        try? AVAudioSession.sharedInstance().setActive(false)
        if let url = url {
            let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? Int ?? -1
            NSLog("[rec] stopped, wav=\(url.lastPathComponent) size=\(size) bytes")
        }
        return url
    }
}
