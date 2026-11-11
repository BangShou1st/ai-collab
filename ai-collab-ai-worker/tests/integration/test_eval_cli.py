"""``ai-eval`` CLI 的集成测试。

通过真实子进程（``python -m ai_collab_worker.eval.cli``）验证：

- ``fingerprint --json`` 两次输出逐字节一致、且与 ``canonicalize`` 的字节/摘要对得上；
- ``canonicalize -o`` 写出的文件与 stdout 完全一致、无尾随换行；
- ``verify`` 正确摘要退出码 0、错误摘要退出码 3；
- 非法输入与文件缺失返回 2，且 stderr 不出现 traceback。
"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
FROZEN_SET = PROJECT_ROOT / "tests" / "fixtures" / "frozen_set" / "rag_baseline.v1.jsonl"
CLI_MODULE = "ai_collab_worker.eval.cli"


def _run(*args: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        [sys.executable, "-m", CLI_MODULE, *args],
        cwd=PROJECT_ROOT,
        capture_output=True,
        check=False,
        timeout=120,
    )


def _fingerprint_json() -> dict[str, object]:
    result = _run("fingerprint", str(FROZEN_SET), "--json")
    assert result.returncode == 0, result.stderr
    payload: dict[str, object] = json.loads(result.stdout)
    return payload


def test_fingerprint_json_is_stable_and_matches_canonical_bytes() -> None:
    first = _run("fingerprint", str(FROZEN_SET), "--json")
    second = _run("fingerprint", str(FROZEN_SET), "--json")

    assert first.returncode == 0, first.stderr
    assert second.returncode == 0, second.stderr
    assert first.stdout == second.stdout
    assert not first.stdout.endswith(b"\n")

    payload = json.loads(first.stdout)
    assert sorted(payload) == ["bytes", "cases", "sha256"]
    assert payload["cases"] >= 4

    canonical = _run("canonicalize", str(FROZEN_SET))
    assert canonical.returncode == 0, canonical.stderr
    assert not canonical.stdout.endswith(b"\n")
    assert payload["bytes"] == len(canonical.stdout)
    assert payload["sha256"] == hashlib.sha256(canonical.stdout).hexdigest()

    # 键排序、紧凑分隔符、UTF-8 无转义、无尾随换行：与仓库 README §7 的单行 JSON 对齐。
    expected = json.dumps(
        payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    assert first.stdout == expected

    # fixture 是中文语料：输出必须是原始 UTF-8，而不是 \uXXXX。
    assert "知识库".encode() in canonical.stdout
    assert b"\\u77e5" not in canonical.stdout


def test_canonicalize_writes_exact_file(tmp_path: Path) -> None:
    output = tmp_path / "canonical.json"
    result = _run("canonicalize", str(FROZEN_SET), "-o", str(output))

    assert result.returncode == 0, result.stderr
    assert result.stdout == b""

    expected = _run("canonicalize", str(FROZEN_SET)).stdout
    assert output.read_bytes() == expected
    assert not output.read_bytes().endswith(b"\n")


def test_fingerprint_text_mode_agrees_with_json_mode() -> None:
    result = _run("fingerprint", str(FROZEN_SET))
    assert result.returncode == 0, result.stderr
    assert re.fullmatch(rb"sha256=[0-9a-f]{64} cases=\d+\r?\n", result.stdout)

    payload = _fingerprint_json()
    assert result.stdout.startswith(f"sha256={payload['sha256']} ".encode())


def test_verify_accepts_matching_digest() -> None:
    sha256 = str(_fingerprint_json()["sha256"])
    result = _run("verify", str(FROZEN_SET), "--sha256", sha256)

    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == f"OK {sha256}".encode()


def test_verify_rejects_wrong_digest() -> None:
    sha256 = str(_fingerprint_json()["sha256"])
    wrong = "0" * 64
    result = _run("verify", str(FROZEN_SET), "--sha256", wrong)

    assert result.returncode == 3
    assert result.stdout == b""
    assert b"mismatch" in result.stderr
    assert sha256.encode() in result.stderr
    assert wrong.encode() in result.stderr


def test_invalid_dataset_returns_2_without_traceback(tmp_path: Path) -> None:
    bad = tmp_path / "bad.jsonl"
    bad.write_bytes(b'{"id":"a","id":"b"}\n')
    result = _run("fingerprint", str(bad))

    assert result.returncode == 2
    assert result.stdout == b""
    assert result.stderr.startswith(b"invalid dataset: ")
    assert b"Traceback" not in result.stderr


def test_missing_input_returns_2_without_traceback(tmp_path: Path) -> None:
    missing = tmp_path / "missing.jsonl"
    result = _run("canonicalize", str(missing))

    assert result.returncode == 2
    assert result.stderr
    assert b"Traceback" not in result.stderr


def test_verify_invalid_dataset_returns_2(tmp_path: Path) -> None:
    bad = tmp_path / "bad.json"
    bad.write_bytes(b'{"schemaVersion":2,"cases":[]}')
    result = _run("verify", str(bad), "--sha256", "0" * 64)

    assert result.returncode == 2
    assert result.stderr.startswith(b"invalid dataset: ")
    assert b"Traceback" not in result.stderr
