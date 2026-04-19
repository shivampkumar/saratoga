# Saratoga — pitch deck
### 5 min demo + 60 s pitch. Track: Best On-Device Enterprise Agent (B2B).

Paste into Keynote / Google Slides. One concept per slide. Speaker notes marked `SN:`.

---

## Slide 1 — Hook (10 s)

### Title
**SARATOGA**

### Subtitle
on-device voice clinical co-pilot for the 40 % of encounters the cloud can't reach

### Visual
Full-screen airplane mode icon + map of LMIC / rural / carceral / combat zones highlighted.

### SN
*"Abridge, Glass, Corti. Real-time clinical AI. All three are cloud-only. They are structurally blocked from 40 % of the world's clinical encounters."*

---

## Slide 2 — Problem (30 s)

### Headline
**The cloud excludes half the world's clinicians.**

### Three bullets (one line each, big type)
- Rural + LMIC primary care — 2.4 M global physician shortfall (WHO)
- US correctional health — security policy blocks external SaaS
- Combat medics / forward surgical — EMCON + denied comms
- Ambulance / EMS — LTE dead zones, tunnels
- Disaster response — infrastructure down by definition

### Visual
Map: Abridge / Glass / Corti headquarters pins vs. global clinics without reliable bandwidth overlay.

### SN
*"Today, if you're a rural nurse in Nigeria, a jail physician in Ohio, a combat medic in denied comms, or an EMT in a tunnel — you have a blank screen while the patient dies."*

---

## Slide 3 — Live demo (90 s — **the kill shot**)

### Script for live demo

1. **[0 s]** Hand judge a Galaxy S26 Ultra. Pull up the Saratoga app. Toggle airplane mode ON. Point at the red ✈ icon in status bar.

2. **[5 s]** Tap **● RECORD**. Speak this verbatim, clinically flat:

   > "42 year old woman with chest pain for three days radiating to jaw, diabetic on metformin, just started ciprofloxacin from a neighbor, blood pressure 180 over 110."

3. **[35 s]** Tap **■ STOP**. Within **1 s**, three ⚡ quick-fire previews land:
   - τ1 blue: `⚡ quick [ddx_acs s=0.79]` + preview
   - τ3 red: `⚡ quick [rf_hypertensive_emergency s=0.74]`
   - τ4 green: `⚡ quick [med_cipro_qt s=0.68]`

4. **[40-55 s]** E4B streams tokens into all three panes, live. Point at the streaming text.
   - τ1: "ACS (MI) · Hypertensive emergency · Aortic dissection — MUST-RULE-OUT: ACS, Aortic dissection, PE"
   - τ3: "ALERT: Hypertensive emergency. ACTION: nicardipine IV. Reduce MAP 20-25 % / 1h. Monitor end-organ damage."
   - τ4: "MED FLAG: Cipro + DM + no ECG. RISK: QT prolongation, torsades. ACTION: check ECG, correct K/Mg, hold ASA missing for ACS suspicion."

5. **[60 s]** Scroll to **FHIR QUEUE · 1 queued**. Point. *"One Bundle, never touched the cloud."*

6. **[70 s]** Flip airplane mode **OFF**. Tap **SYNC 1**. Status: *"POST /Bundle × 1 → HAPI stub..."* → *"✓ synced 1 Bundle(s) → HAPI stub"*.

7. **[85 s]** *"That's the entire product. Cloud competitors showed a blank screen for the last 85 seconds."*

### SN
Anchor everything to one moment: *airplane mode icon visible in the status bar the whole time*. Rehearse 3 ×.

---

## Slide 4 — Architecture (45 s)

### Headline
**Four models on one device.**

### Visual
```
mic → Moonshine (60M) → transcript
         ↓
  Qwen3-Embedding (0.6B) → τ-partitioned cosine top-k
         ↓
   Stage 1: RAG quick-fire → card previews in <1 s
         ↓
   Stage 2: Gemma-4 E4B streaming → τ1 / τ3 / τ4 panes
         ↓
  functiongemma-270m → structured FHIR extraction
         ↓
  FHIR-lite SQLite + append-only queue + SHA-chained evidence log
         ↓
  Network detected → POST Bundle → reconcile 409s
```

### One-liner
Cactus engine · Gemma 4 E4B multimodal · functiongemma tool-calling · Qwen3-Embedding RAG · Moonshine ASR. Zero cloud egress for core path.

### SN
*"This is the deepest technical integration in the hackathon. Four models, one phone, offline. Stage 1 is sub-second RAG; Stage 2 is Gemma-4 streaming. FHIR queue drains when the network returns."*

---

## Slide 5 — Moat (45 s)

