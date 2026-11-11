"""JSON Schema 快照：生成、比对与写回。

契约快照入库在 ``snapshots/v1/``。CI/测试只做比对（重生成 == 入库），要更新契约时
显式运行：

```bash
uv run python -m ai_collab_worker.contract.schema --write
```
"""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Sequence
from pathlib import Path
from typing import Final

from pydantic import BaseModel

from ai_collab_worker.contract.agent_decide import AgentDecideRequest, AgentDecideResponse
from ai_collab_worker.contract.common import CommonRequest, ErrorEnvelope
from ai_collab_worker.contract.memory_job import MemoryJobRequest, MemoryJobResponse
from ai_collab_worker.contract.planning import PlanningDraftRequest, PlanningDraftResponse
from ai_collab_worker.contract.prompts import PromptDocument, PromptManifest
from ai_collab_worker.contract.query_plan import QueryPlanRequest, QueryPlanResponse
from ai_collab_worker.contract.rerank import RerankRequest, RerankResponse

SNAPSHOT_DIR: Final[Path] = Path(__file__).resolve().parent / "snapshots" / "v1"

#: 快照文件名（不含 ``.json``）→ 模型。新增契约时同时登记到这里与快照目录。
SCHEMA_MODELS: Final[dict[str, type[BaseModel]]] = {
    "agent-decide-request": AgentDecideRequest,
    "agent-decide-response": AgentDecideResponse,
    "common-request": CommonRequest,
    "error-envelope": ErrorEnvelope,
    "memory-job-request": MemoryJobRequest,
    "memory-job-response": MemoryJobResponse,
    "planning-request": PlanningDraftRequest,
    "planning-response": PlanningDraftResponse,
    "prompt-document": PromptDocument,
    "prompt-manifest": PromptManifest,
    "query-plan-request": QueryPlanRequest,
    "query-plan-response": QueryPlanResponse,
    "rerank-request": RerankRequest,
    "rerank-response": RerankResponse,
}


def render_schema(model: type[BaseModel]) -> str:
    """渲染单个模型的 JSON Schema 文本（稳定键序、UTF-8、以换行结尾）。"""

    schema = model.model_json_schema(by_alias=True, mode="serialization")
    return json.dumps(schema, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def render_all() -> dict[str, str]:
    """渲染全部快照：``文件名 -> 文本``。"""

    return {name: render_schema(model) for name, model in SCHEMA_MODELS.items()}


def snapshot_path(name: str) -> Path:
    return SNAPSHOT_DIR / f"{name}.schema.json"


def check() -> list[str]:
    """返回与入库快照的差异描述；空列表表示完全一致。"""

    problems: list[str] = []
    for name, text in render_all().items():
        path = snapshot_path(name)
        if not path.is_file():
            problems.append(f"缺少快照：{path.name}")
            continue
        actual = path.read_text(encoding="utf-8")
        if actual != text:
            problems.append(f"快照与模型不一致：{path.name}")
    existing = {path.stem.removesuffix(".schema") for path in SNAPSHOT_DIR.glob("*.schema.json")}
    for name in sorted(existing - set(SCHEMA_MODELS)):
        problems.append(f"存在未登记的旧快照：{name}.schema.json")
    return problems


def write() -> list[Path]:
    """把当前模型渲染结果写回快照目录（先清掉未登记的旧快照）。"""

    SNAPSHOT_DIR.mkdir(parents=True, exist_ok=True)
    for path in SNAPSHOT_DIR.glob("*.schema.json"):
        if path.stem.removesuffix(".schema") not in SCHEMA_MODELS:
            path.unlink()
    written: list[Path] = []
    for name, text in render_all().items():
        path = snapshot_path(name)
        path.write_text(text, encoding="utf-8", newline="\n")
        written.append(path)
    return written


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m ai_collab_worker.contract.schema",
        description="校验/写回跨语言契约的 JSON Schema 快照",
    )
    parser.add_argument("--write", action="store_true", help="把当前模型写回快照（默认只校验）")
    args = parser.parse_args(argv)

    if args.write:
        for path in write():
            print(f"written: {path}")
        return 0

    problems = check()
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        print(
            "契约快照已漂移；确认是预期变更后运行："
            " uv run python -m ai_collab_worker.contract.schema --write",
            file=sys.stderr,
        )
        return 1
    print(f"contract snapshots OK ({len(SCHEMA_MODELS)} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
