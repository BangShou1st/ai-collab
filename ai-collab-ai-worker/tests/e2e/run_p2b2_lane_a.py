#!/usr/bin/env python3
"""P2-B2 Lane A real-process evidence.

Starts a real Spring Boot Gateway, a real Uvicorn MCP Host and two independent
synthetic TLS MCP servers. The database intentionally contains approved,
draft, revoked and cross-project providers, so catalog and call evidence proves
admission and tenant fencing rather than only happy-path connectivity.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import contextlib
import json
import re
import statistics
import sys
import threading
import time
import traceback
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import load_der_private_key
from mcp_contract import JsonRpcError
from p2a_client import GatewayClient, HostClient, host_error_kind, host_result, http_json
from p2a_process import (
    ManagedProcess,
    find_free_port,
    run_psql,
    run_psql_script,
    wait_http,
    wait_port,
    write_json,
)
from p2a_seed import build_catalog, load_server_tools
from p2a_tls import TRUSTSTORE_PASSWORD, generate_tls_material

REPO_ROOT = Path(__file__).resolve().parents[3]
SERVER_PATH = REPO_ROOT / "tests" / "fixtures" / "mcp" / "local_readonly_server.py"
EVIDENCE_ROOT = REPO_ROOT / "docs" / "acceptance-evidence" / "2026-11-13" / "p2b2-release-readiness"
SHANGHAI = timezone(timedelta(hours=8))
PROJECT_A = "11111111-1111-4111-8111-111111111111"
PROJECT_B = "55555555-5555-4555-8555-555555555555"
SESSION_A = "22222222-2222-4222-8222-222222222222"
RUN_A = "33333333-3333-4333-8333-333333333333"
CONNECTIONS = {
    "ext_readonly": "44444444-4444-4444-8444-444444444401",
    "local_fixture": "44444444-4444-4444-8444-444444444402",
    "pending_provider": "44444444-4444-4444-8444-444444444403",
    "revoked_provider": "44444444-4444-4444-8444-444444444404",
    "tenant_probe": "44444444-4444-4444-8444-444444444405",
}
TOKEN_FIXTURE = (
    REPO_ROOT / "ai-collab-ai-worker" / "tests" / "fixtures" / "auth" / "ed25519-test-fixture.json"
)


def _b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _mcp_token(
    catalog_version: str,
    *,
    scope: str = "mcp:list mcp:call",
    expires_in: int = 300,
) -> str:
    fixture = json.loads(TOKEN_FIXTURE.read_text(encoding="utf-8"))
    loaded = load_der_private_key(
        base64.b64decode(fixture["privateKeyPkcs8"], validate=True), password=None
    )
    if not isinstance(loaded, Ed25519PrivateKey):
        raise RuntimeError("测试私钥不是 Ed25519")
    now = int(time.time())
    header = {"alg": "EdDSA", "typ": "JWT", "kid": fixture["kid"]}
    payload = {
        "iss": "ai-collab-backend",
        "sub": "backend",
        "aud": ["backend"],
        "scope": scope,
        "jti": str(uuid.uuid4()),
        "iat": now,
        "exp": now + expires_in,
        "projectRef": PROJECT_A,
        "runRef": RUN_A,
        "catalogVersion": catalog_version,
    }
    signing_input = (
        f"{_b64url(json.dumps(header, separators=(',', ':')).encode())}."
        f"{_b64url(json.dumps(payload, separators=(',', ':')).encode())}"
    )
    signature = loaded.sign(signing_input.encode("ascii"))
    return f"{signing_input}.{_b64url(signature)}"


def _timestamp() -> str:
    return datetime.now(SHANGHAI).isoformat(timespec="seconds")


def _load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key] = value
    return values


def _front_origin(env: dict[str, str]) -> str:
    configured = env.get("AUTH_ALLOWED_ORIGINS", "http://localhost:5173")
    return configured.split(",", 1)[0].strip()


def _login(api_url: str, env: dict[str, str]) -> str:
    result = http_json(
        f"{api_url}/api/v1/auth/login",
        payload={
            "username": env.get("DEMO_OWNER_USERNAME", "owner"),
            "password": env.get("DEMO_OWNER_PASSWORD", ""),
        },
        headers={"Origin": _front_origin(env)},
    )
    if result.status != 200 or not isinstance(result.body, dict):
        raise RuntimeError(f"登录失败 HTTP {result.status}: {result.text[:500]}")
    data = result.body.get("data")
    token = data.get("accessToken") if isinstance(data, dict) else None
    if not isinstance(token, str) or not token:
        raise RuntimeError("登录响应缺少 accessToken")
    return token


def _wait_owner_id(container: str, database: str, timeout: float = 120.0) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            owner = run_psql(
                container,
                database,
                "SELECT id FROM app_user WHERE username='owner' LIMIT 1",
            )
            if owner:
                return owner
        except RuntimeError:
            pass
        time.sleep(1)
    raise TimeoutError("local profile 未在超时内创建 demo owner")


def _create_database(container: str, database: str) -> None:
    if not re.fullmatch(r"[a-z0-9_]{3,63}", database):
        raise ValueError("测试数据库名只允许小写字母、数字和下划线")
    run_psql(
        container,
        "postgres",
        f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='{database}'",
    )
    run_psql(container, "postgres", f"DROP DATABASE IF EXISTS {database}")
    run_psql(container, "postgres", f"CREATE DATABASE {database}")


def _drop_database(container: str, database: str) -> None:
    with contextlib.suppress(RuntimeError):
        run_psql(
            container,
            "postgres",
            f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='{database}'",
        )
        run_psql(container, "postgres", f"DROP DATABASE IF EXISTS {database}")


def _connection(
    *,
    connection_id: str,
    project_id: str,
    owner_id: str,
    code: str,
    endpoint: str,
    tool_names: list[str],
    all_tools: list[dict[str, Any]],
) -> str:
    selected = [tool for tool in all_tools if tool["name"] in tool_names]
    allowlist = json.dumps([tool["name"] for tool in selected], ensure_ascii=False)
    discovered = json.dumps(selected, ensure_ascii=False)
    return (
        "INSERT INTO agent_mcp_connection("
        "id,project_id,code,name,transport,endpoint,auth_type,timeout_ms,max_result_bytes,"
        "tool_allowlist_json,discovered_tools_json,schema_hash,confirmed_schema_hash,"
        "enabled,created_by,version) VALUES ("
        f"'{connection_id}','{project_id}','{code}','{code}','STREAMABLE_HTTP',"
        f"'{endpoint}','NONE',5000,65536,'{allowlist}'::jsonb,'{discovered}'::jsonb,"
        f"repeat('a',64),repeat('a',64),true,'{owner_id}',1);"
    )


def _admission(
    *,
    connection_id: str,
    provider_type: str,
    status: str,
    owner_id: str,
) -> str:
    reviewed_by = "NULL" if status == "DRAFT" else f"'{owner_id}'"
    reviewed_at = "NULL" if status == "DRAFT" else "now()"
    revoked_by = f"'{owner_id}'" if status == "REVOKED" else "NULL"
    revoked_at = "now()" if status == "REVOKED" else "NULL"
    return (
        "INSERT INTO agent_mcp_provider_admission("
        "connection_id,provider_type,provider_owner,data_scope,retention_policy,terms_url,"
        "least_privilege_scope,read_only_endpoint,status,reviewed_by,reviewed_at,"
        "revoked_by,revoked_at,version) VALUES ("
        f"'{connection_id}','{provider_type}','p2b2-security','synthetic read-only metadata',"
        f"'ephemeral test data','urn:ai-collab:local-fixture','read-only',true,'{status}',"
        f"{reviewed_by},{reviewed_at},{revoked_by},{revoked_at},1);"
    )


def _seed_sql(owner_id: str, external_url: str, local_url: str) -> str:
    tools = load_server_tools()
    external_tools = [tools["echo_text"], tools["slow_echo"]]
    local_tools = [tools["echo_text"]]
    lines = [
        "BEGIN;",
        f"DELETE FROM project WHERE id IN ('{PROJECT_A}','{PROJECT_B}');",
        "INSERT INTO project(id,name,description,owner_id,created_by) VALUES "
        f"('{PROJECT_A}','P2-B2 Multi Server','synthetic only','{owner_id}','{owner_id}'),"
        f"('{PROJECT_B}','P2-B2 Tenant B','synthetic tenant boundary','{owner_id}','{owner_id}');",
        "INSERT INTO project_member(project_id,user_id,role,invited_by) VALUES "
        f"('{PROJECT_A}','{owner_id}','OWNER','{owner_id}'),"
        f"('{PROJECT_B}','{owner_id}','OWNER','{owner_id}');",
        "INSERT INTO agent_session(id,project_id,creator_id,title,status) VALUES "
        f"('{SESSION_A}','{PROJECT_A}','{owner_id}','P2-B2 E2E session','ACTIVE');",
        "INSERT INTO agent_run(id,session_id,project_id,requester_id,role,depth,goal,status) "
        f"VALUES ('{RUN_A}','{SESSION_A}','{PROJECT_A}','{owner_id}','SUPERVISOR',0,"
        "'P2-B2 multi server and tenant isolation','RUNNING');",
        _connection(
            connection_id=CONNECTIONS["ext_readonly"],
            project_id=PROJECT_A,
            owner_id=owner_id,
            code="ext_readonly",
            endpoint=external_url,
            tool_names=["echo_text", "slow_echo"],
            all_tools=external_tools,
        ),
        _connection(
            connection_id=CONNECTIONS["local_fixture"],
            project_id=PROJECT_A,
            owner_id=owner_id,
            code="local_fixture",
            endpoint=local_url,
            tool_names=["echo_text"],
            all_tools=local_tools,
        ),
        _connection(
            connection_id=CONNECTIONS["pending_provider"],
            project_id=PROJECT_A,
            owner_id=owner_id,
            code="pending_provider",
            endpoint=local_url,
            tool_names=["echo_text"],
            all_tools=local_tools,
        ),
        _connection(
            connection_id=CONNECTIONS["revoked_provider"],
            project_id=PROJECT_A,
            owner_id=owner_id,
            code="revoked_provider",
            endpoint=local_url,
            tool_names=["echo_text"],
            all_tools=local_tools,
        ),
        _connection(
            connection_id=CONNECTIONS["tenant_probe"],
            project_id=PROJECT_B,
            owner_id=owner_id,
            code="tenant_probe",
            endpoint=local_url,
            tool_names=["echo_text"],
            all_tools=local_tools,
        ),
        _admission(
            connection_id=CONNECTIONS["ext_readonly"],
            provider_type="EXTERNAL_READ_ONLY",
            status="APPROVED",
            owner_id=owner_id,
        ),
        _admission(
            connection_id=CONNECTIONS["local_fixture"],
            provider_type="LOCAL_FIXTURE",
            status="APPROVED",
            owner_id=owner_id,
        ),
        _admission(
            connection_id=CONNECTIONS["pending_provider"],
            provider_type="LOCAL_FIXTURE",
            status="DRAFT",
            owner_id=owner_id,
        ),
        _admission(
            connection_id=CONNECTIONS["revoked_provider"],
            provider_type="LOCAL_FIXTURE",
            status="REVOKED",
            owner_id=owner_id,
        ),
        _admission(
            connection_id=CONNECTIONS["tenant_probe"],
            provider_type="LOCAL_FIXTURE",
            status="APPROVED",
            owner_id=owner_id,
        ),
        "COMMIT;",
    ]
    return "\n".join(lines)


def _expected_catalog() -> Any:
    tools = load_server_tools()
    return build_catalog(
        {
            "ext_readonly": [tools["echo_text"], tools["slow_echo"]],
            "local_fixture": [tools["echo_text"]],
        }
    )


def _java_command(args: argparse.Namespace, truststore: Path) -> list[str]:
    jar = Path(args.java_jar)
    if not jar.is_absolute():
        from_cwd = (Path.cwd() / jar).resolve()
        jar = from_cwd if from_cwd.exists() else REPO_ROOT / jar
    if not jar.exists():
        raise FileNotFoundError(f"Java jar 不存在: {jar}")
    return [
        "java",
        f"-Djavax.net.ssl.trustStore={truststore}",
        f"-Djavax.net.ssl.trustStorePassword={TRUSTSTORE_PASSWORD}",
        "-Djavax.net.ssl.trustStoreType=PKCS12",
        "-jar",
        str(jar),
    ]


def _app_env(
    args: argparse.Namespace,
    env: dict[str, str],
    catalog_version: str,
    gateway_port: int,
    management_port: int,
) -> dict[str, str]:
    pg_port = env.get("AI_COLLAB_PG_HOST_PORT", "5433")
    redis_port = env.get("AI_COLLAB_REDIS_HOST_PORT", "6380")
    minio_port = env.get("AI_COLLAB_MINIO_HOST_PORT", "9002")
    values = {
        "SPRING_PROFILES_ACTIVE": "local",
        "SPRING_CONFIG_IMPORT": f"optional:file:{args.env_file.resolve().as_posix()}[.properties]",
        "SPRING_DATASOURCE_URL": f"jdbc:postgresql://127.0.0.1:{pg_port}/{args.pg_database}",
        "SPRING_DATASOURCE_USERNAME": args.pg_user,
        "SPRING_DATASOURCE_PASSWORD": env.get("POSTGRES_PASSWORD", ""),
        "AI_COLLAB_PG_HOST_PORT": str(pg_port),
        "AI_COLLAB_REDIS_HOST_PORT": str(redis_port),
        "REDIS_PASSWORD": env.get("REDIS_PASSWORD", ""),
        "MINIO_ENDPOINT": f"http://127.0.0.1:{minio_port}",
        "MINIO_ACCESS_KEY": env.get("MINIO_ROOT_USER", ""),
        "MINIO_SECRET_KEY": env.get("MINIO_ROOT_PASSWORD", ""),
        "MINIO_BUCKET": env.get("MINIO_BUCKET", "ai-collab"),
        "SERVER_PORT": str(gateway_port),
        "MANAGEMENT_SERVER_PORT": str(management_port),
        "MANAGEMENT_ENDPOINTS_EXPOSURE": "health,info,prometheus",
        "JWT_SECRET": env.get("JWT_SECRET", ""),
        "AUTH_ALLOWED_ORIGINS": _front_origin(env),
        "MODEL_CONFIG_MASTER_KEY": env.get("MODEL_CONFIG_MASTER_KEY", ""),
        "DEMO_OWNER_USERNAME": env.get("DEMO_OWNER_USERNAME", "owner"),
        "DEMO_OWNER_PASSWORD": env.get("DEMO_OWNER_PASSWORD", ""),
        "MAIL_ENABLED": "false",
        "AUTH_PUBLIC_REGISTRATION_ENABLED": "false",
        "MQ_ENABLED": "false",
        "AGENT_MCP_ALLOWED_HOSTS": "127.0.0.1,localhost",
        "AGENT_MCP_TEST_LOOPBACK_ENABLED": "true",
        "AGENT_MCP_GATEWAY_ENABLED": "true",
        "INTERNAL_AI_TOKEN_MODE": "ed25519",
        "INTERNAL_AI_ED25519_PRIVATE_KEY": _token_fixture()["privateKeyPkcs8"],
        "INTERNAL_AI_ED25519_PUBLIC_KEYS": (
            f"{_token_fixture()['kid']}={_token_fixture()['publicKeySpki']}"
        ),
        "INTERNAL_AI_ED25519_KID": _token_fixture()["kid"],
        "MCP_HOST_GATEWAY_URL": f"http://127.0.0.1:{gateway_port}",
    }
    return {key: value for key, value in values.items() if value != ""}


def _token_fixture() -> dict[str, Any]:
    return json.loads(TOKEN_FIXTURE.read_text(encoding="utf-8"))


def _host_command(args: argparse.Namespace, port: int) -> list[str]:
    return [
        "uv",
        "run",
        "--project",
        "ai-collab-ai-worker",
        "uvicorn",
        "ai_collab_worker.mcp_host_app:app",
        "--host",
        "127.0.0.1",
        "--port",
        str(port),
        "--log-level",
        "info",
    ]


def _host_ok(result: Any) -> Any:
    if result.status != 200:
        raise AssertionError(
            f"Host HTTP {result.status} kind={host_error_kind(result)}; body={result.text[:800]}"
        )
    return host_result(result)


def _expect_host_error(result: Any, expected: set[str]) -> str:
    kind = host_error_kind(result)
    if kind not in expected:
        raise AssertionError(
            f"Host HTTP {result.status} kind={kind}，期望 {sorted(expected)}；"
            f"body={result.text[:500]}"
        )
    return kind


def _tool_map(list_result: Any) -> dict[str, dict[str, Any]]:
    if not isinstance(list_result, dict) or not isinstance(list_result.get("tools"), list):
        raise AssertionError(f"tools/list 结果形状非法: {list_result!r}")
    return {str(tool["name"]): tool for tool in list_result["tools"]}


def _run_cases(
    *,
    args: argparse.Namespace,
    gateway: GatewayClient,
    host: HostClient,
    catalog_version: str,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    artifacts: dict[str, Any] = {}

    def record(case_id: str, status: str, evidence: Any = None, error: str = "") -> None:
        cases.append(
            {
                "id": case_id,
                "status": status,
                "evidence": evidence,
                "error": error,
                "at": _timestamp(),
            }
        )

    def run(case_id: str, fn: Any) -> Any:
        try:
            evidence = fn()
            record(case_id, "PASS", evidence)
            return evidence
        except BaseException as failure:
            record(case_id, "FAIL", error=f"{type(failure).__name__}: {failure}")
            return None

    java_list = run("A-02.java-list", lambda: gateway.call("tools/list", {"schemaVersion": 1}))
    host_list = run("A-02.host-list", lambda: _host_ok(host.list_tools()))
    if isinstance(java_list, dict):
        artifacts["javaList"] = java_list
    if isinstance(host_list, dict):
        artifacts["hostList"] = host_list

    def catalog_assertions() -> dict[str, Any]:
        if not isinstance(java_list, dict) or not isinstance(host_list, dict):
            raise AssertionError("Java/Host tools/list 缺失")
        java_tools = _tool_map(java_list)
        host_tools = _tool_map(host_list)
        expected_names = [
            "mcp.ext_readonly.echo_text",
            "mcp.ext_readonly.slow_echo",
            "mcp.local_fixture.echo_text",
        ]
        if sorted(java_tools) != expected_names:
            raise AssertionError(f"Java 目录异常: {sorted(java_tools)}")
        if java_tools != host_tools or java_list.get("catalogVersion") != catalog_version:
            raise AssertionError("Java/Host 目录或 catalogVersion 不一致")
        for forbidden in (
            "mcp.pending_provider.echo_text",
            "mcp.revoked_provider.echo_text",
            "mcp.tenant_probe.echo_text",
        ):
            if forbidden in java_tools:
                raise AssertionError(f"未获准或跨租户工具被暴露: {forbidden}")
        return {
            "catalogVersion": catalog_version,
            "toolCount": len(java_tools),
            "tools": sorted(java_tools),
            "unapprovedHidden": True,
            "crossTenantHidden": True,
        }

    run("A-01/A-02/A-03.catalog-admission-tenant", catalog_assertions)

    def call_tool(name: str, text: str, call_id: str) -> Any:
        tools = _tool_map(java_list)
        selected = tools.get(name)
        if selected is None:
            raise AssertionError(f"目录缺少 {name}")
        return _host_ok(
            host.call_tool(
                name=name,
                catalog_version=java_list["catalogVersion"],
                tool_schema_hash=selected["toolSchemaHash"],
                arguments={"text": text},
                call_id=call_id,
            )
        )

    run(
        "A-02.external-call",
        lambda: call_tool("mcp.ext_readonly.echo_text", "external-hello", "p2b2-external-1"),
    )
    run(
        "A-02.local-call",
        lambda: call_tool("mcp.local_fixture.echo_text", "local-hello", "p2b2-local-1"),
    )

    def blocked_call(name: str, call_id: str) -> str:
        tools = _tool_map(java_list)
        selected = tools.get(name)
        schema_hash = selected["toolSchemaHash"] if selected else "sha256:" + "0" * 64
        return _expect_host_error(
            host.call_tool(
                name=name,
                catalog_version=java_list["catalogVersion"],
                tool_schema_hash=schema_hash,
                arguments={"text": "must-not-run"},
                call_id=call_id,
            ),
            {"TOOL_DISABLED", "SCHEMA_DRIFT", "TOOL_REVOKED"},
        )

    run(
        "A-01.draft-provider-blocked",
        lambda: blocked_call("mcp.pending_provider.echo_text", "p2b2-pending-1"),
    )
    run(
        "A-01.revoked-provider-blocked",
        lambda: blocked_call("mcp.revoked_provider.echo_text", "p2b2-revoked-1"),
    )
    run(
        "A-03.cross-tenant-blocked",
        lambda: blocked_call("mcp.tenant_probe.echo_text", "p2b2-tenant-1"),
    )

    def hash_fence() -> dict[str, Any]:
        run_psql(
            args.pg_container,
            args.pg_database,
            "UPDATE agent_mcp_connection SET schema_hash=repeat('b',64),"
            f"version=version+1,updated_at=now() WHERE id='{CONNECTIONS['local_fixture']}'",
        )
        try:
            kind = blocked_call("mcp.local_fixture.echo_text", "p2b2-hash-1")
        finally:
            run_psql(
                args.pg_container,
                args.pg_database,
                "UPDATE agent_mcp_connection SET schema_hash=repeat('a',64),"
                f"version=version+1,updated_at=now() WHERE id='{CONNECTIONS['local_fixture']}'",
            )
        return {"kind": kind, "hashMismatchRemovedTool": True}

    run("A-06.hash-fence", hash_fence)

    def revocation_fence() -> dict[str, Any]:
        tools = _tool_map(java_list)
        slow = tools.get("mcp.ext_readonly.slow_echo")
        if slow is None:
            raise AssertionError("缺少 ext_readonly.slow_echo")
        captured: dict[str, Any] = {}

        def slow_call() -> None:
            captured["result"] = host.call_tool(
                name=slow["name"],
                catalog_version=java_list["catalogVersion"],
                tool_schema_hash=slow["toolSchemaHash"],
                arguments={"text": "revoked-inflight", "delayMs": 2500},
                call_id="p2b2-revoke-inflight",
            )

        worker = threading.Thread(target=slow_call, daemon=True)
        worker.start()
        time.sleep(0.6)
        run_psql(
            args.pg_container,
            args.pg_database,
            "UPDATE agent_mcp_provider_admission SET status='REVOKED',"
            "revoked_by=reviewed_by,revoked_at=now(),version=version+1,updated_at=now() "
            f"WHERE connection_id='{CONNECTIONS['ext_readonly']}'",
        )
        worker.join(timeout=20)
        if worker.is_alive():
            raise AssertionError("在途 slow_echo 未在 20s 内返回")
        inflight = captured.get("result")
        if inflight is None:
            raise AssertionError("在途调用没有结果")
        inflight_kind = host_error_kind(inflight)
        new_kind = blocked_call("mcp.ext_readonly.slow_echo", "p2b2-revoke-new")
        if inflight_kind not in {"TOOL_REVOKED", "OUTCOME_UNKNOWN"}:
            raise AssertionError(f"在途结果 kind={inflight_kind}")
        return {
            "inflightKind": inflight_kind,
            "newCallKind": new_kind,
            "inflightStatus": inflight.status,
        }

    run("A-04.revocation-inflight", revocation_fence)

    def audit_check() -> dict[str, Any]:
        audit = run_psql(
            args.pg_container,
            args.pg_database,
            "SELECT count(*) || '|' || coalesce(string_agg(detail::text,''),'') "
            "FROM audit_log WHERE project_id="
            f"'{PROJECT_A}' AND action IN "
            "('AGENT_MCP_GATEWAY_TOOLS_LIST','AGENT_MCP_GATEWAY_TOOLS_CALL')",
        )
        count_text, detail = audit.split("|", 1)
        leaks = [
            marker
            for marker in ("external-hello", "local-hello", "sk-synthetic", "apiKey")
            if marker in detail
        ]
        if int(count_text) <= 0:
            raise AssertionError("未发现 Gateway 审计行")
        if leaks:
            raise AssertionError(f"审计 detail 泄漏参数/结果: {leaks}")
        return {
            "auditRows": int(count_text),
            "payloadFieldsLeaked": leaks,
            "detailShape": "metadata-only",
        }

    run("A-04.audit-metadata-only", audit_check)

    def admission_rows() -> dict[str, Any]:
        rows = run_psql(
            args.pg_container,
            args.pg_database,
            "SELECT count(*) FILTER (WHERE status='APPROVED') || '|' || "
            "count(*) FILTER (WHERE status='DRAFT') || '|' || "
            "count(*) FILTER (WHERE status='REVOKED') "
            "FROM agent_mcp_provider_admission",
        )
        approved, draft, revoked = (int(value) for value in rows.split("|"))
        if (approved, draft, revoked) != (2, 1, 2):
            raise AssertionError(f"admission 状态计数异常: {rows}")
        return {"approved": approved, "draft": draft, "revoked": revoked}

    run("A-01.admission-state", admission_rows)
    return cases, artifacts


def _run_lane_b_cases(
    *,
    gateway: GatewayClient,
    host: HostClient,
    token: str,
    gateway_port: int,
    management_port: int,
    catalog_version: str,
    load_requests: int,
    load_concurrency: int,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    artifacts: dict[str, Any] = {}

    def record(case_id: str, status: str, evidence: Any = None, error: str = "") -> None:
        cases.append(
            {
                "id": case_id,
                "status": status,
                "evidence": evidence,
                "error": error,
                "at": _timestamp(),
            }
        )

    def run(case_id: str, fn: Any) -> Any:
        try:
            evidence = fn()
            record(case_id, "PASS", evidence)
            return evidence
        except BaseException as failure:
            record(case_id, "FAIL", error=f"{type(failure).__name__}: {failure}")
            return None

    current = gateway.call("tools/list", {"schemaVersion": 1})
    if not isinstance(current, dict) or not isinstance(current.get("catalogVersion"), str):
        raise AssertionError("无法读取当前 catalogVersion")
    current_catalog = current["catalogVersion"]
    tool = _tool_map(current).get("mcp.local_fixture.echo_text")
    if tool is None:
        raise AssertionError("缺少 local_fixture.echo_text")
    token = _mcp_token(current_catalog)
    host = HostClient(host.base_url, token)
    gateway_url = f"http://127.0.0.1:{gateway_port}/internal/ai/v1/mcp"
    ping = {
        "jsonrpc": "2.0",
        "id": "p2b2-auth-probe",
        "method": "ping",
        "params": {"schemaVersion": 1},
    }

    def tampered_token() -> dict[str, Any]:
        prefix, signature = token.rsplit(".", 1)
        index = len(signature) // 2
        replacement = "A" if signature[index] != "A" else "B"
        bad = f"{prefix}.{signature[:index]}{replacement}{signature[index + 1 :]}"
        result = http_json(gateway_url, payload=ping, token=bad)
        if result.status != 401:
            raise AssertionError(f"tampered token HTTP {result.status}")
        return {"httpStatus": result.status, "outcome": "UNAUTHORIZED"}

    run("B-01.tampered-token-rejected", tampered_token)

    def wrong_scope() -> dict[str, Any]:
        list_only = _mcp_token(current_catalog, scope="mcp:list")
        result = http_json(
            gateway_url,
            payload={
                "jsonrpc": "2.0",
                "id": "p2b2-scope-probe",
                "method": "tools/call",
                "params": {
                    "schemaVersion": 1,
                    "name": tool["name"],
                    "catalogVersion": current_catalog,
                    "toolSchemaHash": tool["toolSchemaHash"],
                    "callId": "p2b2-scope-probe",
                    "arguments": {"text": "scope"},
                },
            },
            token=list_only,
        )
        if result.status != 401:
            raise AssertionError(f"wrong scope HTTP {result.status}")
        return {"httpStatus": result.status, "outcome": "UNAUTHORIZED"}

    run("B-01.wrong-scope-rejected", wrong_scope)

    def latency() -> dict[str, Any]:
        latencies: list[float] = []

        def invoke(index: int) -> None:
            started = time.perf_counter()
            result = host.call_tool(
                name=tool["name"],
                catalog_version=current_catalog,
                tool_schema_hash=tool["toolSchemaHash"],
                arguments={"text": f"load-{index}"},
                call_id=f"p2b2-load-{index}",
            )
            if result.status != 200:
                raise AssertionError(f"load call HTTP {result.status}: {result.text[:300]}")
            latencies.append((time.perf_counter() - started) * 1000)

        for index in range(load_requests):
            invoke(index)
        with concurrent.futures.ThreadPoolExecutor(
            max_workers=max(1, load_concurrency)
        ) as executor:
            futures = [
                executor.submit(invoke, load_requests + index) for index in range(load_requests)
            ]
            for future in concurrent.futures.as_completed(futures):
                future.result()
        ordered = sorted(latencies)
        p50 = statistics.median(ordered)
        p95 = ordered[max(0, int(len(ordered) * 0.95) - 1)]
        p99 = ordered[max(0, int(len(ordered) * 0.99) - 1)]
        if p95 > 2000:
            raise AssertionError(f"local p95={p95:.3f}ms 超过 2000ms 预算")
        result = {
            "samples": len(ordered),
            "sequential": load_requests,
            "concurrent": load_requests,
            "concurrency": load_concurrency,
            "p50Ms": round(p50, 3),
            "p95Ms": round(p95, 3),
            "p99Ms": round(p99, 3),
            "maxMs": round(ordered[-1], 3),
            "budgetP95Ms": 2000,
            "localOnly": True,
        }
        artifacts["latency"] = result
        return result

    run("B-05.latency", latency)

    def metrics() -> dict[str, Any]:
        result = http_json(
            f"http://127.0.0.1:{management_port}/actuator/prometheus",
            method="GET",
            headers={"Accept": "text/plain"},
        )
        if result.status != 200:
            raise AssertionError(f"metrics HTTP {result.status}")
        required = [
            "mcp_gateway_requests_total",
            "mcp_gateway_latency_seconds",
            "mcp_gateway_tool_calls_total",
            "mcp_gateway_denied_total",
            "mcp_gateway_schema_drift_total",
            "mcp_gateway_inflight",
        ]
        missing = [name for name in required if name not in result.text]
        if missing:
            raise AssertionError(f"缺少指标: {missing}")
        evidence = {
            "httpStatus": result.status,
            "bytes": len(result.text.encode("utf-8")),
            "metrics": required,
        }
        artifacts["prometheus"] = evidence
        return evidence

    run("B-03.prometheus-metrics", metrics)
    return cases, artifacts


def _git_commit() -> str:
    try:
        import subprocess

        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            cwd=REPO_ROOT,
            text=True,
            timeout=5,
        ).strip()
    except Exception:
        return "unknown"


def _run(args: argparse.Namespace) -> int:
    env = _load_env(args.env_file)
    lane = "lane-b" if args.lane_b else "lane-a"
    run_dir = Path(args.work_dir) / f"{lane}-{datetime.now(SHANGHAI):%Y%m%d-%H%M%S}"
    run_dir.mkdir(parents=True, exist_ok=True)
    tls = generate_tls_material(run_dir / "tls")
    expected = _expected_catalog()
    _create_database(args.pg_container, args.pg_database)

    gateway_port = args.gateway_port or find_free_port()
    management_port = args.management_port or find_free_port()
    host_port = args.host_port or find_free_port()
    external_port = args.external_mcp_port or find_free_port()
    local_port = args.local_mcp_port or find_free_port()
    external_url = f"https://127.0.0.1:{external_port}/mcp"
    local_url = f"https://127.0.0.1:{local_port}/mcp"
    processes: list[ManagedProcess] = []
    cases: list[dict[str, Any]] = []
    artifacts: dict[str, Any] = {
        "expectedCatalog": expected.__dict__,
        "externalUrl": external_url,
        "localUrl": local_url,
    }
    exit_code = 1
    try:
        for name, port in (("external-mcp-tls", external_port), ("local-mcp-tls", local_port)):
            process = ManagedProcess(
                name,
                [
                    sys.executable,
                    str(SERVER_PATH),
                    "--host",
                    "127.0.0.1",
                    "--port",
                    str(port),
                    "--tls-cert",
                    str(tls.cert),
                    "--tls-key",
                    str(tls.key),
                    "--server-version",
                    name,
                ],
                REPO_ROOT,
                run_dir / "logs",
            ).start()
            processes.append(process)
            wait_port(port, timeout=15)

        token = _mcp_token(expected.catalog_version)
        java_env = _app_env(args, env, expected.catalog_version, gateway_port, management_port)
        java = ManagedProcess(
            "java-gateway",
            _java_command(args, tls.truststore),
            REPO_ROOT / "ai-collab-backend",
            run_dir / "logs",
            env=java_env,
        ).start()
        processes.append(java)
        wait_port(gateway_port, timeout=args.startup_timeout)
        gateway = GatewayClient(f"http://127.0.0.1:{gateway_port}", token)
        deadline = time.monotonic() + args.startup_timeout
        while time.monotonic() < deadline:
            try:
                gateway.call("ping", {"schemaVersion": 1})
                break
            except (OSError, RuntimeError, JsonRpcError):
                time.sleep(1)
        else:
            raise TimeoutError("Java Gateway 未在超时内响应 ping")

        owner_id = _wait_owner_id(args.pg_container, args.pg_database, timeout=args.startup_timeout)
        run_psql_script(
            args.pg_container,
            args.pg_database,
            _seed_sql(owner_id, external_url, local_url),
        )

        host = ManagedProcess(
            "python-host",
            _host_command(args, host_port),
            REPO_ROOT,
            run_dir / "logs",
            env={
                "MCP_HOST_GATEWAY_URL": f"http://127.0.0.1:{gateway_port}",
                "AI_WORKER_SERVICE_TOKEN": token,
            },
        ).start()
        processes.append(host)
        wait_port(host_port, timeout=args.startup_timeout)
        wait_http(f"http://127.0.0.1:{host_port}/healthz", timeout=args.startup_timeout)
        host_client = HostClient(f"http://127.0.0.1:{host_port}", token)
        cases, run_artifacts = _run_cases(
            args=args,
            gateway=gateway,
            host=host_client,
            catalog_version=expected.catalog_version,
        )
        if args.lane_b:
            lane_b_cases, lane_b_artifacts = _run_lane_b_cases(
                gateway=gateway,
                host=host_client,
                token=token,
                gateway_port=gateway_port,
                management_port=management_port,
                catalog_version=expected.catalog_version,
                load_requests=args.load_requests,
                load_concurrency=args.load_concurrency,
            )
            cases.extend(lane_b_cases)
            run_artifacts.update(lane_b_artifacts)
        artifacts.update(run_artifacts)
        exit_code = 0 if all(case["status"] != "FAIL" for case in cases) else 1
    except BaseException as failure:
        cases.append(
            {
                "id": "A-BOOTSTRAP",
                "status": "FAIL",
                "error": f"{type(failure).__name__}: {failure}",
                "traceback": traceback.format_exc(limit=5),
                "at": _timestamp(),
            }
        )
    finally:
        for process in reversed(processes):
            process.stop()
        if not args.keep_db:
            _drop_database(args.pg_container, args.pg_database)

    matrix = [
        {
            "id": case["id"],
            "status": case["status"],
            "error": case["error"],
        }
        for case in cases
    ]
    report = {
        "evidenceLevel": "TESTED(REAL_PROCESS)" if exit_code == 0 else "FAILED(REAL_PROCESS)",
        "mode": "lane-b-real" if args.lane_b else "lane-a-real",
        "generatedAt": _timestamp(),
        "commit": _git_commit(),
        "ports": {
            "gateway": gateway_port,
            "management": management_port,
            "host": host_port,
            "externalMcp": external_port,
            "localMcp": local_port,
        },
        "processes": [process.record() for process in processes],
        "cases": cases,
        "matrix": matrix,
        "artifacts": artifacts,
        "limits": {
            "databaseDropped": not args.keep_db,
            "thirdPartyCredentialsUsed": False,
            "externalProviderIs": "synthetic TLS MCP process with EXTERNAL_READ_ONLY admission",
        },
    }
    write_json(Path(args.output) / "real-process.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return exit_code


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=EVIDENCE_ROOT / "lane-a" / "raw")
    parser.add_argument("--work-dir", type=Path, default=REPO_ROOT / "target" / "p2b2-e2e")
    parser.add_argument("--env-file", type=Path, default=REPO_ROOT / ".env")
    parser.add_argument(
        "--java-jar", default="ai-collab-backend/target/ai-collab-backend-0.0.1-SNAPSHOT.jar"
    )
    parser.add_argument("--gateway-port", type=int, default=0)
    parser.add_argument("--management-port", type=int, default=0)
    parser.add_argument("--host-port", type=int, default=0)
    parser.add_argument("--external-mcp-port", type=int, default=0)
    parser.add_argument("--local-mcp-port", type=int, default=0)
    parser.add_argument("--startup-timeout", type=float, default=180.0)
    parser.add_argument("--pg-container", default="ai-collab-postgres")
    parser.add_argument("--pg-user", default="ai_collab")
    parser.add_argument("--pg-database", default="ai_collab_p2b2_a")
    parser.add_argument("--keep-db", action="store_true")
    parser.add_argument("--lane-b", action="store_true")
    parser.add_argument("--load-requests", type=int, default=50)
    parser.add_argument("--load-concurrency", type=int, default=10)
    args = parser.parse_args()
    return _run(args)


if __name__ == "__main__":
    raise SystemExit(main())
