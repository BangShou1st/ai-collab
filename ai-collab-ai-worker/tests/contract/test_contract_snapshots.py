"""契约快照的双向校验：重生成必须与入库文件逐字节一致。"""

from __future__ import annotations

import json

from ai_collab_worker.contract.schema import (
    SCHEMA_MODELS,
    check,
    render_all,
    render_schema,
    snapshot_path,
)


def test_snapshots_match_models() -> None:
    """入库快照 == 当前模型渲染结果（破坏性字段改动会在这里直接红）。"""

    assert check() == []


def test_every_registered_model_has_snapshot() -> None:
    for name in SCHEMA_MODELS:
        path = snapshot_path(name)
        assert path.is_file(), f"缺少快照 {path}"
        assert path.read_text(encoding="utf-8").strip(), f"快照为空 {path}"


def test_snapshot_directory_has_no_unregistered_files() -> None:
    names = {path.name for path in snapshot_path("prompt-manifest").parent.glob("*.schema.json")}
    assert names == {f"{name}.schema.json" for name in SCHEMA_MODELS}


def test_schema_rendering_is_stable() -> None:
    first = render_all()
    second = render_all()
    assert first == second
    for text in first.values():
        assert text.startswith("{")
        assert text.endswith("}\n")


def test_schemas_declare_schema_version_one() -> None:
    """跨语言响应模型必须自带 schemaVersion=1；错误信封按 §7.1 不带。"""

    for name in (
        "common-request",
        "prompt-document",
        "prompt-manifest",
        "query-plan-request",
        "query-plan-response",
        "agent-decide-request",
        "agent-decide-response",
        "rerank-request",
        "rerank-response",
        "planning-request",
        "planning-response",
        "memory-job-request",
        "memory-job-response",
        "mcp-initialize-result",
        "mcp-ping-result",
        "mcp-tools-list-result",
        "mcp-tools-call-result",
    ):
        schema = json.loads(render_schema(SCHEMA_MODELS[name]))
        assert schema["properties"]["schemaVersion"]["const"] == 1
        assert schema["properties"]["schemaVersion"]["default"] == 1


def test_registry_has_all_frozen_models() -> None:
    """W5 的 14 个模型 + W6 MCP Host 的 10 个内部信封/方法模型。"""

    assert len(SCHEMA_MODELS) == 24


def test_schemas_forbid_extra_fields() -> None:
    for name in SCHEMA_MODELS:
        schema = json.loads(render_schema(SCHEMA_MODELS[name]))
        assert schema["additionalProperties"] is False, name


def test_schema_titles_are_model_names() -> None:
    expected = {
        "common-request": "CommonRequest",
        "error-envelope": "ErrorEnvelope",
        "prompt-document": "PromptDocument",
        "prompt-manifest": "PromptManifest",
        "query-plan-request": "QueryPlanRequest",
        "query-plan-response": "QueryPlanResponse",
        "agent-decide-request": "AgentDecideRequest",
        "agent-decide-response": "AgentDecideResponse",
        "rerank-request": "RerankRequest",
        "rerank-response": "RerankResponse",
        "planning-request": "PlanningDraftRequest",
        "planning-response": "PlanningDraftResponse",
        "memory-job-request": "MemoryJobRequest",
        "memory-job-response": "MemoryJobResponse",
        "mcp-initialize-request": "McpInitializeRequest",
        "mcp-initialize-result": "McpInitializeResult",
        "mcp-json-rpc-error-response": "McpJsonRpcErrorResponse",
        "mcp-json-rpc-success": "McpJsonRpcSuccess",
        "mcp-ping-request": "McpPingRequest",
        "mcp-ping-result": "McpPingResult",
        "mcp-tools-call-request": "McpToolsCallRequest",
        "mcp-tools-call-result": "McpToolCallResult",
        "mcp-tools-list-request": "McpToolsListRequest",
        "mcp-tools-list-result": "McpToolsListResult",
    }
    for name, title in expected.items():
        assert json.loads(render_schema(SCHEMA_MODELS[name]))["title"] == title
