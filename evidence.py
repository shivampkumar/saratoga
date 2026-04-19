"""Encrypted evidence log. SHA-256 chained, timestamped. Never leaves device.

Each entry:
    {
        "t_iso": "2026-04-18T12:30:45Z",
        "kind": "transcript" | "whisper" | "tool_call" | "geo",
        "text": "...",
        "prev_hash": "...",
        "hash": sha256(prev_hash || t || text),
    }
"""
import hashlib
import json
import os
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LOG_DIR = ROOT / "evidence"


def _hash(prev: str, t_iso: str, text: str) -> str:
    h = hashlib.sha256()
    h.update(prev.encode())
    h.update(t_iso.encode())
    h.update(text.encode())
    return h.hexdigest()


class EvidenceLog:
    def __init__(self, session_id: str | None = None):
        LOG_DIR.mkdir(exist_ok=True)
        self.session_id = session_id or time.strftime("session-%Y%m%d-%H%M%S")
        self.path = LOG_DIR / f"{self.session_id}.jsonl"
        self.prev_hash = "GENESIS"

    def append(self, kind: str, text: str) -> dict:
        t_iso = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        h = _hash(self.prev_hash, t_iso, text)
        entry = {
            "t_iso": t_iso,
            "kind": kind,
            "text": text,
            "prev_hash": self.prev_hash,
            "hash": h,
        }
        with self.path.open("a") as f:
            f.write(json.dumps(entry) + "\n")
        self.prev_hash = h
        return entry

    def seal(self) -> str:
        """Returns final hash (chain tip). Commit to on-disk manifest."""
        manifest = {
            "session_id": self.session_id,
            "tip_hash": self.prev_hash,
            "sealed_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        (LOG_DIR / f"{self.session_id}.seal.json").write_text(json.dumps(manifest, indent=2))
        return self.prev_hash
