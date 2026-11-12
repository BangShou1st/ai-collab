"""只读转换脚本：从 S3 归档的 decide 结果重建 replay 数据集。

用法（在 ``ai-collab-ai-worker`` 下）::

    uv run python tests/fixtures/decide_set/tools/build_real_gemini31.py

输入：

- ``tests/fixtures/decide_set/decide_baseline.v1.jsonl``（请求字段、expected、subset）；
- ``docs/acceptance-evidence/2026-11-12/w3-s3-real-model-evals/raw/decide-cases.jsonl``
  （S3 归档；只读）。

输出：``tests/fixtures/decide_set/decide_real_gemini31.v1.jsonl``。

**重要限制（不得省略）**：S3 归档只保留了解析后的 ``actual.decision`` / ``actual.toolName``，
没有模型原始文本，也没有 ``toolCall.arguments``。因此本脚本把每条成功的 actual 重建为
``{"decision": ..., "toolCall": {"name": ..., "arguments": {}}}``：

- 这是"解析后重建"，不是原始模型输出；``arguments`` 缺失，统一重建为空对象；
- d-018 的 actual 是 ``MODEL_UNAVAILABLE``（provider 503），没有 decision 可重建，
  **不写入**该 fixture（31 条而非 32 条），并在 README 明确标 NOT_VERIFIED；
- 不含任何密钥，也不写回历史证据。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve()
WORKER_ROOT = HERE.parents[4]
REPO_ROOT = WORKER_ROOT.parent
BASELINE = WORKER_ROOT / "tests" / "fixtures" / "decide_set" / "decide_baseline.v1.jsonl"
OUTPUT = WORKER_ROOT / "tests" / "fixtures" / "decide_set" / "decide_real_gemini31.v1.jsonl"
RAW = (
    REPO_ROOT
    / "docs"
    / "acceptance-evidence"
    / "2026-11-12"
    / "w3-s3-real-model-evals"
    / "raw"
    / "decide-cases.jsonl"
)


def main() -> None:
    baseline = _read_jsonl(BASELINE)
    raw = {row["id"]: row for row in _read_jsonl(RAW)}
    output: list[dict[str, Any]] = []
    skipped: list[str] = []
    for case in baseline:
        case_id = case["id"]
        row = raw.get(case_id)
        if row is None:
            raise SystemExit(f"S3 归档缺少 {case_id}")
        actual = row.get("actual")
        if not isinstance(actual, dict) or not actual.get("valid"):
            skipped.append(case_id)
            continue
        decision = actual.get("decision")
        tool_name = actual.get("toolName")
        if not isinstance(decision, str) or not decision:
            raise SystemExit(f"{case_id} 的 actual.decision 不可重建")
        payload: dict[str, Any] = {"decision": decision}
        if decision == "CALL_TOOL":
            if not isinstance(tool_name, str) or not tool_name:
                raise SystemExit(f"{case_id} 的 CALL_TOOL 缺少 actual.toolName")
            payload["toolCall"] = {"name": tool_name, "arguments": {}}
        output.append(
            {
                **case,
                "modelOutput": json.dumps(
                    payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True
                ),
            }
        )
    _write_jsonl(OUTPUT, output)
    print(f"wrote {OUTPUT.relative_to(WORKER_ROOT)} ({len(output)} cases)")
    if skipped:
        print(f"skipped (no reconstructable decision): {', '.join(skipped)}")


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
