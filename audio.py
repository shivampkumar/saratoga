"""Mic capture + TTS. Relative-threshold VAD (robust to ambient noise)."""
import subprocess
import sys

import numpy as np
import sounddevice as sd

SAMPLE_RATE = 16000


def record_until_silence(
    max_seconds: float = 30,
    silence_seconds: float = 1.0,
    speech_trigger_rms: float = 250.0,
    silence_ratio: float = 0.25,
    verbose: bool = True,
) -> bytes:
    """Record PCM16 mono from default mic.

    Speech triggers when RMS > `speech_trigger_rms`.
    After speech, silence = RMS < `max_rms_seen * silence_ratio`.
    This adapts to your mic level automatically.
    """
    chunk_ms = 100
    chunk_samples = SAMPLE_RATE * chunk_ms // 1000
    buf: list[bytes] = []
    silent_chunks = 0
    silent_limit = int(silence_seconds * 1000 / chunk_ms)
    max_chunks = int(max_seconds * 1000 // chunk_ms)
    heard = False
    max_rms = 0.0
    print("[rec] listening...", flush=True)
    try:
        with sd.InputStream(samplerate=SAMPLE_RATE, channels=1, dtype="int16") as stream:
            for i in range(max_chunks):
                data, _ = stream.read(chunk_samples)
                buf.append(data.tobytes())
                rms = float(np.sqrt(np.mean(np.square(data.astype(np.float32)))))
                if rms > max_rms:
                    max_rms = rms
                silence_floor = max(max_rms * silence_ratio, 80.0)
                if verbose and i % 3 == 0:
                    bar = "#" * min(40, int(rms / 40))
                    sys.stdout.write(
                        f"\r  rms={rms:6.1f} peak={max_rms:6.1f} "
                        f"floor={silence_floor:5.1f} "
                        f"heard={'Y' if heard else 'n'} "
                        f"silent={silent_chunks:2d}/{silent_limit}  {bar:<40}"
                    )
                    sys.stdout.flush()
                if not heard:
                    if rms > speech_trigger_rms:
                        heard = True
                        silent_chunks = 0
                else:
                    if rms < silence_floor:
                        silent_chunks += 1
                    else:
                        silent_chunks = 0
                    if silent_chunks >= silent_limit:
                        break
    except Exception as e:
        print(f"\n[rec] ERROR: {e}")
        return b""
    print()
    dur = len(buf) * chunk_ms / 1000
    print(f"[rec] done ({dur:.1f}s, peak={max_rms:.0f}, heard={heard})")
    if not heard:
        return b""
    return b"".join(buf)


def speak(text: str) -> None:
    subprocess.run(["say", text], check=False)
