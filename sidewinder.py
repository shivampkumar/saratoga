"""Sidewinder — on-device rights co-pilot.

Multi-model pipeline per utterance:
  1. Panic lexicon check (sub-ms).
  2. RAG embedding gate (Qwen3-Embedding-0.6B): score > 0.45 -> legal path.
  3. Legal path: E4B composes whisper using retrieved ACLU chunks +
     functiongemma-270m extracts structured demand fields for evidence log.
  4. Everything SHA-chained into encrypted evidence log. No network.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CACTUS_ROOT = ROOT.parent / "cactus"
sys.path.insert(0, str(CACTUS_ROOT / "python"))

from src.cactus import cactus_complete, cactus_destroy, cactus_init  # type: ignore
from src.downloads import ensure_model  # type: ignore

from evidence import EvidenceLog
from rag import RagIndex
from router import Extractor, check_panic, gate_by_rag

REASON_MODEL = "google/gemma-4-E4B-it"

WHISPER_SYSTEM = (
    "You are Sidewinder, an on-device rights coach. Driver is in a live authority "
    "encounter. Given (a) what the authority said and (b) retrieved ACLU rights cards, "
    "emit ONE whisper the driver should hear or glance at. Max 10 words. Direct imperative. "
    "Examples: 'Refuse search: \"I do not consent.\"' or 'Remain silent; ask for lawyer.' "
    "End with [card_id]. If cards irrelevant, reply 'comply quietly'. No legal opinions."
)


def _compose_whisper(e4b_model: int, utterance: str, hits: list[dict]) -> str:
    corpus = "\n".join(f"- [{h['id']}] {h['text'][:220]}" for h in hits[:3])
    messages = json.dumps(
        [
            {"role": "system", "content": WHISPER_SYSTEM},
            {
                "role": "user",
                "content": (
                    f"Authority said: \"{utterance}\"\n\n"
                    f"ACLU cards:\n{corpus}\n\n"
                    "Emit one whisper."
                ),
            },
        ]
    )
    opts = json.dumps({"max_tokens": 40, "temperature": 0.2})
    raw = cactus_complete(e4b_model, messages, opts, None, None, None)
    result = json.loads(raw)
    return result.get("response", "").strip()


class Sidewinder:
    def __init__(self, session_id: str | None = None):
        print("[sidewinder] loading RAG + embedder...")
        self.rag = RagIndex()
        self.rag.load()
        print("[sidewinder] loading E4B...")
        weights = ensure_model(REASON_MODEL)
        self.e4b = cactus_init(str(weights), None, False)
        print("[sidewinder] loading functiongemma extractor...")
        self.extractor = Extractor()
        self.evidence = EvidenceLog(session_id)

    def handle(self, utterance: str) -> dict:
        self.evidence.append("transcript", f"authority: {utterance}")

        panic_phrase = check_panic(utterance)
        if panic_phrase:
            self.evidence.append("panic", f"matched: {panic_phrase}")
            whisper = "Do not resist. Stay calm. Say: 'I invoke my right to silent.'"
            self.evidence.append("whisper", whisper)
            return {"intent": "panic", "panic_phrase": panic_phrase, "whisper": whisper}

        is_legal, hits = gate_by_rag(self.rag, utterance)
        if not is_legal:
            return {
                "intent": "ambient",
                "top_score": hits[0]["score"] if hits else 0.0,
            }

        structured = self.extractor.extract(utterance)
        self.evidence.append("extracted", json.dumps(structured))
        whisper = _compose_whisper(self.e4b, utterance, hits)
        self.evidence.append("whisper", whisper)

        return {
            "intent": "legal_lookup",
            "hits": [{"id": h["id"], "score": h["score"]} for h in hits],
            "structured": structured,
            "whisper": whisper,
        }

    def seal(self) -> dict:
        tip = self.evidence.seal()
        return {
            "session_id": self.evidence.session_id,
            "tip_hash": tip,
            "path": str(self.evidence.path),
        }

    def close(self):
        cactus_destroy(self.e4b)
        self.extractor.close()
        self.rag.close()
