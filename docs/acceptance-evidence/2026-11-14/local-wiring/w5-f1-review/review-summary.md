# W5 F1 Candidate Review Evidence

- Lane: `wiring/f1-candidate-review`
- Worktree: `E:\project\ai-collab-wt\wiring-f1-review`
- Baseline handed to lane: `1f95447e1880132e34fca2ef57345ccbf7699f5e`
- Candidate set: 80 = 64 golden + 16 adversarial
- Review ledger: `ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v4.review.json`
- Promoted set: `ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v4.jsonl`
- Status: `IMPLEMENTED`; validation `TESTED(UNIT)`

## Reviewed Changes

- 80/80 cases have reviewer, reviewedAt, per-case notes, checks and correction list.
- 17/80 cases carry explicit corrections.
- Golden label distribution remains `CALL_TOOL=42`, `FINAL=8`, `ASK_USER=6`, `NO_OP=8`.
- Adversarial rejection distribution remains 7 families across 16 probes.
- Candidate input files and historical v1/v2/v3/real-gemini fixtures remain byte-identical.
- Adversarial `expected=ASK_USER` is a schema-required neutral placeholder; the authoritative
  assertion is probe reason/parser equality and replay `rejectedRate`.

## Hashes

| Item | Value |
| --- | --- |
| v4 raw SHA-256 | `866f88f388237201c130973363d1c3d2c7d77643555f721f735654d0df107f93` |
| v4 canonical SHA-256 | `abf169b85526e2b1471e8d6ce939b447273b5c8994d76f7e0a12b9f3a9ff484a` |
| v4 canonical bytes | `121364` |
| review SHA-256 | `c3d8681f50cf6d35317385cd67acc52137345867d8f13d6cd1a2e893db8164a5` |
| manifest SHA-256 | `63ce0455feb99c278b4e64098a5ed04477bfcc9680224c32dc3c660ed6c25d8b` |

## Commands And Raw Evidence

| Command | Raw output | Result |
| --- | --- | --- |
| `uv run python tests/fixtures/decide_set/tools/promote_v4_20261114.py` | `promote-v4.txt` | deterministic write |
| same script with `--check` | `promote-v4-check.txt` | byte-for-byte check |
| `uv run ai-eval fingerprint ...v4.jsonl --json` | `fingerprint-v4.txt` | canonical fingerprint |
| `uv run ai-eval decide ...v4.jsonl --json` | `eval-v4.json.txt` | replay evidence |
| candidate validator | `validator-after-v4.json.txt` | candidate corpus remains pending review |
| `uv run pytest -q` on decide candidate/parser/eval/contract tests | `decide-unit-tests.txt` | 70 tests |
| `uv run ruff format/check` on promotion script | `ruff-format.txt`, `ruff-check.txt` | formatting + lint |

## Replay Metrics

- `aggregate.bySubset.golden.decisionAgreement=1.0`
- `aggregate.bySubset.golden.jsonValidityRate=1.0`
- `aggregate.bySubset.golden.schemaViolations=0`
- `aggregate.bySubset.adversarial.rejectedRate=1.0`
- `aggregate.bySubset.adversarial.invalidToolCalls=4` (the four deliberate unknown-tool probes)
- `evidence=TESTED (stub)`, `qualityClaim=NOT_VERIFIED`

These are replay/contract results only and do not claim real-model quality.
