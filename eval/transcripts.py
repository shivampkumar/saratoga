"""3 scripted demo scenarios. See scenarios.md."""

SCENARIOS = [
    {
        "id": "s1_chest_pain_polypharmacy",
        "description": "Rural primary care — chest pain + polypharmacy",
        "transcript": (
            "Forty-two year old woman with chest pain for three days radiating to "
            "the jaw, diabetic on metformin, just started ciprofloxacin from a "
            "neighbor, blood pressure one hundred eighty over one-ten."
        ),
        "expected_fires": {"tau1", "tau3", "tau4"},
        "expected_top_chunks": {
            "tau1": ["ddx_acs", "ddx_aortic_dissection"],
            "tau3": ["rf_hypertensive_emergency"],
            "tau4": ["med_cipro_qt", "med_metformin_contrast", "med_asa_missing_acs"],
        },
    },
    {
        "id": "s2_colorectal_survivor",
        "description": "Cancer survivor 3-yr surveillance (paper-native)",
        "transcript": (
            "Sixty-three year old man with stage two colon cancer resected three "
            "years ago, on metformin for diabetes. Here for routine survivorship "
            "follow-up. He stopped his aspirin two months ago because of a nosebleed. "
            "Hasn't had a colonoscopy since surgery. CEA last month was four point two. "
            "He reports some rectal bleeding last week."
        ),
        "expected_fires": {"tau1", "tau3", "tau4"},
        "expected_top_chunks": {
            "tau1": ["ddx_polypharmacy_geriatric", "ddx_gerd_musculoskeletal"],
            "tau3": ["rf_gi_bleed"],
            "tau4": ["med_asa_missing_acs", "med_anticoag_bleeding", "med_beers_elderly"],
        },
    },
    {
        "id": "s3_peds_sepsis_imci",
        "description": "LMIC pediatric sepsis (WHO IMCI)",
        "transcript": (
            "Eleven month old boy with fever for two days. He stopped breastfeeding "
            "since morning. Capillary refill four seconds. Respiratory rate fifty-eight. "
            "Oxygen saturation ninety percent on room air. No stiff neck. Mom says he "
            "had a seizure in the car on the way here."
        ),
        "expected_fires": {"tau1", "tau3"},
        "expected_top_chunks": {
            "tau1": ["ddx_peds_fever_high_risk", "ddx_sepsis"],
            "tau3": ["rf_peds_danger_signs", "rf_sepsis_bundle"],
            "tau4": [],
        },
    },
]
