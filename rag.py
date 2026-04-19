"""Local RAG over ACLU corpus. Uses Cactus embedding model + numpy cosine.

Corpus files: corpus/*.md with chunks delimited by '---' and anchor line '## name'.
"""
import json
import re
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent
CACTUS_ROOT = ROOT.parent / "cactus"
sys.path.insert(0, str(CACTUS_ROOT / "python"))

from src.cactus import cactus_embed, cactus_init, cactus_destroy  # type: ignore
from src.downloads import ensure_model  # type: ignore

EMBED_MODEL = "Qwen/Qwen3-Embedding-0.6B"
CORPUS_DIR = ROOT / "corpus"
INDEX_PATH = ROOT / "corpus_index.npz"


def _parse_chunks(md_text: str) -> list[dict]:
    """Split a corpus markdown file into chunks by '---' separator."""
    chunks = []
    for block in md_text.split("\n---"):
        block = block.strip()
        m = re.search(r"^##\s+(\S+)", block, re.M)
        if not m:
            continue
        name = m.group(1).strip()
        # body = text after the heading line
        body = re.sub(r"^##\s+\S+\s*\n", "", block, count=1, flags=re.M).strip()
        if not body:
            continue
        chunks.append({"id": name, "text": body})
    return chunks


def load_corpus_chunks() -> list[dict]:
    out = []
    for path in sorted(CORPUS_DIR.glob("*.md")):
        topic = path.stem.replace("aclu_", "").replace("_", " ")
        for chunk in _parse_chunks(path.read_text()):
            chunk["topic"] = topic
            chunk["source"] = path.name
            out.append(chunk)
    return out


class RagIndex:
    def __init__(self):
        self._model = None
        self.chunks: list[dict] = []
        self.embeddings: np.ndarray | None = None

    def _ensure_model(self):
        if self._model is None:
            weights = ensure_model(EMBED_MODEL)
            self._model = cactus_init(str(weights), None, False)

    def embed(self, text: str) -> np.ndarray:
        self._ensure_model()
        v = cactus_embed(self._model, text, True)
        return np.array(v, dtype=np.float32)

    def build(self, verbose: bool = True) -> None:
        self.chunks = load_corpus_chunks()
        if not self.chunks:
            raise RuntimeError(f"No corpus chunks found in {CORPUS_DIR}")
        vecs = []
        for i, chunk in enumerate(self.chunks):
            if verbose:
                print(f"[rag] embed {i+1}/{len(self.chunks)}: {chunk['id']}")
            vecs.append(self.embed(chunk["text"]))
        self.embeddings = np.stack(vecs)
        meta = [{"id": c["id"], "text": c["text"], "topic": c["topic"], "source": c["source"]}
                for c in self.chunks]
        np.savez(INDEX_PATH, embeddings=self.embeddings,
                 meta=np.array(json.dumps(meta), dtype=object))
        if verbose:
            print(f"[rag] saved {len(self.chunks)} chunks -> {INDEX_PATH}")

    def load(self) -> None:
        if not INDEX_PATH.exists():
            raise FileNotFoundError(f"Index not found: {INDEX_PATH}. Run: python seed_corpus.py")
        data = np.load(INDEX_PATH, allow_pickle=True)
        self.embeddings = data["embeddings"]
        self.chunks = json.loads(str(data["meta"]))

    def query(self, text: str, k: int = 3, topic_filter: str | None = None) -> list[dict]:
        if self.embeddings is None:
            self.load()
        q = self.embed(text)
        # cosine since both normalized
        sims = self.embeddings @ q
        if topic_filter:
            mask = np.array([c["topic"] == topic_filter for c in self.chunks])
            sims = np.where(mask, sims, -1)
        idx = np.argsort(-sims)[:k]
        return [{**self.chunks[i], "score": float(sims[i])} for i in idx]

    def close(self):
        if self._model is not None:
            cactus_destroy(self._model)
            self._model = None
