"""只读转换脚本：从 S3 归档真实 planning 输出生成 replay 数据集。

用法（在 ``ai-collab-ai-worker`` 下）::

    uv run python tests/fixtures/planning_set/tools/build_real_gemini31.py

输入：

- ``tests/fixtures/planning_set/planning_baseline.v1.jsonl``（请求字段、expected、subset）；
- ``docs/acceptance-evidence/2026-11-12/w3-s3-real-model-evals/raw/planning-cases.jsonl``
  （真实 gemini-3.1-flash-lite 输出；只读）。

输出：``tests/fixtures/planning_set/planning_real_gemini31.v1.jsonl``，21 条 case，
``modelOutput`` 替换为 S3 归档的 ``content`` 原文；不写回历史证据、不含任何密钥。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve()
WORKER_ROOT = HERE.parents[4]
REPO_ROOT = WORKER_ROOT.parent
BASELINE = WORKER_ROOT / "tests" / "fixtures" / "planning_set" / "planning_baseline.v1.jsonl"
OUTPUT = WORKER_ROOT / "tests" / "fixtures" / "planning_set" / "planning_real_gemini31.v1.jsonl"
RAW = (
    REPO_ROOT
    / "docs"
    / "acceptance-evidence"
    / "2026-11-12"
    / "w3-s3-real-model-evals"
    / "raw"
    / "planning-cases.jsonl"
)


def main() -> None:
    baseline = _read_jsonl(BASELINE)
    raw = {row["id"]: row for row in _read_jsonl(RAW)}
    output: list[dict[str, Any]] = []
    for case in baseline:
        case_id = case["id"]
        row = raw.get(case_id)
        if row is None or row.get("status") != "ok" or not isinstance(row.get("content"), str):
            raise SystemExit(f"S3 归档缺少 {case_id} 的成功真实输出")
        output.append({**case, "modelOutput": row["content"]})
    _write_jsonl(OUTPUT, output)
    print(f"wrote {OUTPUT.relative_to(WORKER_ROOT)} ({len(output)} cases)")


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [
        json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()
    ]


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    text = "\n".join(
        json.dumps(row, ensure_ascii=False, separators=(",", ":"), sort_keys=True) for row in rows
    )
    path.write_text(text + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