### Headline
**Abridge can't copy this. Their entire stack is cloud inference.**

### Competitive table

| | Saratoga | Abridge | Glass | Corti | MSF PDF | MedGemma |
|---|---|---|---|---|---|---|
| Real-time CDS | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| **Offline** | **✅** | ❌ | ❌ | ❌ | ✅ static | N/A |
| Voice-driven | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ |
| Must-rule-out / don't-miss | ✅ | partial | ✅ | ✅ | ❌ | ❌ |
| Med rec + interactions | ✅ | ❌ | ❌ | ❌ | ref | ❌ |
| FHIR offline-buffered | ✅ | cloud | ❌ | cloud | ❌ | ❌ |
| LMIC / correctional / combat | ✅ | ❌ | ❌ | ❌ | ✅ no-AI | ❌ |
| Peer-reviewed foundation | ✅ **AMIA 2025** | ❌ | ❌ | partial | ❌ | ❌ |

### Three moats, one slide
1. **Structural:** cloud competitors would need 18 months to re-architect inference
2. **Regulatory:** 21 st Cent. Cures Act § 3060 CDS carve-out — same posture as UpToDate
3. **Academic:** *Survivorship Navigator* architecture (Pradeepkumar, **Kumar SP**, *et al.* AMIA 2025, PMC12919410)

### SN
*"The moat is not the model. Gemma 4 is public. The moat is that the product functionally exists when every cloud competitor shows a blank screen."*

---

## Slide 6 — Market + ask (30 s)

### Headline
**$30 B clinical-AI market. One category competitors can't reach.**

### Revenue shape (bullet, big type)
- **B2B SaaS** · MSF & NGO clinics · 100 USD / clinician / month
- **B2G** · US county-jail health systems · VA & DoD forward surgical
- **B2B2C** · ambulance & EMS fleets · 500 USD / rig / year
- **Licensing** · medical-device OEMs integrating the engine

### TAM
- 1.3 M US clinicians + 2.4 M global shortfall countries
- Abridge funded at 27 B USD rumored (2026). Clean comp.

### Team
**@shivampkumar** — co-author, AMIA 2025 *Survivorship Navigator* · co-author npj Digital Medicine 2025 *FairPlay* · Northwestern Feinberg informatics · AI-tinkerer.

### Ask
- YC interview (winner-takes-all prize)
- 150 k GCP credits → host the HAPI FHIR endpoint + fine-tune on CORAL

### Closing line (literal)
*"Abridge showed you a blank screen for the last 4 minutes. We showed you a clinical co-pilot. That's the entire pitch."*

---

## Appendix slides (keep as backup if judges dig)

### A-1 — Eval numbers
- CORAL 40-note expert-annotated baseline (Sushil et al. NEJM AI 2024)
- Expert-rated correctness target: **71.1 %** (vs Gemma 2 9 B in Survivorship Navigator paper)
- A/B vs cloud Gemini 2.5 Flash (judge: GPT-5) — win-rate target ≥ 40 %

### A-2 — Regulatory
- 21 st Cent. Cures Act § 3060 CDS carve-out (FDA CDS guidance, Sept 2022)
- HIPAA-friendly by construction (on-device + no BAA for core)
- Joint Commission NPSG.03.06.01 — med rec is mandated

### A-3 — Roadmap
- v0.2 — τ2 (history Qs) + τ5 (multilingual teach-back)
- v0.3 — QNN / Hexagon NPU → 3-5 × latency
- v0.4 — iOS via Cactus Swift + Apple Neural Engine (prefill 660 tps target per Cactus M5 bench)
- v0.5 — pilot with MSF, US jail health, FEMA
- v0.6 — FairPlay generative balancing for underrepresented populations (npj Dig Med 2025, team co-author)

### A-4 — Technical depth (if Deepest-Technical-Integration track judges ask)
4 on-device models in a single multimodal inference graph:
- `Moonshine-base` 60 M INT4 — edge ASR
- `Qwen3-Embedding-0.6B` INT4 — multilingual retriever
- `functiongemma-270m-it` INT4 — tool-calling
- `Gemma 4 E4B-it` INT4 — reasoner, 128 K context

Plus: SHA-chained append-only evidence log · airplane-mode enforcement · Cactus v1.14 NPU-aware runtime.

---

## Demo safety net

If the live demo fails:
1. **Backup 30 s video loop** playing on the judge's laptop. Record it tonight.
2. **Mac CLI** as pre-recorded alternative: `python clinical.py "42yo woman chest pain..."` reads same output.
3. **Pre-made FHIR bundle screenshot** to show if sync button hangs.

Rehearse 3 × before going up.
