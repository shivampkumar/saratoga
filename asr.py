"""Live ASR — whisper-tiny via Cactus. PCM bytes -> WAV file -> transcribe."""
import json
import struct
import sys
import tempfile
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CACTUS_ROOT = ROOT.parent / "cactus"
sys.path.insert(0, str(CACTUS_ROOT / "python"))

from src.cactus import cactus_destroy, cactus_init, cactus_transcribe  # type: ignore
from src.downloads import ensure_model  # type: ignore

ASR_MODEL = "nvidia/parakeet-tdt-0.6b-v3"
SAMPLE_RATE = 16000
# Whisper models need this; Parakeet ignores it.
WHISPER_PROMPT = "<|startoftranscript|><|en|><|transcribe|><|notimestamps|>"


def _pcm_to_wav(pcm: bytes, path: str, sr: int = SAMPLE_RATE) -> None:
    with wave.open(path, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(pcm)


class ASR:
    def __init__(self):
        weights = ensure_model(ASR_MODEL)
        self.model = cactus_init(str(weights), None, False)

    def transcribe_pcm(self, pcm: bytes) -> str:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            path = tmp.name
        _pcm_to_wav(pcm, path)
        prompt = WHISPER_PROMPT if "whisper" in ASR_MODEL else None
        raw = cactus_transcribe(self.model, path, prompt, None, None, None)
        try:
            Path(path).unlink()
        except OSError:
            pass
        result = json.loads(raw)
        return (result.get("response") or "").strip()

    def close(self):
        cactus_destroy(self.model)
