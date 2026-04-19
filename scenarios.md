# Saratoga — demo scenarios

Three scripted transcripts for the live demo, ordered by narrative strength. Each maps cleanly to the **Survivorship Navigator** (Pradeepkumar, **Kumar SP**, et al. AMIA 2025, [PMC12919410](https://pmc.ncbi.nlm.nih.gov/articles/PMC12919410/)) τ-task architecture Saratoga is derived from.

Each scenario includes:
- transcript to speak
- expected τ-task fires + top chunk citations
- ground-truth clinically-validated response (for eval / judge Q&A)
- paper-alignment note

---

## Scenario 1 — chest pain + polypharmacy in rural primary care (LEAD DEMO)

### Transcript (read aloud, 18 s)
> "Forty-two year old woman with chest pain for three days radiating to the jaw, diabetic on metformin, just started ciprofloxacin from a neighbor, blood pressure one hundred eighty over one-ten."

### Expected fires

| τ | Top chunk | Card |
|---|---|---|
| τ1 DDX | `ddx_acs` s≥0.75 | (1) ACS (2) Hypertensive urgency / emergency (3) Aortic dissection. MUST-RULE-OUT: ACS, aortic dissection, PE |
| τ3 RED FLAG | `rf_hypertensive_emergency` s≥0.70 | ALERT: Hypertensive emergency (chest pain + 180/110). ACTION: nicardipine IV, reduce MAP 20–25 % over first hour, monitor end-organ |
| τ4 MED REC | `med_cipro_qt` OR `med_metformin_contrast` s≥0.60 | Cipro + QT-prolongation risk with DM; check ECG + K/Mg. Hold metformin if CT with contrast planned. ASA not yet given for ACS suspicion — flag as missing guideline-directed therapy |

### Ground-truth responses (for judge Q&A / eval reference)

- **Correctness**: ACS must be ruled out first (ACEP Clinical Policy). BP 180/110 + chest pain = hypertensive emergency by JNC 8 definition (end-organ damage = chest pain). Metformin is hold-if-contrast (FDA guidance). Cipro + any QT-prolonging agent needs ECG (Lexicomp Level X).
- **Actionability**: aspirin 325 mg chewed, 12-lead ECG ≤10 min, serial troponins, CBC/BMP/coags, consider CTPA if risk factors, nicardipine drip, admit telemetry.
- **Faithfulness**: every clinical claim is citable to a specific retrieved chunk.

### Paper alignment
This maps cleanly to the paper's τ1 surveillance (differential is the decision-support equivalent of surveillance schedule generation) and τ3 secondary prevention (red-flag alerts are the active decision-support equivalent of prevention protocols). The **two-step structure-to-JSON-then-task-prompt** pattern in Saratoga is identical to the paper's Section 3.2 pipeline.

---

## Scenario 2 — colorectal cancer survivor 3-yr surveillance (PAPER-NATIVE DEMO)

**Use this one if judges press on paper alignment.** This is literally the paper's domain (cancer survivorship).

### Transcript (24 s)
> "Sixty-three year old man, stage two colon cancer resected three years ago, on metformin for diabetes. Here for routine survivorship follow-up. Says he stopped his aspirin two months ago because of a nosebleed. Hasn't had a colonoscopy since surgery. CEA last month was 4.2. Reports some rectal bleeding last week."

### Expected fires

| τ | Top chunk | Card |
|---|---|---|
| τ1 DDX | `ddx_polypharmacy_geriatric` + cancer surveillance | (1) Local or distant recurrence (CEA rising, rectal bleeding) (2) New colorectal primary (3) Hemorrhoids / benign cause |
| τ3 RED FLAG | any GI bleed chunk | ALERT: Rising CEA + rectal bleeding in 3-yr post-resection. ACTION: colonoscopy within 2 weeks, CT abdomen/pelvis w/ contrast, CBC today |
| τ4 MED REC | missing ASA + missing surveillance colo | Stopped ASA without discussion; colonoscopy overdue (NCCN guideline: every 3 y post-resection if pathology clean, annually if any high-risk feature) |

### Paper alignment (KEY SLIDE)
> "My 2025 AMIA paper built this exact pipeline for cancer survivorship. Saratoga is that architecture re-pointed at acute-care guidelines. This scenario is literally what the paper does — on a phone, offline. The τ-tasks are the same τ-tasks from the paper's Figure 2."

### Ground truth (paper's clinical eval dimensions)

Using the paper's 5-point expert-rated scale across 7 dimensions:

| Dimension | Expected score (target) | Gold answer |
|---|---|---|
| Correctness | ≥4/5 | Identifies recurrence risk, missing colonoscopy, ASA discontinuation |
| Faithfulness | ≥4/5 | Cites NCCN CRC surveillance + ACS chemoprevention guidelines |
| Actionability | ≥4/5 | Specific next-steps: colonoscopy 2w, CT A/P, CBC, resume ASA discussion |
| Reasoning accuracy | ≥4/5 | Links rising CEA + rectal bleeding causally to surveillance urgency |
| Personalization | ≥3/5 | Accounts for DM, age, prior stage II clean-margin resection |
| Clarity | ≥4/5 | 6th-grade reading level when in τ5 handout mode |
| Comprehensiveness | ≥3/5 | Doesn't miss hemorrhoid ddx but prioritizes malignancy |

Target expert-rated correctness: **≥71 %**, matching the paper's Gemma-2-9B baseline. Any improvement ascribable to Gemma 4 E4B is a bonus claim.

---

## Scenario 3 — pediatric sepsis, LMIC clinic (EMOTIONAL / NARRATIVE HOOK)

Use this one to close. It's the shortest, most visceral, and has the hardest time pressure.

### Transcript (14 s)
> "Eleven month old boy, fever for two days, stopped breastfeeding since morning, capillary refill four seconds, respiratory rate fifty-eight, saturating ninety percent on room air, no stiff neck. Mom says he had a seizure in the car on the way here."

### Expected fires

| τ | Top chunk | Card |
|---|---|---|
| τ3 RED FLAG | `rf_peds_danger_signs` s≥0.80 | ALERT: Pediatric danger signs: convulsion, tachypnea, SpO2<92%, cap refill>3s. ACTION: IV access, empiric ceftriaxone, LP after CT if focal, admit |
| τ1 DDX | `ddx_peds_fever_high_risk` | (1) Bacterial meningitis (2) Sepsis (3) Severe pneumonia |
| τ4 MED REC | renal-adjusted peds dosing | Ceftriaxone 50 mg/kg IV once; hold NSAIDs if suspected sepsis; no aspirin in pediatric viral illness (Reye) |

### Paper alignment
WHO IMCI (Integrated Management of Childhood Illness) is explicitly one of the paper's reference corpora type — guideline-driven, chunk-retrievable, task-segmented. Saratoga's τ3 chunks are IMCI-derived verbatim for this scenario.

### The moment
If judges sit up at the "seizure in the car" line, you have them. This is the scenario where **"cloud competitors show a blank screen"** is no longer a metaphor.

---

## Eval harness (run tonight, numbers go on Slide 5)

```bash
source ../cactus/venv/bin/activate

for scenario in scenario1 scenario2 scenario3; do
    python clinical.py "$(cat eval/transcripts/${scenario}.txt)"
done
```

Capture per scenario:
- `pipeline_ms`
- `fired_taus`
- `card_bleu` (vs `eval/references/${scenario}.json`)
- `card_rouge-1`
- `em_f1_extracted_meds`, `em_f1_extracted_vitals`

Roll up one number per dimension for the deck. Match or exceed paper's 71.1 %.

---

## Pitch framing (use verbatim)

> "I'm **Shivam Pankaj Kumar**, co-author of the *Survivorship Navigator* paper published at AMIA 2025. That paper built a multimodal LLM clinical care interface — exactly the architecture Saratoga runs, re-pointed at acute-care guidelines and deployed offline on a phone. Everything you just watched is a real-time on-device instantiation of a peer-reviewed methodology. Our 71.1 % correctness baseline is from the paper. Our moat is that cloud competitors can't reproduce it without a full re-architecture."

PMC link: https://pmc.ncbi.nlm.nih.gov/articles/PMC12919410/
