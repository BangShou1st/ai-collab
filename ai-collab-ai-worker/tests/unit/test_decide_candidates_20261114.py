"""Pending-review decide candidate corpus for the 2026-11-14 hardening wave."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import sys
from pathlib import Path
from types import ModuleType

PROJECT_ROOT = Path(__file__).resolve().parents[2]
CANDIDATE_ROOT = (
    PROJECT_ROOT / "tests" / "fixtures" / "decide_set" / "candidates" / "2026-11-14-local-hardening"
)
VALIDATOR_PATH = CANDIDATE_ROOT / "tools" / "validate_candidates_20261114.py"
BUILDER_PATH = CANDIDATE_ROOT / "tools" / "build_candidates_20261114.py"
DECIDE_SET_DIR = PROJECT_ROOT / "tests" / "fixtures" / "decide_set"
MANIFEST = json.loads((CANDIDATE_ROOT / "manifest.json").read_text(encoding="utf-8"))


def _load_validator() -> ModuleType:
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("decide_candidate_validator", VALIDATOR_PATH)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _records(name: str) -> list[dict[str, object]]:
    return [
        json.loads(line)
        for line in (CANDIDATE_ROOT / name).read_text(encoding="utf-8").splitlines()
        if line
    ]


def test_candidate_corpus_is_valid_and_pending_review() -> None:
    summary = _load_validator().validate_dataset()

    assert summary["status"] == "PENDING_REVIEW"
    assert summary["counts"] == {"total": 80, "golden": 64, "adversarial": 16}
    assert summary["decisionCounts"] == {
        "ASK_USER": 6,
        "CALL_TOOL": 42,
        "FINAL": 8,
        "NO_OP": 8,
    }
    assert len(summary["locales"]) == 9
    assert len(summary["scenarios"]) == 15


def test_every_candidate_is_unreviewed_and_not_frozen() -> None:
    records = _records("golden.v1.jsonl") + _records("adversarial.v1.jsonl")

    assert len(records) == 80
    assert len({record["id"] for record in records}) == 80
    assert all(record["reviewStatus"] == "PENDING_REVIEW" for record in records)
    assert all(record["review"]["reviewer"] is None for record in records)
    assert all(record["review"]["reviewedAt"] is None for record in records)
    assert all("expected" not in record and "modelOutput" not in record for record in records)


def test_manifest_cannot_be_mistaken_for_quality_gate() -> None:
    assert MANIFEST["status"] == "PENDING_REVIEW"
    assert MANIFEST["reviewPolicy"] == {
        "defaultStatus": "PENDING_REVIEW",
        "reviewerRequired": True,
        "freezeRequiresSeparateApproval": True,
        "qualityGateEligible": False,
    }
    assert MANIFEST["counts"] == {"total": 80, "golden": 64, "adversarial": 16}


def test_historical_fixtures_are_byte_identical() -> None:
    expected = {
        "decide_baseline.v1.jsonl": (
            "144439dcb0998cceef549c13bb87720ebe0d5fa16072649f2738444a00a91413"
        ),
        "decide_baseline.v2.jsonl": (
            "6b74c9325964d9a0c5a5f18e995da73483683fbb89b8ff1f683972909998fd2b"
        ),
        "decide_baseline.v3.jsonl": (
            "68a66d8a42a2171e3ab8a88e0b93fd77cf621921167b3ef569419fee20b0dd5a"
        ),
        "decide_real_gemini31.v1.jsonl": (
            "ec35ac978446b898b87c6e1566efe9799f4132c76126845e8292e6ed66fec0ba"
        ),
    }
    actual = {
        name: hashlib.sha256((DECIDE_SET_DIR / name).read_bytes()).hexdigest() for name in expected
    }

    assert actual == expected
    assert MANIFEST["historicalFileHashes"] == expected


def test_manifest_fingerprints_match_candidate_bytes() -> None:
    for name in ("golden.v1.jsonl", "adversarial.v1.jsonl"):
        actual = hashlib.sha256((CANDIDATE_ROOT / name).read_bytes()).hexdigest()
        assert MANIFEST["canonicalSha256"][name] == actual


def test_candidate_files_are_utf8_without_bom_and_use_lf() -> None:
    for name in ("golden.v1.jsonl", "adversarial.v1.jsonl", "manifest.json"):
        raw = (CANDIDATE_ROOT / name).read_bytes()
        assert not raw.startswith(b"\xef\xbb\xbf")
        assert b"\r\n" not in raw
        raw.decode("utf-8")


def test_adversarial_probes_cover_each_rejection_family() -> None:
    records = _records("adversarial.v1.jsonl")
    reasons = {record["suggestedLabel"]["rejectReason"] for record in records}

    assert len(records) == 16
    assert len(reasons) == 7
    assert all(record["suggestedLabel"]["kind"] == "REJECTION" for record in records)
    assert all(record["probe"]["candidateOutput"] for record in records)


def test_golden_sources_are_traceable() -> None:
    records = _records("golden.v1.jsonl")
    source_kinds = {record["source"]["kind"] for record in records}

    assert source_kinds == {
        "FROZEN_V1_VARIANT",
        "LIVE_ROUND5_REFERENCE",
        "SYNTHETIC_COVERAGE_GAP",
        "DESIGN_REFERENCE",
    }
    assert all(record["source"]["references"] for record in records)
    assert all(record["source"]["rationale"] for record in records)


def test_builder_is_reproducible() -> None:
    result = subprocess.run(
        [sys.executable, str(BUILDER_PATH), "--check"],
        cwd=PROJECT_ROOT,
        capture_output=True,
        check=False,
        timeout=60,
    )

    assert result.returncode == 0, result.stderr.decode("utf-8", errors="replace")
    assert result.stdout == b""
    assert result.stderr == b""
