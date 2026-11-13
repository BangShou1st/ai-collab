"""P2-A 测试目录与数据库种子。

真实进程 E2E 使用独立测试项目和固定 UUID；SQL 只操作明确的测试项目，结束后可定向清理。
"""

from __future__ import annotations

import copy
import importlib.util
import json
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from mcp_contract import (
    catalog_version,
    normalize_description,
    normalize_tool_name,
    tool_schema_hash,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
SERVER_PATH = REPO_ROOT / "tests" / "fixtures" / "mcp" / "local_readonly_server.py"

PROJECT_A = uuid.UUID("11111111-1111-4111-8111-111111111111")
SESSION_A = uuid.UUID("22222222-2222-4222-8222-222222222222")
RUN_A = uuid.UUID("33333333-3333-4333-8333-333333333333")
PROJECT_B = uuid.UUID("55555555-5555-4555-8555-555555555555")

CONNECTION_IDS = {
    "local_readonly": uuid.UUID("44444444-4444-4444-8444-444444444401"),
    "local_faults": uuid.UUID("44444444-4444-4444-8444-444444444402"),
    "metadata_probe": uuid.UUID("44444444-4444-4444-8444-444444444403"),
    "outside_probe": uuid.UUID("44444444-4444-4444-8444-444444444404"),
}

NO_ARGS_SCHEMA = {
    "type": "object",
    "properties": {},
    "additionalProperties": False,
}

SYNTHETIC_TOOLS = {
    "metadata_probe": {
        "name": "metadata_probe",
        "description": "合成元数据地址探测，仅用于 SSRF 拒绝测试",
        "inputSchema": copy.deepcopy(NO_ARGS_SCHEMA),
        "annotations": {"readOnlyHint": True},
    },
    "outside_probe": {
        "name": "outside_probe",
        "description": "合成 allowlist 外主机探测，仅用于出网拒绝测试",
        "inputSchema": copy.deepcopy(NO_ARGS_SCHEMA),
        "annotations": {"readOnlyHint": True},
    },
}


@dataclass(frozen=True)
class CatalogExpectation:
    tools: list[dict[str, Any]]
    catalog_version: str


def load_server_tools() -> dict[str, dict[str, Any]]:
    spec = importlib.util.spec_from_file_location("p2a_local_readonly_server", SERVER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载本地 MCP Server: {SERVER_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    tools = module.tools_for_variant("original")
    return {str(tool["name"]): copy.deepcopy(tool) for tool in tools}


def exposed_tool(connection_code: str, raw: dict[str, Any]) -> dict[str, Any]:
    normalized = normalize_tool_name(str(raw["name"]))
    exposed_name = f"mcp.{connection_code}.{normalized}"
    description = normalize_description(str(raw.get("description", "")))
    schema = raw["inputSchema"]
    annotations = {"readOnlyHint": True}
    return {
        "name": exposed_name,
        "description": description,
        "inputSchema": schema,
        "toolSchemaHash": tool_schema_hash(exposed_name, description, schema, annotations),
        "annotations": annotations,
    }


def build_catalog(connections: dict[str, list[dict[str, Any]]]) -> CatalogExpectation:
    tools = [
        exposed_tool(code, raw) for code, raw_tools in connections.items() for raw in raw_tools
    ]
    tools.sort(key=lambda item: item["name"])
    return CatalogExpectation(tools, catalog_version(tools))


def project_a_connection_tools() -> dict[str, list[dict[str, Any]]]:
    local = load_server_tools()
    faults = [
        "slow_echo",
        "error_echo",
        "rate_limited",
        "server_error",
        "partial_echo",
        "oversized_echo",
        "unsafe_payload",
        "injected_description",
    ]
    return {
        "local_readonly": [copy.deepcopy(local["echo_text"])],
        "local_faults": [copy.deepcopy(local[name]) for name in faults],
        "metadata_probe": [copy.deepcopy(SYNTHETIC_TOOLS["metadata_probe"])],
        "outside_probe": [copy.deepcopy(SYNTHETIC_TOOLS["outside_probe"])],
    }


def expected_project_a_catalog() -> CatalogExpectation:
    return build_catalog(project_a_connection_tools())


def project_a_seed_sql(owner_id: uuid.UUID, mcp_url: str) -> str:
    connections = project_a_connection_tools()
    lines = [
        "BEGIN;",
        f"DELETE FROM project WHERE id = '{PROJECT_A}';",
        (
            "INSERT INTO project(id,name,description,owner_id,created_by) VALUES "
            f"('{PROJECT_A}','P2-A Real Process E2E','synthetic local only',"
            f"'{owner_id}','{owner_id}');"
        ),
        (
            "INSERT INTO project_member(project_id,user_id,role,invited_by) VALUES "
            f"('{PROJECT_A}','{owner_id}','OWNER','{owner_id}');"
        ),
        (
            "INSERT INTO agent_session(id,project_id,creator_id,title,status) VALUES "
            f"('{SESSION_A}','{PROJECT_A}','{owner_id}','P2-A E2E session','ACTIVE');"
        ),
        (
            "INSERT INTO agent_run("
            "id,session_id,project_id,requester_id,role,depth,goal,status) VALUES "
            f"('{RUN_A}','{SESSION_A}','{PROJECT_A}','{owner_id}','SUPERVISOR',0,"
            "'P2-A real process E2E','RUNNING');"
        ),
    ]
    endpoints = {
        "local_readonly": mcp_url,
        "local_faults": mcp_url,
        "metadata_probe": "https://169.254.169.254/mcp",
        "outside_probe": "https://outside.example/mcp",
    }
    for code, raw_tools in connections.items():
        connection_id = CONNECTION_IDS[code]
        allowlist = json.dumps([str(tool["name"]) for tool in raw_tools], ensure_ascii=False)
        discovered = json.dumps(raw_tools, ensure_ascii=False)
        max_bytes = 1024 if code == "local_faults" else 32768
        timeout_ms = 1000 if code == "local_faults" else 3000
        lines.append(
            "INSERT INTO agent_mcp_connection("
            "id,project_id,code,name,transport,endpoint,auth_type,timeout_ms,max_result_bytes,"
            "tool_allowlist_json,discovered_tools_json,schema_hash,confirmed_schema_hash,"
            "enabled,created_by,version) VALUES ("
            f"'{connection_id}','{PROJECT_A}','{code}','{code}','STREAMABLE_HTTP',"
            f"'{endpoints[code]}','NONE',{timeout_ms},{max_bytes},"
            f"'{allowlist}'::jsonb,'{discovered}'::jsonb,"
            f"repeat('a',64),repeat('a',64),true,'{owner_id}',1);"
        )
        lines.append(
            "INSERT INTO agent_mcp_provider_admission("
            "connection_id,provider_type,provider_owner,data_scope,retention_policy,terms_url,"
            "least_privilege_scope,read_only_endpoint,status,reviewed_by,reviewed_at,version) "
            "VALUES ("
            f"'{connection_id}','LOCAL_FIXTURE','p2a-security','synthetic local MCP metadata',"
            f"'ephemeral test data','urn:ai-collab:local-fixture','read-only',true,'APPROVED',"
            f"'{owner_id}',now(),1);"
        )
    lines.append("COMMIT;")
    return "\n".join(lines)


def project_b_seed_sql(owner_id: uuid.UUID) -> str:
    return "\n".join(
        [
            "BEGIN;",
            f"DELETE FROM project WHERE id = '{PROJECT_B}';",
            (
                "INSERT INTO project(id,name,description,owner_id,created_by) VALUES "
                f"('{PROJECT_B}','P2-A Schema Drift','synthetic schema drift fixture',"
                f"'{owner_id}','{owner_id}');"
            ),
            (
                "INSERT INTO project_member(project_id,user_id,role,invited_by) VALUES "
                f"('{PROJECT_B}','{owner_id}','OWNER','{owner_id}');"
            ),
            "COMMIT;",
        ]
    )


def cleanup_sql() -> str:
    return "\n".join(
        [
            "BEGIN;",
            f"DELETE FROM project WHERE id IN ('{PROJECT_A}','{PROJECT_B}');",
            "COMMIT;",
        ]
    )


def disable_connection_sql(connection_id: uuid.UUID) -> str:
    return (
        "UPDATE agent_mcp_connection SET enabled=false,confirmed_schema_hash=NULL,"
        f"version=version+1,updated_at=now() WHERE id='{connection_id}';"
    )


def restore_connection_sql(connection_id: uuid.UUID) -> str:
    return (
        "UPDATE agent_mcp_connection SET enabled=true,confirmed_schema_hash=schema_hash,"
        f"version=version+1,updated_at=now() WHERE id='{connection_id}';"
    )


__all__ = [
    "CONNECTION_IDS",
    "PROJECT_A",
    "PROJECT_B",
    "RUN_A",
    "CatalogExpectation",
    "build_catalog",
    "cleanup_sql",
    "disable_connection_sql",
    "expected_project_a_catalog",
    "load_server_tools",
    "project_a_connection_tools",
    "project_a_seed_sql",
    "project_b_seed_sql",
    "restore_connection_sql",
]
