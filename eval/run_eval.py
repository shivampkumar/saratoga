"""Run 3 demo scenarios through clinical pipeline, report paper-style numbers.

Usage:
    cd saratoga
    source ../cactus/venv/bin/activate
    python eval/run_eval.py

Writes eval/results.json + prints deck-ready summary.
"""
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "eval"))

from transcripts import SCENARIOS  # type: ignore

# Lazy imports so help runs fast
def run():
    from clinical import GATE_THRESHOLDS, compose_all, parse_sections, top_per_tau  # type: ignore
    from rag import RagIndex  # type: ignore

    sys.path.insert(0, str((ROOT.parent / "cactus" / "python").resolve()))
    from src.cactus import cactus_destroy, cactus_init  # type: ignore
    from src.downloads import ensure_model  # type: ignore

    rag = RagIndex()
    rag.load()
    weights = ensure_model("google/gemma-4-E4B-it")
    model = cactus_init(str(weights), None, False)

    results = []
    for sc in SCENARIOS:
        print(f"\n=== {sc['id']}: {sc['description']} ===")
        t0 = time.time()
        per_tau = top_per_tau(rag, sc["transcript"])
        fired = {
            t for t, hs in per_tau.items()
            if hs and hs[0]["score"] >= GATE_THRESHOLDS[t]
        }
        gate_ms = (time.time() - t0) * 1000

        top_chunks = {t: (per_tau.get(t, [{}])[0].get("id"), per_tau.get(t, [{}])[0].get("score", 0))
                      for t in ("tau1", "tau3", "tau4")}

        # Per-τ fire accuracy
        expected = sc["expected_fires"]
        correct_fire = fired == expected
        extra = fired - expected
        missing = expected - fired

        # Per-τ top-chunk match: is top hit in expected list?
        chunk_match = {}
        for t in expected:
            top_id = top_chunks.get(t, (None, 0))[0]
            exp_ids = sc["expected_top_chunks"].get(t, [])
            chunk_match[t] = top_id in exp_ids

        # Full E4B pass
        llm_t = time.time()
        if fired:
            raw = compose_all(model, sc["transcript"], per_tau, fired)
            sections = parse_sections(raw, fired)
        else:
            sections = {}
        llm_ms = (time.time() - llm_t) * 1000

        result = {
            "scenario": sc["id"],
            "description": sc["description"],
            "gate_ms": round(gate_ms, 1),
            "llm_ms": round(llm_ms, 1),
            "total_ms": round(gate_ms + llm_ms, 1),
            "fired": sorted(fired),
            "expected_fires": sorted(expected),
            "correct_fire_set": correct_fire,
            "extra_fires": sorted(extra),
            "missing_fires": sorted(missing),
            "top_chunks": {t: {"id": v[0], "score": round(v[1], 3)} for t, v in top_chunks.items()},
            "top_chunk_match": chunk_match,
            "sections": {t: s[:800] for t, s in sections.items()},
        }
        results.append(result)

        print(f"  gate={gate_ms:.0f}ms  llm={llm_ms:.0f}ms  total={gate_ms+llm_ms:.0f}ms")
        print(f"  fired={sorted(fired)}  expected={sorted(expected)}  correct_set={correct_fire}")
        print(f"  top_chunk_match={chunk_match}")

    cactus_destroy(model)
    rag.close()

    # Roll-up
    n = len(results)
    n_correct_fires = sum(1 for r in results if r["correct_fire_set"])
    n_chunk_total = sum(len(r["top_chunk_match"]) for r in results)
    n_chunk_correct = sum(v for r in results for v in r["top_chunk_match"].values())
    mean_total_ms = sum(r["total_ms"] for r in results) / n

    summary = {
        "n_scenarios": n,
        "pct_correct_fire_set": round(100 * n_correct_fires / n, 1),
        "pct_top_chunk_match": round(100 * n_chunk_correct / n_chunk_total, 1),
        "mean_total_ms": round(mean_total_ms, 0),
    }

    out = ROOT / "eval" / "results.json"
    out.write_text(json.dumps({"results": results, "summary": summary}, indent=2))
    print(f"\n{'='*60}\nSUMMARY (n={n} scenarios)\n{'='*60}")
    print(f"  correct-fire-set rate: {summary['pct_correct_fire_set']}%")
    print(f"  top-chunk match rate:  {summary['pct_top_chunk_match']}%")
    print(f"  mean total latency:    {summary['mean_total_ms']} ms")
    print(f"\n  → {out}")


if __name__ == "__main__":
    run()
