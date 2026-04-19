# Saratoga — pitch outline

Working notes toward a demo presentation. Placeholder content. Final slides live elsewhere.

---

## Slide 1 — overview

- Working title: Saratoga
- One-line framing: on-device voice clinical co-pilot
- Demo device: Galaxy S26 Ultra, airplane mode

---

## Slide 2 — problem framing

Broad framing around deployment contexts where cloud clinical-AI is unavailable (connectivity, policy, privilege). Specifics TBD.

---

## Slide 3 — live demo

Live voice-in / on-device-out demo. Script still being finalized.

Flow (approximate):
1. Activate airplane mode on demo device.
2. Tap record; speak a clinically framed transcript.
3. Show on-device outputs populate across task panes.
4. Show offline queue buffered; reconcile when network returns.

---

## Slide 4 — architecture

Reference pipeline (see README for current stack):

```
mic → ASR → embedder → RAG → reasoning model → task-partitioned outputs → local store → sync on reconnect
```

Pre-quantized on-device models via Cactus. No cloud inference for the primary path.

---

## Slide 5 — moat

TBD — under revision. See research section in appendix.

---

## Slide 6 — team + ask

- Contributor: @shivampkumar
- Ask: standard hackathon prize track participation

---

## Appendix (work-in-progress)

- A-1: evaluation plan — pending
- A-2: regulatory posture — pending
- A-3: roadmap — pending
- A-4: technical depth — see README

---

## Demo safety net

- Short screen-recorded backup clip to cover live-demo risk.
- CLI fallback if the mobile path stalls.

---

*Final presentation materials are tracked privately.*
