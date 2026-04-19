<img src="assets/banner.png" alt="Saratoga" style="border-radius: 20px; width: 60%;">

# Saratoga

**On-device voice clinical co-pilot for clinicians who work where the cloud cannot reach.**

Built for the **Cactus × Google DeepMind × Y Combinator — Gemma 4 Voice Agents Hackathon** (Apr 19 2026).
Track: **Best On-Device Enterprise Agent (B2B)** · strong candidate for **Deepest Technical Integration** via 4-model on-device stack.

---

## Why this exists

Abridge, Glass Health, and Corti ship real-time clinical decision support **exclusively as cloud SaaS**. That architecture **structurally excludes ~40% of global clinical encounters**: rural and LMIC primary care, correctional health, combat casualty care in EMCON, disaster response, and LTE-dark ambulances and tunnels.

Saratoga runs **Gemma 4 E4B** on **Cactus** directly on the clinician's phone, performs three clinical co-pilot tasks against a locally-cached evidence base, writes FHIR resources to a local store, and syncs when (if) the network returns.

> **The moat is not the model. The moat is that the product functionally exists when every cloud competitor is a blank screen.**

---

## What it does

Three τ-tasks, fired in parallel by a single multimodal on-device call:

| τ | Task | Triggered when | Output |
|---|---|---|---|
| τ1 | **Differential + must-rule-outs** | chief complaint detected | ranked ddx with ACEP/Tintinalli/WHO IMCI-backed don't-miss items |
| τ3 | **Red-flag alerts** | vitals or danger signs mentioned | sepsis / STEMI / stroke / hypertensive-emergency / peds-danger / anaphylaxis / DKA / massive PE / GI bleed / meningitis |
| τ4 | **Medication reconciliation** | drug list read aloud | interactions, Beers elderly, renal dose, missing guideline-directed therapy |

Each output cites chunk IDs that the clinician can tap to reach the source guideline passage. All outputs write to a **FHIR-lite** local SQLite with an append-only queue that drains when the phone next sees a network.

---

## Demo — rural clinic encounter

**Transcript (speak into phone mic):**
> "42 year old woman with chest pain for three days radiating to jaw, diabetic on metformin, just started ciprofloxacin from a neighbor, blood pressure 180 over 110."

**Phone response, airplane-mode ON:**
- **τ1 DDX**: 1. ACS [ddx_acs] 2. Hypertensive emergency [ddx_aortic_dissection] 3. Aortic dissection — *MUST-RULE-OUT: ACS, Aortic dissection, PE*
- **τ3 RED FLAG**: Hypertensive emergency [rf_hypertensive_emergency]. Action: nicardipine, reduce MAP 20–25% / 1h, monitor end-organ damage.
- **τ4 MED REC**: Cipro + QT-prolong risk with DM context; hold metformin if CT indicated; ASA not yet given for ACS suspicion.
- **FHIR QUEUE**: 1 Bundle (Encounter + ClinicalImpression) buffered.

Flip airplane-mode OFF → tap **SYNC** → bundle posts to HAPI stub, reconciled.

---

## Architecture

```
┌─────────────────────────── clinician's phone (offline-first) ───────────────────────────┐
│                                                                                         │
│   mic  →  AudioRecord (16kHz PCM)                                                       │
│              ↓                                                                          │
│   Moonshine-base (60M, int4)  →  transcript                                             │
│              ↓                                                                          │
│   Qwen3-Embedding-0.6B  →  τ-partitioned cosine top-k over local ACEP/WHO/Beers chunks  │
│              ↓                                                                          │
│   Stage 1 (sub-second): RAG top-hit per τ  →  instant "⚡ quick" card preview            │
│              ↓                                                                          │
│   Stage 2 (~20s): Gemma 4 E4B single streaming call  →  parsed into τ1/τ3/τ4 panes     │
│              ↓                                                                          │
│   functiongemma-270m  →  structured field extraction for FHIR write                     │
│              ↓                                                                          │
│   FHIR-lite SQLite + append-only sync queue + SHA-chained evidence log                  │
│              ↓                                                                          │
│   Network detected? → POST Bundle to configured FHIR endpoint → reconcile 409s          │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### On-device stack

| Layer | Choice | Why |
|---|---|---|
| Inference engine | **Cactus v1.14** (hackathon mandate) | NPU-aware, ARM NEON/i8mm, day-one Gemma 4 |
| Reasoning | `google/gemma-4-E4B-it` INT4 (8.1 GB) | Haiku-class reasoning; vision+audio native |
| Router / extractor | `google/functiongemma-270m-it` INT4 (266 MB) | Instant function-calling |
| Embeddings | `Qwen/Qwen3-Embedding-0.6B` INT4 | Multilingual; unified with retriever |
| ASR | `UsefulSensors/moonshine-base` INT4 | Edge-optimized |
| Vector store | in-memory float32 cosine over pre-indexed chunks | Sub-1 ms query |
| FHIR store | SQLite + append-only JSONL queue | Reconciliation-safe |
| Platform | Android (Kotlin + JNI → libcactus.so) on Galaxy S26 Ultra (SD 8 Elite Gen 5) | Demo device |

### Corpus (pre-loaded, <500 MB target)

ACEP Acute Chest Pain Clinical Policy · Tintinalli's don't-miss list · WHO IMCI · MSF Clinical Guidelines · TCCC · AGS 2023 Beers · WHO Essential Medicines · Joint Commission NPSG.03.06.01.

---

## Regulatory posture

- **Clinical decision support aid, not a diagnostic device.** Clinician-in-loop, non-autonomous.
- Falls under **21st Century Cures Act §3060 CDS carve-out** from FDA SaMD classification, provided the clinician can review the basis for each recommendation. Saratoga satisfies this because every output cites a retrieved chunk.
- Same legal posture as UpToDate or DynaMed.
- On-device inference + zero cloud egress = **HIPAA-friendly by construction**; no BAA required for core product.
- Correctional and DoD deployments specifically benefit from zero-egress.

---

## Competitive moat

| Capability | Saratoga | Abridge | Glass | Corti | MSF PDF app | MedGemma |
|---|---|---|---|---|---|---|
| Real-time CDS during encounter | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| **Works fully offline** | ✅ | ❌ | ❌ | ❌ | ✅ (static) | N/A |
| Voice-driven | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ |
| Must-rule-out / don't-miss logic | ✅ | partial | ✅ | ✅ | ❌ | ❌ |
| Med reconciliation + interactions | ✅ | ❌ | ❌ | ❌ | ref only | ❌ |
| FHIR write, offline-buffered | ✅ | cloud | ❌ | cloud | ❌ | ❌ |
| Deployable in correctional / combat / LMIC | ✅ | ❌ | ❌ | ❌ | ✅ (no AI) | ❌ |
| Peer-reviewed architectural foundation | ✅ AMIA 2025 | ❌ | ❌ | partial | ❌ | ❌ |

Saratoga's architecture is the *Survivorship Navigator* pattern (Pradeepkumar A, **Kumar SP** *et al.*, AMIA 2025) — multimodal LLM parse → task-specific retrieval → structure-to-JSON → task-prompt with in-context examples — re-pointed at clinical guideline corpora instead of cancer survivorship.

---

## Run locally (Mac iteration loop)

```bash
# 1. Cactus setup
git clone https://github.com/cactus-compute/cactus
cd cactus && source ./setup

