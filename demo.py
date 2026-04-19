"""Traffic stop demo — scripted utterances, full Sidewinder pipeline.

Use for judges on-stage. Replace with live mic via app.py once VAD wired.
"""
import time

from sidewinder import Sidewinder

SCRIPT = [
    "Good evening, do you know why I stopped you?",
    "Where are you headed tonight?",
    "Have you had anything to drink?",
    "Mind if I take a look in the trunk?",
    "License and registration, please.",
    "Can you step out of the vehicle?",
    "I'm going to call for a K-9 unit. Stay here.",
]


def banner(text: str) -> None:
    bar = "=" * 72
    print(f"\n{bar}\n  {text}\n{bar}")


def main():
    banner("SIDEWINDER — on-device rights co-pilot (airplane mode demo)")
    s = Sidewinder()
    banner("Live traffic stop — judge plays officer")
    t0 = time.time()
    for i, line in enumerate(SCRIPT, 1):
        print(f"\n[officer {i}/{len(SCRIPT)}] \"{line}\"")
        t = time.time()
        out = s.handle(line)
        dt_ms = (time.time() - t) * 1000
        intent = out.get("intent")
        if intent == "legal_lookup":
            top = out["hits"][0] if out.get("hits") else {"id": "?", "score": 0}
            structured = out.get("structured", {})
            print(
                f"   route=legal top=[{top['id']} s={top['score']:.2f}] "
                f"demand={structured.get('demand_type','?')} "
                f"surface={structured.get('surface','?')} dt={dt_ms:.0f}ms"
            )
            print(f"   WHISPER: {out['whisper']}")
        elif intent == "panic":
            print(f"   PANIC matched={out['panic_phrase']!r} dt={dt_ms:.0f}ms")
            print(f"   WHISPER: {out['whisper']}")
        else:
            score = out.get("top_score", 0.0)
            print(f"   route=ambient (top={score:.2f} < 0.45) dt={dt_ms:.0f}ms")
    seal = s.seal()
    total = time.time() - t0
    banner(f"SEAL — tip={seal['tip_hash'][:12]}... total={total:.1f}s")
    print(f"Evidence log: {seal['path']}")
    print(f"Session id: {seal['session_id']}")
    s.close()


if __name__ == "__main__":
    main()
