"""Routing + structured evidence extraction.

Intent gating = embedding cosine vs ACLU corpus (fast, already indexed).
  - top-1 score > LEGAL_GATE -> legal_lookup
  - keyword match on panic lexicon -> panic
  - else -> ambient

Evidence extraction = functiongemma-270m tool call on confirmed-legal utterances.
Pulls out {demand_type, surface, modality} for structured incident log.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CACTUS_ROOT = ROOT.parent / "cactus"
sys.path.insert(0, str(CACTUS_ROOT / "python"))

from src.cactus import cactus_complete, cactus_destroy, cactus_init  # type: ignore
from src.downloads import ensure_model  # type: ignore

EXTRACT_MODEL = "google/functiongemma-270m-it"

LEGAL_GATE = 0.55  # cosine threshold on normalized vectors

PANIC_LEXICON = {
    "gun", "firearm", "weapon", "on the ground", "get down",
    "you're under arrest", "in custody", "hands behind",
}


EXTRACT_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "log_authority_demand",
            "description": (
                "Extract structured fields from an authority figure's statement for "
                "evidence log."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "demand_type": {
                        "type": "string",
                        "enum": [
                            "question", "request_consent", "order_to_comply",
                            "request_id", "threat_of_force",
                        ],
                    },
                    "surface": {
                        "type": "string",
                        "enum": [
                            "person", "vehicle", "belongings", "home", "phone", "none",
                        ],
                    },
                    "rights_implicated": {
                        "type": "string",
                        "description": "Short: which right is at stake (e.g. 'fourth amendment consent')",
                    },
                },
                "required": ["demand_type", "surface"],
            },
        },
    }
]

EXTRACT_SYSTEM = (
    "Extract structured fields from what an authority figure just said. "
    "Always emit exactly one log_authority_demand call."
)


def check_panic(utterance: str) -> str | None:
    u = utterance.lower()
    for phrase in PANIC_LEXICON:
        if phrase in u:
            return phrase
    return None


def gate_by_rag(rag, utterance: str, threshold: float = LEGAL_GATE) -> tuple[bool, list[dict]]:
    hits = rag.query(utterance, k=3)
    if hits and hits[0]["score"] >= threshold:
        return True, hits
    return False, hits


class Extractor:
    def __init__(self):
        weights = ensure_model(EXTRACT_MODEL)
        self.model = cactus_init(str(weights), None, False)

    def extract(self, utterance: str) -> dict:
        messages = json.dumps(
            [
                {"role": "system", "content": EXTRACT_SYSTEM},
                {"role": "user", "content": utterance},
            ]
        )
        opts = json.dumps({"max_tokens": 64, "temperature": 0.0})
        raw = cactus_complete(
            self.model, messages, opts, json.dumps(EXTRACT_TOOLS), None, None
        )
        result = json.loads(raw)
        calls = result.get("function_calls") or []
        if not calls:
            return {"demand_type": "question", "surface": "none"}
        return calls[0].get("arguments", {})

    def close(self):
        cactus_destroy(self.model)