# 2. Download models
cactus download google/gemma-4-E4B-it
cactus download google/functiongemma-270m-it
cactus download Qwen/Qwen3-Embedding-0.6B
cactus download UsefulSensors/moonshine-base

# 3. Index the corpus + iterate prompts on Mac
cd .. && git clone git@github.com:shivampkumar/saratoga.git && cd saratoga
source ../cactus/venv/bin/activate
python seed_corpus.py
python clinical.py "42yo woman chest pain 3 days ..."
```

## Build and deploy on Android

```bash
# On Mac — build libcactus.so for Android (requires Android NDK)
cd cactus && source ./setup && cactus build --android
cp cactus/android/libcactus.so saratoga/android/app/src/main/jniLibs/arm64-v8a/
cp cactus/android/Cactus.kt saratoga/android/app/src/main/java/com/cactus/

# On Mac — build APK + push weights to phone
cd saratoga/android
./gradlew assembleDebug
./deploy.sh   # APK install + one-time push of ~8.6 GB weights to /sdcard/Android/data/com.saratoga/files/weights
```

Subsequent iterations: `./gradlew assembleDebug && ./deploy.sh` (APK-only push; weights persist through `install -r`).

---

## Roadmap

- **v0.1 (hackathon MVP, this repo):** τ1 + τ3 + τ4, Android, airplane-mode demo, FHIR queue.
- **v0.2:** τ2 (history Qs) and τ5 (multilingual patient handout + teach-back).
- **v0.3:** QNN / Hexagon NPU offload via Cactus for 3–5× phone latency.
- **v0.4:** iOS (Cactus Swift + Apple Neural Engine for vision/audio encoders).
- **v0.5:** pilot deployments — MSF clinic in rural Nigeria, one US county jail health system, one FEMA disaster-response unit.
- **v0.6:** FairPlay-style generative balancing for underrepresented populations (npj Digital Medicine 2025, team co-author).

---

## Citations

1. **Architectural foundation:** Pradeepkumar A, **Kumar SP**, Reamer E, Dreyer N, Patel R, Liebovitz DM, Sun J. *Survivorship Navigator: A multimodal LLM-powered clinical care interface.* AMIA 2025 Informatics Summit. PMC12919410.
2. **Chest-pain must-rule-outs:** ACEP Clinical Policy: Acute Chest Pain.
3. **Teach-back:** AHRQ Health Literacy Universal Precautions Toolkit, Tool 5.
4. **Med reconciliation:** The Joint Commission NPSG.03.06.01.
5. **CDS carve-out:** 21st Century Cures Act §3060; FDA CDS Software guidance (Sept 2022).
6. **LMIC workforce gap:** WHO *World Health Report 2006* + *Bulletin WHO* 87(3):225 — 2.4 M global physician/nurse/midwife shortfall.
7. **Cactus engine:** Ndubuaku H et al. *Cactus: AI Inference Engine for Phones & Wearables*. github.com/cactus-compute/cactus.

---

Built by [@shivampkumar](https://github.com/shivampkumar) for the Cactus × Google DeepMind × Y Combinator hackathon, April 19 2026.
