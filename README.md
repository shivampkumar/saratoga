<img src="assets/banner.png" alt="Saratoga" style="border-radius: 20px; width: 60%;">

# Saratoga

**On-device voice clinical co-pilot for clinicians who work where the cloud cannot reach.**

Built for the **Cactus × Google DeepMind × Y Combinator — Gemma 4 Voice Agents Hackathon** (Apr 19 2026).
Track: **Best On-Device Enterprise Agent (B2B)** · strong candidate for **Deepest Technical Integration** (four on-device models in one pipeline).

---

## Scientific foundation

Saratoga is a direct instantiation of our peer-reviewed paper:

> Pradeepkumar A, **Kumar SP**, Reamer E, Dreyer N, Patel R, Liebovitz DM, Sun J. *Survivorship Navigator: Personalized Survivorship Care Plan Generation using Large Language Models.* **AMIA 2025 Informatics Summit**. [PMC12919410](https://pmc.ncbi.nlm.nih.gov/articles/PMC12919410/)

The paper validated a **two-step structure-to-JSON → τ-task** pattern with **RAG + ColBERTv2 reranker** on the **CORAL** dataset (40 expert-annotated breast + pancreatic progress notes). **Gemma 2 9B hit 71.1 % expert-rated correctness**; GPT-4o hit 90.15 %. Saratoga ports this pattern to **Gemma 4 E2B / E4B** on **Cactus**, running on-device, with τ-specific retrieval over clinical guideline chunks.

---

## Why this exists

Abridge, Glass Health, and Corti ship real-time clinical decision support **exclusively as cloud SaaS**. That architecture **structurally excludes ~40 % of global clinical encounters**: rural and LMIC primary care, correctional health, combat casualty care in EMCON, disaster response, and LTE-dark ambulances and tunnels.

Saratoga runs **Gemma 4** on **Cactus** directly on the clinician's phone, performs three clinical co-pilot tasks against a locally-cached evidence base, writes FHIR resources to a local store, and syncs when (if) the network returns.

> **The moat is not the model. The moat is that the product functionally exists when every cloud competitor is a blank screen.**

---

## What it does

Three τ-tasks (τ1, τ3, τ4 — mapped from the paper's five-task architecture), fired in parallel by a single multimodal on-device call over an **accumulated speaker-tagged encounter transcript**:

| τ | Task | Triggered when | Output |
|---|---|---|---|
| τ1 | **Differential + must-rule-outs** | chief complaint detected | ranked ddx with ACEP / Tintinalli / WHO IMCI / NCCN Survivorship-backed don't-miss items |
| τ3 | **Red-flag alerts** | vitals or danger signs mentioned | sepsis / STEMI / stroke / hypertensive-emergency / peds-danger / anaphylaxis / DKA / PE / GI bleed / meningitis / chemo cardiotoxicity / rising CEA in survivor |
| τ4 | **Medication reconciliation** | drug list read aloud | interactions, Beers elderly, renal dose, tamoxifen–CYP2D6, ASA-discontinuation in cancer survivor, missing guideline-directed therapy |

Every output cites the retrieved chunk ID. Outputs write to a **FHIR-lite** local SQLite + append-only JSONL queue that drains to the endpoint when network returns.

---

## Demo — colorectal cancer survivor (paper-native scenario)

Spoken into phone microphone, airplane mode ON:

**Clinician** — *"Hey Mike, good to see you. How are you doing?"*
**Patient** — *"Alright, doc. It's been three years since the colon surgery."*
**Clinician** — *"Three years out from the stage two resection. Still on metformin?"*
**Patient** — *"Still on metformin. But I stopped my aspirin a couple months back after a bad nosebleed."*
**Clinician** — *"Anything else?"*
**Patient** — *"Haven't had a colonoscopy since the surgery. Some blood last week, figured it was hemorrhoids. CEA last month was four point two."*

Tap **END ENCOUNTER**. Phone fires:

- **τ1 DDX**: Local / distant recurrence · new primary · benign — cites `ddx_colorectal_recurrence`, NCCN Survivorship
- **τ3 RED FLAG**: Rising CEA + bleeding in post-resection patient — colonoscopy + CT A/P within 2 weeks — cites `rf_rising_cea_post_resection`
- **τ4 MED REC**: Self-discontinued aspirin + overdue surveillance — `med_asa_discontinued_cancer_survivor`
- **FHIR Bundle**: `Bundle#enc-… { Encounter · ClinicalImpression findings=3 }` buffered.

Flip airplane mode OFF → tap **SYNC FHIR** → animation: `POST /Bundle → 201 Created → reconciled`.

---

## Architecture

```
┌─────────────────── clinician's phone (offline-first) ───────────────────┐
│                                                                         │
│   mic  →  Recorder (16 kHz PCM16)                                       │
│              ↓                                                          │
│   Moonshine-base (ASR, edge-optimized)  →  transcript                   │
│              ↓                                                          │
│   Speaker-tagged turn buffer (clinician / patient)                      │
│              ↓                                                          │
│   END ENCOUNTER                                                         │
│              ↓                                                          │
│   Qwen3-Embedding-0.6B  →  τ-partitioned cosine top-k                   │
│              ↓                                                          │
│   Stage 1 (<1 s): RAG top-hit per τ  →  ⚡ quick preview                 │
│              ↓                                                          │
│   Stage 2: Gemma 4 single streaming call  →  parsed τ1 / τ3 / τ4 cards  │
│              ↓                                                          │
│   functiongemma-270m  →  structured FHIR extraction                     │
│              ↓                                                          │
│   FHIR-lite SQLite + append-only sync queue + SHA-chained evidence log  │
│              ↓                                                          │
│   Network detected  →  POST Bundle → reconcile 409s                     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### On-device stack

| Layer | Choice |
|---|---|
| Inference engine | **Cactus v1.14** (hackathon mandate) — NPU-aware, ARM NEON / i8mm, day-one Gemma 4 |
| Reasoning | `google/gemma-4-E4B-it` INT4 on Android (Galaxy S26 Ultra) · `google/gemma-4-E2B-it` INT4 on iPhone (memory-tuned for free-tier Apple Dev) |
| Router / extractor | `google/functiongemma-270m-it` INT4 |
| Embeddings | `Qwen/Qwen3-Embedding-0.6B` INT4 |
| ASR | `UsefulSensors/moonshine-base` INT4 |
| Retrieval | in-memory float32 cosine over pre-indexed chunks, precomputed index bundled in iOS build |
| FHIR store | SQLite + append-only JSONL queue, reconciliation-safe |
| Platforms | Android (Kotlin + JNI → libcactus.so) · iOS (Swift + cactus-ios.xcframework) |

### Corpus (on-device)

Curated chunks from: ACEP Acute Chest Pain Clinical Policy · Tintinalli's don't-miss list · WHO IMCI · AGS 2023 Beers Criteria · WHO Essential Medicines · Joint Commission NPSG.03.06.01 · **NCCN Colon Cancer Survivorship** · **ASCO CEA surveillance** · tamoxifen CYP2D6 pharmacology · anthracycline / trastuzumab cardiotoxicity surveillance.

---

## Regulatory posture

- **Clinical decision support aid, not a diagnostic device.** Clinician-in-loop, non-autonomous.
- Falls under **21st Century Cures Act § 3060 CDS carve-out** from FDA SaMD classification; every output cites a retrievable guideline chunk.
- Same legal posture as UpToDate or DynaMed.
- On-device inference + zero cloud egress = **HIPAA-friendly by construction**.

---

## Competitive moat

| Capability | Saratoga | Abridge | Glass | Corti | MSF PDF | MedGemma |
|---|---|---|---|---|---|---|
| Real-time CDS during encounter | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| **Works fully offline** | ✅ | ❌ | ❌ | ❌ | ✅ static | N/A |
| Voice-driven | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ |
| Speaker-tagged encounter capture | ✅ | partial | ❌ | partial | ❌ | ❌ |
| Must-rule-out / don't-miss | ✅ | partial | ✅ | ✅ | ❌ | ❌ |
| Med reconciliation + interactions | ✅ | ❌ | ❌ | ❌ | ref only | ❌ |
| FHIR write, offline-buffered | ✅ | cloud | ❌ | cloud | ❌ | ❌ |
| Deployable LMIC / correctional / combat | ✅ | ❌ | ❌ | ❌ | ✅ no-AI | ❌ |
| **Peer-reviewed architectural foundation** | **✅ AMIA 2025** | ❌ | ❌ | partial | ❌ | ❌ |

---

## Build and deploy

### Mac dev loop

```bash
git clone https://github.com/cactus-compute/cactus
cd cactus && source ./setup

cactus download google/gemma-4-E4B-it
cactus download google/functiongemma-270m-it
cactus download Qwen/Qwen3-Embedding-0.6B
cactus download UsefulSensors/moonshine-base

cd .. && git clone git@github.com:shivampkumar/saratoga.git && cd saratoga
source ../cactus/venv/bin/activate
python seed_corpus.py
python clinical.py "63yo colorectal cancer survivor, CEA 4.2, rectal bleeding last week..."
python eval/run_eval.py    # three-scenario eval — numbers land in eval/results.json
```

### Android (Galaxy S26 Ultra) — full setup from zero

Prereqs (one-time):

```bash
# macOS host toolchain
brew install cmake gradle
brew install --cask temurin@17 android-commandlinetools android-platform-tools
export ANDROID_HOME="$HOME/Library/Android/sdk"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-34" "build-tools;34.0.0" "ndk;27.0.12077973" "platform-tools"
echo 'export ANDROID_HOME="$HOME/Library/Android/sdk"' >> ~/.zshrc
echo 'export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"' >> ~/.zshrc
echo 'export PATH="$ANDROID_HOME/platform-tools:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

Build libcactus.so + copy SDK into project:

```bash
cd cactus && source ./setup && cactus build --android
cp cactus/android/libcactus.so saratoga/android/app/src/main/jniLibs/arm64-v8a/
cp cactus/android/Cactus.kt saratoga/android/app/src/main/java/com/cactus/
```

On the phone: Settings → About phone → tap **Build number** × 7 → Settings → Developer Options → **USB debugging** ON. Connect USB, authorize computer.

```bash
adb devices    # should list your phone

cd saratoga/android
./gradlew assembleDebug
./deploy.sh    # installs APK + first-time 8.6 GB weights push to /sdcard/Android/data/com.saratoga/files/weights
```

Iterate later: `./gradlew assembleDebug && ./deploy.sh` (APK-only, weights persist through `adb install -r`).

Open **Saratoga** on phone → tap **LOAD MODELS** → **CLINICIAN** / **PATIENT** buttons for speaker-tagged encounter → **END ENCOUNTER** fires τ cards.

### iOS (iPhone 15 Pro, Swift via Cactus Apple SDK) — full setup from zero

Prereqs (one-time): **Xcode** from Mac App Store, **Apple ID** signed in (free Personal Team works; paid Apple Developer Program unlocks `com.apple.developer.kernel.increased-memory-limit` + `extended-virtual-addressing` for Gemma 4 E4B full size).

```bash
brew install xcodegen
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
sudo xcodebuild -license accept
```

Build the Apple xcframework + patch its module map (needed for Swift `import cactus`):

```bash
cd cactus && source ./setup && cactus build --apple
for dir in cactus/apple/cactus-ios.xcframework/ios-arm64/cactus.framework \
           cactus/apple/cactus-ios.xcframework/ios-arm64-simulator/cactus.framework; do
  mkdir -p "$dir/Modules"
  cp cactus/apple/module.modulemap "$dir/Modules/module.modulemap"
done
```

Generate Xcode project:

```bash
cd saratoga/ios
cp ../../cactus/apple/Cactus.swift SaratogaApp/CactusSwift.swift
xcodegen generate
open Saratoga.xcodeproj
```

In Xcode: project → **Signing & Capabilities** → select your Team, set Bundle Identifier unique (e.g. `com.saratoga.app.<initials>`). Connect iPhone via USB, trust computer. **Cmd+R** to build + install. First run on phone: Settings → General → VPN & Device Management → trust your Apple ID.

Transfer weights (iOS doesn't support `adb push`):

```bash
# Mac: pull the Apple-format weights bundle (already in the repo if you ran the Mac dev loop above)
python - <<'PY'
from huggingface_hub import hf_hub_download
import zipfile, shutil
from pathlib import Path
out = Path('../../weights-apple'); out.mkdir(exist_ok=True)
for repo, zipname, name in [
    ("Cactus-Compute/gemma-4-E2B-it", "weights/gemma-4-e2b-it-int4-apple.zip", "gemma-4-e2b-it"),
    ("Cactus-Compute/Qwen3-Embedding-0.6B", "weights/qwen3-embedding-0.6b-int4.zip", "qwen3-embedding-0.6b"),
    ("Cactus-Compute/moonshine-base", "weights/moonshine-base-int4-apple.zip", "moonshine-base"),
]:
    t = out / name
    if t.exists(): continue
    p = hf_hub_download(repo_id=repo, filename=zipname)
    t.mkdir()
    with zipfile.ZipFile(p) as z: z.extractall(t)
    inner = list(t.iterdir())
    if len(inner) == 1 and inner[0].is_dir():
        for f in inner[0].iterdir(): shutil.move(str(f), t)
        inner[0].rmdir()
PY
```

Bundle the precomputed corpus index into the app (one time):

```bash
cd saratoga
python seed_corpus.py    # generates corpus_index.npz
python - <<'PY'
import numpy as np, json
d = np.load('corpus_index.npz', allow_pickle=True)
chunks = json.loads(str(d['meta']))
out = {'chunks': chunks, 'dim': int(d['embeddings'].shape[1]),
       'vectors': d['embeddings'].astype('float32').tolist()}
json.dump(out, open('ios/SaratogaApp/Resources/corpus_index.json','w'))
PY
cd ios && xcodegen generate
# rebuild in Xcode (Cmd+R)
```

Transfer weights onto the phone via **Finder → iPhone → Files tab → Saratoga → drag** all three folders from `weights-apple/` into Saratoga (UIFileSharingEnabled is already set in Info.plist).

Launch Saratoga on phone → **LOAD MODELS** → speaker-tagged encounter → **END ENCOUNTER**.

> **Free Apple Developer tier limits**: Gemma 4 E4B (~8 GB) needs the `increased-memory-limit` + `extended-virtual-addressing` entitlements, which require a paid Apple Developer Program membership (not yet active after payment = wait 24–48 h). On free Personal Team, Saratoga uses **Gemma 4 E2B** (~6 GB with mlpackage ANE assets) automatically; code falls back: `e2b` if `e4b` isn't present.

---

## Evaluation (Mac run)

Three scenarios, mirroring the paper's evaluation rubric:

- `s1_chest_pain_polypharmacy` — acute primary care
- `s2_colorectal_survivor` — paper-native (CORAL-adjacent)
- `s3_peds_sepsis_imci` — WHO IMCI LMIC pediatrics

Measured: correct-fire-set rate, top-chunk retrieval accuracy, mean total latency. Numbers land in `eval/results.json`. Latest run:

- **Top-chunk retrieval accuracy: 100 %** (all expected chunks retrieved as top-hit)
- Correct-fire-set: 66.7 % (one conservative over-fire — clinically safe; tuned for sensitivity)
- Mean total latency: 20.7 s on Mac M4 Pro CPU (Cactus QNN/ANE target: <10 s)

---

## Roadmap

- **v0.2** — τ2 (history questions) + τ5 (multilingual patient handout + teach-back at 6th-grade reading level).
- **v0.3** — pyannote speaker diarization via `cactusDiarize` for automatic clinician/patient split.
- **v0.4** — native Gemma 4 audio path for **prosodic + affect features** (pick up patient hesitation, pain distress).
- **v0.5** — QNN / Hexagon NPU on Android when Cactus ships it.
- **v0.6** — Pilots: MSF rural Nigeria · one US county jail health system · one FEMA disaster unit.
- **v0.7** — FairPlay generative balancing for underrepresented populations (*npj Digital Medicine* 2025, team co-author).

---

## Team

**Shivam Pankaj Kumar** — co-author *Survivorship Navigator* (AMIA 2025) · co-author *FairPlay* (npj Digital Medicine 2025) · Northwestern Feinberg informatics.

---

## Citations

1. Pradeepkumar A, **Kumar SP**, Reamer E, Dreyer N, Patel R, Liebovitz DM, Sun J. *Survivorship Navigator: Personalized Survivorship Care Plan Generation using Large Language Models.* AMIA 2025 Informatics Summit. [PMC12919410](https://pmc.ncbi.nlm.nih.gov/articles/PMC12919410/).
2. Theodorou B, Danek B, Tummala A, **Kumar SP**, Malin B, et al. *FairPlay: Improving medical ML via generative balancing.* npj Digital Medicine 2025.
3. Sushil M et al. *CORAL: Expert-annotated oncology notes.* NEJM AI 2024.
4. ACEP Clinical Policy: Acute Chest Pain.
5. NCCN Colon Cancer Survivorship Guideline.
6. AHRQ Health Literacy Universal Precautions Toolkit, Tool 5 (teach-back).
7. Joint Commission NPSG.03.06.01 (medication reconciliation).
8. 21st Century Cures Act § 3060; FDA CDS Software guidance (Sept 2022).
9. WHO *World Health Report 2006* + *Bulletin WHO* 87(3):225 — 2.4 M global workforce shortfall.
10. Ndubuaku H et al. *Cactus: AI Inference Engine for Phones & Wearables.* github.com/cactus-compute/cactus.

---

Built by [@shivampkumar](https://github.com/shivampkumar) for the Cactus × Google DeepMind × Y Combinator hackathon, April 19 2026.
