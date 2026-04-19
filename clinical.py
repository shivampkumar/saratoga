"""Saratoga clinical pipeline — Mac Python mirror of Android Saratoga.kt.

For rapid prompt iteration. Run one transcript, see parsed τ sections.

Usage:
    source ../cactus/venv/bin/activate
    python clinical.py "42yo woman chest pain 3 days ..."
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CACTUS_ROOT = ROOT.parent / "cactus"
sys.path.insert(0, str(CACTUS_ROOT / "python"))

from src.cactus import (  # type: ignore
    cactus_complete,
    cactus_destroy,
    cactus_init,
    cactus_reset,
)
from src.downloads import ensure_model  # type: ignore

from rag import RagIndex

REASON_MODEL = "google/gemma-4-E4B-it"

GATE_THRESHOLDS = {"tau1": 0.42, "tau3": 0.48, "tau4": 0.48}
TAU_OF = {
    "tau1_differential": "tau1",
    "tau3_redflags": "tau3",
    "tau4_medrec": "tau4",
}

LABELS = {
    "tau1": "Differential chunks",
    "tau3": "Red-flag chunks",
    "tau4": "Medication chunks",
}

COMBINED_SYSTEM = """\
You are Saratoga, an on-device clinical co-pilot. A clinician is seeing a patient.
Given the transcript and retrieved guideline chunks for up to three tasks, emit
EXACTLY these three sections (in this order). Use 'none' for any section with no
relevant chunks provided. Cite chunk ids in [brackets].

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
* <bullet>
* <bullet>
* <bullet>

## MED REC
MED FLAG: <drug(s)>
RISK: <one line> [chunk_id]
ACTION: <one line>

Under 220 words total. No preamble. No disclaimers."""


def top_per_tau(rag: RagIndex, query: str, k: int = 3) -> dict:
    all_hits = rag.query(query, k=100)
    per_tau: dict = {}
    for h in all_hits:
        tau = TAU_OF.get(h["source"].removesuffix(".md"))
        if not tau:
            continue
        per_tau.setdefault(tau, []).append(h)
    return {t: hs[:k] for t, hs in per_tau.items()}


def compose_all(model, utterance: str, per_tau: dict, fired: set) -> str:
    blocks = []
    for tau in fired:
        hits = per_tau.get(tau, [])[:3]
        lines = "\n".join(f"- [{h['id']}] {h['text'][:220]}" for h in hits)
        blocks.append(f"{LABELS[tau]}:\n{lines}")
    user = f'Transcript:\n"{utterance}"\n\n' + "\n\n".join(blocks) + "\n\nEmit the three sections."
    messages = [
        {"role": "system", "content": COMBINED_SYSTEM},
        {"role": "user", "content": user},
    ]
    opts = json.dumps({"max_tokens": 380, "temperature": 0.2})
    cactus_reset(model)
    raw = cactus_complete(model, json.dumps(messages), opts, None, None, None)
    result = json.loads(raw)
    return result.get("response", "")


def parse_sections(text: str, fired: set) -> dict:
    regex = re.compile(r"^##\s+(DDX|RED FLAG|MED REC)\s*$", re.MULTILINE)
    matches = list(regex.finditer(text))
    out = {}
    for i, m in enumerate(matches):
        tau = {"DDX": "tau1", "RED FLAG": "tau3", "MED REC": "tau4"}[m.group(1)]
        if tau not in fired:
            continue
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        body = text[start:end].strip()
        if body and body.lower() != "none":
            out[tau] = body
    return out


def main():
    if len(sys.argv) < 2:
        transcript = (
            "Forty-two year old woman with chest pain for three days, radiating to jaw, "
            "diabetic on metformin, just started ciprofloxacin from a neighbor, "
            "blood pressure 180 over 110."
        )
    else:
        transcript = sys.argv[1]

    print(f"[INPUT] {transcript}\n")
    rag = RagIndex()
    rag.load()
    weights = ensure_model(REASON_MODEL)
    model = cactus_init(str(weights), None, False)

    import time
    t0 = time.time()
    per_tau = top_per_tau(rag, transcript)
    print("[RAG top per τ]:")
    for tau, hits in per_tau.items():
        top = hits[0] if hits else None
        s = top["score"] if top else 0
        tid = top["id"] if top else "?"
        gate = GATE_THRESHOLDS[tau]
        fired = s >= gate
        print(f"  {tau}: {tid} score={s:.3f}  gate={gate}  {'FIRE' if fired else 'skip'}")

    fired = {
        tau for tau, hits in per_tau.items()
        if hits and hits[0]["score"] >= GATE_THRESHOLDS[tau]
    }
    if not fired:
        print("\n[no tasks fired]")
        cactus_destroy(model)
        rag.close()
        return

    t_llm = time.time()
    raw_text = compose_all(model, transcript, per_tau, fired)
    llm_ms = (time.time() - t_llm) * 1000
    sections = parse_sections(raw_text, fired)

    print(f"\n[LLM {llm_ms:.0f}ms]  fired={fired}")
    print(f"\n[RAW OUTPUT]\n{raw_text}\n")
    for tau in ("tau1", "tau3", "tau4"):
        if tau in sections:
            print(f"\n===== {tau.upper()} =====\n{sections[tau]}\n")

    total = (time.time() - t0) * 1000
    print(f"\n[total {total:.0f}ms]")
    cactus_destroy(model)
    rag.close()


if __name__ == "__main__":
    main()
