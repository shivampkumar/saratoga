"""Build RAG index from corpus/*.md. Run once.

Usage:
    cd ../cactus && source ./setup && cd ../saratoga
    python seed_corpus.py
"""
from rag import RagIndex


def main():
    rag = RagIndex()
    rag.build(verbose=True)
    rag.close()
    # smoke test
    rag2 = RagIndex()
    hits = rag2.query("can i refuse search of my car", k=3)
    for h in hits:
        print(f"  [{h['score']:.3f}] {h['topic']}/{h['id']}: {h['text'][:80]}...")
    rag2.close()


if __name__ == "__main__":
    main()
