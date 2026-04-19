"""Live demo: record mic -> whisper-tiny ASR -> Sidewinder pipeline -> print + TTS.

Run:
    source ../cactus/venv/bin/activate
    python app.py
"""
import argparse
import subprocess
import time

from asr import ASR
from audio import record_until_silence, speak
from sidewinder import Sidewinder


def banner(text: str) -> None:
    bar = "=" * 72
    print(f"\n{bar}\n  {text}\n{bar}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--tts", action="store_true", help="Speak whispers via macOS 'say'")
    p.add_argument("--max-secs", type=float, default=20.0)
    p.add_argument("--silence-secs", type=float, default=1.2)
    args = p.parse_args()

    banner("SIDEWINDER — live mic mode (airplane mode demo)")
    print("[init] Loading ASR...")
    asr = ASR()
    print("[init] Loading Sidewinder pipeline...")
    s = Sidewinder()
    banner("Ready. Speak as officer. Ctrl+C to exit.")

    try:
        while True:
            pcm = record_until_silence(
                max_seconds=args.max_secs, silence_seconds=args.silence_secs
            )
            if not pcm:
                continue
            if len(pcm) < 2 * 16000 * 0.6:  # <0.6s audio
                print("[asr] too short, skipping")
                continue
            t = time.time()
            text = asr.transcribe_pcm(pcm)
            asr_ms = (time.time() - t) * 1000
            if not text or len(text.strip()) < 4:
                print(f"[asr] (silence or too short: {text!r})")
                continue
            # Whisper common hallucinations on silence/noise
            if text.lower().strip() in {"you", "thank you.", "thanks.", ".", "...", "voy"}:
                print(f"[asr] hallucination filter: {text!r}")
                continue
            print(f"\n[officer] \"{text}\"  [asr={asr_ms:.0f}ms]")

            t = time.time()
            out = s.handle(text)
            pipe_ms = (time.time() - t) * 1000
            intent = out.get("intent")
            if intent == "legal_lookup":
                top = out["hits"][0] if out.get("hits") else {"id": "?", "score": 0}
                whisper = out["whisper"]
                print(
                    f"[route] LEGAL top=[{top['id']} s={top['score']:.2f}] "
                    f"pipe={pipe_ms:.0f}ms"
                )
                print(f"\n    \U0001f92b  WHISPER: {whisper}\n")
                if args.tts:
                    subprocess.Popen(["say", whisper])
            elif intent == "panic":
                print(f"[route] PANIC matched={out['panic_phrase']!r} pipe={pipe_ms:.0f}ms")
                print(f"\n    \u26a0\ufe0f  {out['whisper']}\n")
                if args.tts:
                    subprocess.Popen(["say", out["whisper"]])
            else:
                score = out.get("top_score", 0.0)
                print(f"[route] ambient (top={score:.2f}) pipe={pipe_ms:.0f}ms")
    except KeyboardInterrupt:
        pass
    finally:
        seal = s.seal()
        banner(
            f"SEAL tip={seal['tip_hash'][:12]}  session={seal['session_id']}"
        )
        print(f"Evidence log: {seal['path']}")
        asr.close()
        s.close()


if __name__ == "__main__":
    main()
