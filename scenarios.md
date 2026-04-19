# Saratoga — test scenarios (placeholder)

Working notes. Real scenarios + references tracked privately.

Three short voice transcripts are used to smoke-test the pipeline across the
three task partitions (τ1 / τ3 / τ4). Each covers a different clinical setting.

## Scenario buckets
- acute primary care
- survivorship follow-up
- pediatric emergent

## Expected behavior
For each scenario, the τ-gate fires one or more panes, the reasoning model
produces a card per fired task, and a FHIR bundle lands in the offline queue.
Chunk citations appear inline with every task output.

## Where the live transcripts live
See repo-private `scenarios_real.md` (gitignored). Out of scope for this README.
