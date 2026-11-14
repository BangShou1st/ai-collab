"""只读转换脚本：从 v1 冻结集与 S3 归档真实输出生成 memory v2 / real replay 集。

用法（在 ``ai-collab-ai-worker`` 下）::

    uv run python tests/fixtures/memory_set/tools/build_fixtures.py

输入（只读）：

- ``tests/fixtures/memory_set/memory_baseline.v1.jsonl``；
- ``docs/acceptance-evidence/2026-11-12/w3-s3-real-model-evals/raw/memory-cases.jsonl``
  （S3 归档；本脚本只读取 ``id`` 与 ``content``，不写回历史证据）。

输出：

- ``memory_baseline.v2.jsonl``：14 条 case 全保留；每条 judgement 增加显式
  ``factRefs``；m-005 / m-009 / m-010 增加支撑 fact（不删 case、不删 judgement）；
- ``memory_real_gemini31.v1.jsonl``：snapshot / judgement 来自 v2，``modelOutput``
  来自 S3 归档的真实 gemini-3.1-flash-lite 输出；不含任何密钥。

两个输出都使用固定键序与无尾随空白，保证重复运行逐字节一致。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Final

HERE = Path(__file__).resolve()
WORKER_ROOT = HERE.parents[4]
REPO_ROOT = WORKER_ROOT.parent
V1_PATH = WORKER_ROOT / "tests" / "fixtures" / "memory_set" / "memory_baseline.v1.jsonl"
V2_PATH = WORKER_ROOT / "tests" / "fixtures" / "memory_set" / "memory_baseline.v2.jsonl"
REAL_PATH = WORKER_ROOT / "tests" / "fixtures" / "memory_set" / "memory_real_gemini31.v1.jsonl"
S3_RAW_PATH = (
    REPO_ROOT
    / "docs"
    / "acceptance-evidence"
    / "2026-11-12"
    / "w3-s3-real-model-evals"
    / "raw"
    / "memory-cases.jsonl"
)

#: m-005 / m-009 / m-010 的判定在原 facts 中没有支撑；v2 只补支撑事实，不删任何内容。
EXTRA_FACTS: Final[dict[str, dict[str, str]]] = {
    "m-005": {
        "ref": "fact-4",
        "kind": "CONSTRAINT",
        "text": "任何写操作都必须走人工审批。",
    },
    "m-009": {
        "ref": "fact-4",
        "kind": "DECISION",
        "text": "接口契约先冻结，再开始实现。",
    },
    "m-010": {
        "ref": "fact-4",
        "kind": "CONSTRAINT",
        "text": "Python 不写业务表，所有写操作留在 Java 控制面。",
    },
}

#: 每条 judgement 的事实支撑；键是 case id，值是 {judgement title: [fact ref]}。
FACT_REFS: Final[dict[str, dict[str, list[str]]]] = {
    "m-001": {
        "检索链路统一走 Java 网关": ["fact-1"],
        "生产环境不持 Provider 密钥": ["fact-2"],
        "灰度前必须准备回滚开关": ["fact-3"],
    },
    "m-002": {
        "周报固定周五下午发出": ["fact-1"],
        "迭代长度调整为两周": ["fact-2"],
    },
    "m-003": {
        "检索链路统一走 Java 网关": ["fact-1"],
        "生产环境不持 Provider 密钥": ["fact-2"],
    },
    "m-004": {"外部排期要预留缓冲": ["fact-1"]},
    "m-005": {"写操作必须人工审批": ["fact-4"]},
    "m-006": {"检索链路统一走 Java 网关": ["fact-1"]},
    "m-007": {"周报固定周五下午发出": ["fact-1"]},
    "m-008": {"周报固定周五下午发出": ["fact-1"]},
    "m-009": {"API 契约先行": ["fact-4"]},
    "m-010": {"Python 不写业务表": ["fact-4"]},
    "m-011": {
        "外部排期要预留缓冲": ["fact-1"],
        "写操作必须人工审批": ["fact-2"],
    },
    "m-012": {"迭代长度调整为两周": ["fact-2"]},
    "m-013": {"写操作必须人工审批": ["fact-2"]},
    "m-014": {
        "检索链路统一走 Java 网关": ["fact-1"],
        "灰度前必须准备回滚开关": ["fact-3"],
    },
}


def main() -> None:
    v1_cases = _read_jsonl(V1_PATH)
    v2_cases: list[dict[str, Any]] = []
    for case in v1_cases:
        case_id = case["id"]
        if case_id in EXTRA_FACTS:
            case["snapshot"]["facts"].append(EXTRA_FACTS[case_id])
        refs = FACT_REFS[case_id]
        new_judgement: list[dict[str, Any]] = []
        for entry in case["humanJudgement"]:
            title = entry["title"]
            if title not in refs:
                raise SystemExit(f"{case_id} 的 judgement {title!r} 没有 factRefs 映射")
            new_judgement.append({**entry, "factRefs": refs[title]})
        if len(new_judgement) != len(refs):
            raise SystemExit(f"{case_id} 的 factRefs 映射数量与 judgement 数量不一致")
        case["humanJudgement"] = new_judgement
        v2_cases.append(case)

    _write_jsonl(V2_PATH, v2_cases)

    raw_cases = {
        row["id"]: row["content"] for row in _read_jsonl(S3_RAW_PATH) if row.get("status") == "ok"
    }
    real_cases: list[dict[str, Any]] = []
    for case in v2_cases:
        case_id = case["id"]
        if case_id not in raw_cases:
            raise SystemExit(f"S3 归档缺少 {case_id} 的真实模型输出")
        real_cases.append({**case, "modelOutput": raw_cases[case_id]})
    _write_jsonl(REAL_PATH, real_cases)

    print(f"wrote {V2_PATH.relative_to(WORKER_ROOT)}")
    print(f"wrote {REAL_PATH.relative_to(WORKER_ROOT)}")


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    text = "\n".join(
        json.dumps(row, ensure_ascii=False, separators=(",", ":"), sort_keys=True) for row in rows
    )
    path.write_text(text + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
