#!/usr/bin/env python3
"""P2-A 真实进程 E2E 与安全矩阵编排。

默认只启动本地 TLS 只读 MCP Server 做夹具自检。`--mode real` 还要求调用方提供
Spring Boot jar、测试环境文件和 PostgreSQL 容器，并启动真实 Java Gateway 与
Python FastAPI/Uvicorn Host 进程。真实模式绝不把 stub 结果标成 PASS。
"""

from __future__ import annotations

import argparse
import contextlib
import json
import re
import shlex
import sys
import threading
import time
import traceback
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from mcp_contract import JsonRpcError
from p2a_client import (
    GatewayClient,
    HostClient,
    TlsJsonRpcClient,
    host_error_kind,
    host_result,
    http_json,
)
from p2a_process import (
    ManagedProcess,
    find_free_port,
    run_psql,
    run_psql_script,
    wait_http,
    wait_port,
    wait_port_closed,
    write_json,
)
from p2a_seed import (
    CONNECTION_IDS,
    PROJECT_A,
    PROJECT_B,
    RUN_A,
    cleanup_sql,
    disable_connection_sql,
    expected_project_a_catalog,
    project_a_seed_sql,
    project_b_seed_sql,
    restore_connection_sql,
)
from p2a_tls import generate_tls_material

REPO_ROOT = Path(__file__).resolve().parents[3]
SERVER_PATH = REPO_ROOT / "tests" / "fixtures" / "mcp" / "local_readonly_server.py"
EVIDENCE_ROOT = REPO_ROOT / "docs" / "acceptance-evidence" / "2026-11-13" / "p2a-real-process"
SHANGHAI = timezone(timedelta(hours=8))
SYNTHETIC_TOKEN = "p2a-synthetic-internal-token"


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


def _api_data(result: Any) -> Any:
    if not 200 <= result.status < 300:
        raise RuntimeError(f"HTTP {result.status}: {result.text[:500]}")
    if not isinstance(result.body, dict):
        raise RuntimeError("API 响应不是 JSON object")
    if result.body.get("code") != "SUCCESS":
        raise RuntimeError(f"API 失败: {result.body.get('code')} {result.body.get('message')}")
    return result.body.get("data")


def _tool_map(list_result: Any) -> dict[str, dict[str, Any]]:
    if not isinstance(list_result, dict) or not isinstance(list_result.get("tools"), list):
        raise AssertionError(f"tools/list 结果形状非法: {list_result!r}")
    return {str(tool["name"]): tool for tool in list_result["tools"]}


def _expect_error_kind(thunk: Any, expected: set[str]) -> str:
    try:
        thunk()
    except JsonRpcError as failure:
        if failure.kind not in expected:
            raise AssertionError(f"错误 kind={failure.kind}，期望 {sorted(expected)}") from failure
        return failure.kind
    raise AssertionError(f"调用未失败，期望错误 {sorted(expected)}")


def _expect_host_error(result: Any, expected: set[str]) -> str:
    kind = host_error_kind(result)
    if kind not in expected:
        raise AssertionError(
            f"Host HTTP {result.status} kind={kind}，期望 {sorted(expected)}；"
            f"body={result.text[:500]}"
        )
    return kind


def _host_ok(result: Any) -> Any:
    if result.status != 200:
        raise AssertionError(
            f"Host HTTP {result.status} kind={host_error_kind(result)}；body={result.text[:800]}"
        )
    return host_result(result)


def _create_database(container: str, database: str, *, reset: bool) -> None:
    if not re.fullmatch(r"[a-z0-9_]{3,63}", database):
        raise ValueError("测试数据库名只允许小写字母、数字和下划线")
    if reset:
        run_psql(
            container,
            "postgres",
            f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='{database}'",
        )
        run_psql(container, "postgres", f"DROP DATABASE IF EXISTS {database}")
    exists = run_psql(
        container,
        "postgres",
        f"SELECT 1 FROM pg_database WHERE datname='{database}'",
    )
    if exists != "1":
        run_psql(container, "postgres", f"CREATE DATABASE {database}")


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


def _login(api_url: str, env: dict[str, str]) -> str:
    result = http_json(
        f"{api_url}/api/v1/auth/login",
        payload={
            "username": env.get("DEMO_OWNER_USERNAME", "owner"),
            "password": env.get("DEMO_OWNER_PASSWORD", ""),
        },
        headers={"Origin": _front_origin(env)},
    )
    data = _api_data(result)
    token = data.get("accessToken") if isinstance(data, dict) else None
    if not isinstance(token, str) or not token:
        raise RuntimeError("登录响应缺少 accessToken")
    return token


def _front_origin(env: dict[str, str]) -> str:
    configured = env.get("AUTH_ALLOWED_ORIGINS", "http://localhost:5173")
    return configured.split(",", 1)[0].strip()


def _create_admin_connection(
    api_url: str,
    jwt: str,
    project_id: uuid.UUID,
    mcp_url: str,
) -> dict[str, Any]:
    result = http_json(
        f"{api_url}/api/v1/projects/{project_id}/agent/mcp-connections",
        payload={
            "code": "schema-probe",
            "name": "P2-A schema probe",
            "transport": "STREAMABLE_HTTP",
            "endpoint": mcp_url,
            "stdioCommand": None,
            "authType": "NONE",
            "credential": None,
            "timeoutMs": 3000,
            "maxResultBytes": 32768,
            "toolAllowlist": ["echo_text", "injected_description"],
            "resourceAllowlist": [],
            "version": 0,
        },
        token=jwt,
    )
    data = _api_data(result)
    if not isinstance(data, dict) or "id" not in data:
        raise RuntimeError(f"创建 MCP 连接响应非法: {data!r}")
    return data


def _discover(api_url: str, jwt: str, project_id: uuid.UUID, connection_id: str) -> dict[str, Any]:
    result = http_json(
        f"{api_url}/api/v1/projects/{project_id}/agent/mcp-connections/{connection_id}/discover",
        method="POST",
        token=jwt,
    )
    data = _api_data(result)
    if not isinstance(data, dict):
        raise RuntimeError(f"discover 响应非法: {data!r}")
    return data


def _enable(
    api_url: str,
    jwt: str,
    project_id: uuid.UUID,
    connection_id: str,
    version: int,
) -> dict[str, Any]:
    result = http_json(
        f"{api_url}/api/v1/projects/{project_id}/agent/mcp-connections/"
        f"{connection_id}/enable?version={version}",
        method="POST",
        token=jwt,
    )
    data = _api_data(result)
    if not isinstance(data, dict):
        raise RuntimeError(f"enable 响应非法: {data!r}")
    return data


def _set_schema_variant(control_url: str, mode: str, ca_file: Path | None = None) -> None:
    result = http_json(f"{control_url}?mode={mode}", method="POST", ca_file=ca_file)
    if result.status != 200:
        raise RuntimeError(f"切换 schema 失败: HTTP {result.status} {result.text[:300]}")


def _java_command(args: argparse.Namespace, tls_truststore: Path) -> list[str]:
    if args.java_command:
        return shlex.split(args.java_command, posix=sys.platform != "win32")
    jar = Path(args.java_jar)
    if not jar.is_absolute():
        jar = REPO_ROOT / jar
    if not jar.exists():
        raise FileNotFoundError(f"Java jar 不存在: {jar}")
    return [
        "java",
        f"-Djavax.net.ssl.trustStore={tls_truststore}",
        f"-Djavax.net.ssl.trustStorePassword={args.truststore_password}",
        "-Djavax.net.ssl.trustStoreType=PKCS12",
        "-jar",
        str(jar),
    ]


def _host_command(args: argparse.Namespace, port: int) -> list[str]:
    if args.host_command:
        return shlex.split(args.host_command, posix=sys.platform != "win32")
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


def _app_env(
    args: argparse.Namespace,
    env: dict[str, str],
    catalog_version: str,
    gateway_port: int,
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
        "MANAGEMENT_SERVER_PORT": str(args.management_port),
        "JWT_SECRET": env.get("JWT_SECRET", ""),
        "AUTH_ALLOWED_ORIGINS": _front_origin(env),
        "MODEL_CONFIG_MASTER_KEY": env.get("MODEL_CONFIG_MASTER_KEY", ""),
        "DEMO_OWNER_USERNAME": env.get("DEMO_OWNER_USERNAME", "owner"),
        "DEMO_OWNER_PASSWORD": env.get("DEMO_OWNER_PASSWORD", ""),
        "MAIL_ENABLED": "false",
        "AUTH_PUBLIC_REGISTRATION_ENABLED": "false",
        "MQ_ENABLED": "false",
        "AGENT_MCP_ALLOWED_HOSTS": "127.0.0.1,localhost,169.254.169.254,outside.example",
        "AGENT_MCP_TEST_LOOPBACK_ENABLED": "true",
        "AGENT_MCP_GATEWAY_ENABLED": "true",
        "AGENT_MCP_GATEWAY_STATIC_TEST_PROJECT_REF": str(PROJECT_A),
        "AGENT_MCP_GATEWAY_STATIC_TEST_RUN_REF": str(RUN_A),
        "AGENT_MCP_GATEWAY_STATIC_TEST_CATALOG_VERSION": catalog_version,
        "RERANK_SERVICE_TOKEN": SYNTHETIC_TOKEN,
        "INTERNAL_AI_TOKEN_MODE": "static",
        "AI_WORKER_SERVICE_TOKEN": SYNTHETIC_TOKEN,
        "MCP_HOST_GATEWAY_URL": f"http://127.0.0.1:{gateway_port}",
    }
    return {key: value for key, value in values.items() if value != ""}


def _real_evidence(result: dict[str, Any], output: Path) -> None:
    write_json(output, result)


def _run_offline(args: argparse.Namespace) -> int:
    run_dir = Path(args.work_dir) / f"offline-{datetime.now(SHANGHAI).strftime('%Y%m%d-%H%M%S')}"
    run_dir.mkdir(parents=True, exist_ok=True)
    tls = generate_tls_material(run_dir / "tls")
    port = find_free_port()
    process = ManagedProcess(
        "local-mcp-tls",
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
        ],
        REPO_ROOT,
        run_dir / "logs",
    ).start()
    scenarios: list[dict[str, Any]] = []
    try:
        wait_port(port, timeout=15)
        client = TlsJsonRpcClient(
            f"https://127.0.0.1:{port}/mcp",
            ca_file=tls.cert,
            timeout=5,
        )
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                client.call("ping", {})
                break
            except Exception:
                time.sleep(0.2)
        else:
            raise TimeoutError("TLS MCP Server 未就绪")

        listed = client.call("tools/list", {})
        names = {tool["name"] for tool in listed["tools"]}
        expected = {
            "echo_text",
            "slow_echo",
            "error_echo",
            "write_probe",
            "rate_limited",
            "server_error",
            "partial_echo",
            "oversized_echo",
            "unsafe_payload",
            "injected_description",
        }
        assert expected <= names, sorted(expected - names)
        scenarios.append({"name": "tls-tools-list", "status": "PASS", "evidence": sorted(names)})

        rate = http_json(
            f"https://127.0.0.1:{port}/mcp",
            payload={
                "jsonrpc": "2.0",
                "id": "offline-rate",
                "method": "tools/call",
                "params": {"name": "rate_limited", "arguments": {}},
            },
            ca_file=tls.cert,
        )
        assert rate.status == 429, rate.status
        scenarios.append({"name": "fixture-429", "status": "PASS", "evidence": {"status": 429}})

        error = http_json(
            f"https://127.0.0.1:{port}/mcp",
            payload={
                "jsonrpc": "2.0",
                "id": "offline-500",
                "method": "tools/call",
                "params": {"name": "server_error", "arguments": {}},
            },
            ca_file=tls.cert,
        )
        assert error.status == 500, error.status
        scenarios.append({"name": "fixture-500", "status": "PASS", "evidence": {"status": 500}})

        partial = http_json(
            f"https://127.0.0.1:{port}/mcp",
            payload={
                "jsonrpc": "2.0",
                "id": "offline-206",
                "method": "tools/call",
                "params": {"name": "partial_echo", "arguments": {"text": "partial"}},
            },
            ca_file=tls.cert,
        )
        assert partial.status == 206, partial.status
        scenarios.append({"name": "fixture-206", "status": "PASS", "evidence": {"status": 206}})

        unsafe = client.call(
            "tools/call",
            {"name": "unsafe_payload", "arguments": {"text": "unsafe"}},
        )
        assert "binary_data" in unsafe["structuredContent"]
        assert "file_path" in unsafe["structuredContent"]
        scenarios.append(
            {
                "name": "fixture-unsafe-payload",
                "status": "PASS",
                "evidence": sorted(unsafe["structuredContent"]),
            }
        )
    except BaseException as failure:
        scenarios.append(
            {
                "name": "offline-tls",
                "status": "FAIL",
                "error": f"{type(failure).__name__}: {failure}",
                "traceback": traceback.format_exc(limit=3),
            }
        )
    finally:
        process.stop()
        closed = wait_port_closed(port, timeout=5)
    report = {
        "evidenceLevel": "FIXTURE_READY",
        "mode": "offline",
        "generatedAt": _timestamp(),
        "python": sys.version.split()[0],
        "scenarios": scenarios,
        "process": process.record(),
        "portClosed": closed,
    }
    _real_evidence(report, Path(args.output) / "offline-tls.json")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if all(item["status"] == "PASS" for item in scenarios) and closed else 1


def _real_cases(
    *,
    host: HostClient,
    gateway: GatewayClient,
    api_url: str,
    mcp_url: str,
    control_url: str,
    control_ca_file: Path | None,
    jwt: str,
    env: dict[str, str],
    args: argparse.Namespace,
    catalog_version: str,
    expected_catalog: Any,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    artifacts: dict[str, Any] = {}

    def record(name: str, status: str, evidence: Any = None, error: str = "") -> None:
        cases.append(
            {
                "id": name,
                "status": status,
                "evidence": evidence,
                "error": error,
                "at": _timestamp(),
            }
        )

    def run_case(name: str, fn: Any) -> Any:
        try:
            evidence = fn()
            record(name, "PASS", evidence)
            return evidence
        except BaseException as failure:
            record(name, "FAIL", error=f"{type(failure).__name__}: {failure}")
            return None

    java_list = run_case("M-11.java-list", lambda: gateway.call("tools/list", {"schemaVersion": 1}))
    host_list = run_case("M-11.host-list", lambda: _host_ok(host.list_tools()))
    if isinstance(java_list, dict):
        artifacts["javaList"] = java_list
    if isinstance(host_list, dict):
        artifacts["hostList"] = host_list
    if isinstance(java_list, dict) and isinstance(host_list, dict):
        java_tools = {tool["name"]: tool for tool in java_list.get("tools", [])}
        host_tools = {tool["name"]: tool for tool in host_list.get("tools", [])}
        expected_tools = {tool["name"]: tool for tool in expected_catalog.tools}
        echo = java_tools.get("mcp.local_readonly.echo_text")
        expected_echo = expected_tools.get("mcp.local_readonly.echo_text")
        if echo is None:
            record("M-11.fixture-subset", "FAIL", error="缺少 mcp.local_readonly.echo_text")
        elif expected_echo is None:
            record("M-11.fixture-subset", "FAIL", error="fixture 缺少 echo_text 期望")
        elif echo != expected_echo:
            record(
                "M-11.fixture-subset",
                "FAIL",
                {"actual": echo, "expected": expected_echo},
            )
        elif java_list.get("catalogVersion") != expected_catalog.catalog_version:
            record(
                "M-11.fixture-subset",
                "FAIL",
                {
                    "actualCatalogVersion": java_list.get("catalogVersion"),
                    "expectedCatalogVersion": expected_catalog.catalog_version,
                },
            )
        else:
            record(
                "M-11.fixture-subset",
                "PASS",
                {
                    "catalogVersion": java_list.get("catalogVersion"),
                    "echoToolSchemaHash": echo.get("toolSchemaHash"),
                    "toolCount": len(java_tools),
                },
            )
        same = (
            java_list.get("catalogVersion") == expected_catalog.catalog_version
            and java_list.get("catalogVersion") == host_list.get("catalogVersion")
            and java_tools == host_tools
        )
        record("M-11.host-java-equal", "PASS" if same else "FAIL", {"equal": same})
    if isinstance(java_list, dict):
        echo = _tool_map(java_list).get("mcp.local_readonly.echo_text")
        if echo:
            result = run_case(
                "M-04.result-sanitization",
                lambda: _host_ok(
                    host.call_tool(
                        name=echo["name"],
                        catalog_version=java_list["catalogVersion"],
                        tool_schema_hash=echo["toolSchemaHash"],
                        arguments={"text": "hello"},
                        call_id="p2a-success-1",
                    )
                ),
            )
            if isinstance(result, dict):
                serialized = json.dumps(result, ensure_ascii=False)
                data = result.get("data")
                good = (
                    result.get("outcome") == "SUCCEEDED"
                    and result.get("untrusted") is True
                    and result.get("truncated") is False
                    and isinstance(data, dict)
                    and data.get("apiKey") == "[REDACTED]"
                    and data.get("authorization") == "[REDACTED]"
                    and "sk-synthetic-admission-test-0001" not in serialized
                    and result.get("warnings")
                )
                record(
                    "M-04.result-sanitization-assert",
                    "PASS" if good else "FAIL",
                    {"outcome": result.get("outcome"), "warnings": result.get("warnings")},
                )

            run_case(
                "M-06.write-probe-denied",
                lambda: _expect_host_error(
                    host.call_tool(
                        name="mcp.local_readonly.write_probe",
                        catalog_version=java_list["catalogVersion"],
                        tool_schema_hash="sha256:" + "0" * 64,
                        arguments={"text": "must-not-run"},
                        call_id="p2a-denied-1",
                    ),
                    {"TOOL_DISABLED"},
                ),
            )

    if isinstance(java_list, dict):
        tools = _tool_map(java_list)
        metadata = tools.get("mcp.metadata_probe.metadata_probe")
        outside = tools.get("mcp.outside_probe.outside_probe")
        unsafe = tools.get("mcp.local_faults.unsafe_payload")
        oversized = tools.get("mcp.local_faults.oversized_echo")
        rate = tools.get("mcp.local_faults.rate_limited")
        server_error = tools.get("mcp.local_faults.server_error")
        partial = tools.get("mcp.local_faults.partial_echo")
        slow = tools.get("mcp.local_faults.slow_echo")
        error = tools.get("mcp.local_faults.error_echo")
        injected = tools.get("mcp.local_faults.injected_description")

        if injected:
            description = str(injected.get("description", ""))
            good = (
                "[REMOVED_INSTRUCTION]" in description
                and "Ignore all previous" not in description
                and "忽略之前的系统规则" not in description
            )
            record(
                "M-03.description-injection",
                "PASS" if good else "FAIL",
                {"description": description},
            )

        if metadata:
            run_case(
                "M-01.metadata-rejected",
                lambda: _expect_host_error(
                    host.call_tool(
                        name=metadata["name"],
                        catalog_version=java_list["catalogVersion"],
                        tool_schema_hash=metadata["toolSchemaHash"],
                        arguments={},
                        call_id="p2a-metadata-1",
                    ),
                    {"NOT_EXECUTED"},
                ),
            )
        if outside:
            run_case(
                "M-01.allowlist-outside-rejected",
                lambda: _expect_host_error(
                    host.call_tool(
                        name=outside["name"],
                        catalog_version=java_list["catalogVersion"],
                        tool_schema_hash=outside["toolSchemaHash"],
                        arguments={},
                        call_id="p2a-outside-1",
                    ),
                    {"NOT_EXECUTED"},
                ),
            )

        if unsafe:
            result = run_case(
                "M-10.unsafe-payload",
                lambda: _host_ok(
                    host.call_tool(
                        name=unsafe["name"],
                        catalog_version=java_list["catalogVersion"],
                        tool_schema_hash=unsafe["toolSchemaHash"],
                        arguments={"text": "unsafe"},
                        call_id="p2a-unsafe-1",
                    )
                ),
            )
            if isinstance(result, dict):
                data = result.get("data", {})
                good = (
                    data.get("binary_data") == "[UNSUPPORTED_BINARY_OR_PATH]"
                    and data.get("file_path") == "[UNSUPPORTED_BINARY_OR_PATH]"
                    and data.get("apiKey") == "[REDACTED]"
                    and data.get("authorization") == "[REDACTED]"
                    and result.get("untrusted") is True
                )
                record("M-10.unsafe-payload-assert", "PASS" if good else "FAIL", data)

        if oversized:
            run_case(
                "M-10.oversized",
                lambda: _expect_host_error(
                    host.call_tool(
                        name=oversized["name"],
                        catalog_version=java_list["catalogVersion"],
                        tool_schema_hash=oversized["toolSchemaHash"],
                        arguments={"size": 6000},
                        call_id="p2a-oversized-1",
                    ),
                    {"TRUNCATED", "OUTCOME_UNKNOWN"},
                ),
            )
        if rate:
            run_case(
                "M-09.rate-limited",
                lambda: _expect_host_error(
                    host.call_tool(
                        name=rate["name"],
                        catalog_version=java_list["catalogVersion"],
                        tool_schema_hash=rate["toolSchemaHash"],
                        arguments={},
                        call_id="p2a-rate-1",
                    ),
                    {"RATE_LIMITED"},
                ),
            )
        if server_error:
            run_case(
                "M-09.5xx",
                lambda: _expect_host_error(
                    host.call_tool(
                        name=server_error["name"],
                        catalog_version=java_list["catalogVersion"],
                        tool_schema_hash=server_error["toolSchemaHash"],
                        arguments={},
                        call_id="p2a-5xx-1",
                    ),
                    {"OUTCOME_UNKNOWN"},
                ),
            )
        if partial:
            run_case(
                "M-09.partial",
                lambda: _expect_host_error(
                    host.call_tool(
                        name=partial["name"],
                        catalog_version=java_list["catalogVersion"],
                        tool_schema_hash=partial["toolSchemaHash"],
                        arguments={"text": "partial"},
                        call_id="p2a-partial-1",
                    ),
                    {"PARTIAL"},
                ),
            )
        if error:
            run_case(
                "M-09.unknown",
                lambda: _expect_host_error(
                    host.call_tool(
                        name=error["name"],
                        catalog_version=java_list["catalogVersion"],
                        tool_schema_hash=error["toolSchemaHash"],
                        arguments={},
                        call_id="p2a-unknown-1",
                    ),
                    {"OUTCOME_UNKNOWN"},
                ),
            )
        if slow:
            run_case(
                "M-09.timeout",
                lambda: _expect_host_error(
                    host.call_tool(
                        name=slow["name"],
                        catalog_version=java_list["catalogVersion"],
                        tool_schema_hash=slow["toolSchemaHash"],
                        arguments={"text": "slow", "delayMs": 5000},
                        call_id="p2a-timeout-1",
                    ),
                    {"OUTCOME_UNKNOWN", "DEADLINE_EXCEEDED"},
                ),
            )

    def forged_identity() -> str:
        return _expect_error_kind(
            lambda: gateway.call(
                "tools/call",
                {
                    "schemaVersion": 1,
                    "name": "mcp.local_readonly.echo_text",
                    "catalogVersion": catalog_version,
                    "toolSchemaHash": "sha256:" + "0" * 64,
                    "callId": "p2a-forged-1",
                    "projectRef": str(PROJECT_B),
                    "userId": str(uuid.uuid4()),
                    "arguments": {"text": "forged"},
                },
            ),
            {"PARAM_INVALID"},
        )

    run_case("M-07.forged-identity", forged_identity)

    def schema_drift() -> dict[str, Any]:
        created = _create_admin_connection(api_url, jwt, PROJECT_B, mcp_url)
        discovered = _discover(api_url, jwt, PROJECT_B, created["id"])
        enabled = _enable(api_url, jwt, PROJECT_B, created["id"], int(discovered["version"]))
        _set_schema_variant(control_url, "changed", control_ca_file)
        try:
            drift = _discover(api_url, jwt, PROJECT_B, created["id"])
        finally:
            _set_schema_variant(control_url, "original", control_ca_file)
        audit_count = run_psql(
            args.pg_container,
            args.pg_database,
            "SELECT count(*) FROM audit_log "
            f"WHERE project_id='{PROJECT_B}' AND action='AGENT_MCP_SCHEMA_CHANGED'",
        )
        good = (
            enabled.get("enabled") is True
            and drift.get("enabled") is False
            and drift.get("schemaConfirmed") is False
            and audit_count != "0"
        )
        if not good:
            raise AssertionError(
                f"schema drift 未停用/审计: enabled={enabled.get('enabled')} "
                f"driftEnabled={drift.get('enabled')} audit={audit_count}"
            )
        return {
            "enabledBefore": enabled.get("enabled"),
            "enabledAfterDrift": drift.get("enabled"),
            "schemaConfirmedAfterDrift": drift.get("schemaConfirmed"),
            "auditRows": audit_count,
        }

    run_case("M-05.schema-drift-disable", schema_drift)

    def revocation() -> dict[str, Any]:
        if not isinstance(java_list, dict):
            raise AssertionError("java tools/list 不可用")
        slow = _tool_map(java_list).get("mcp.local_faults.slow_echo")
        if not slow:
            raise AssertionError("缺少 slow_echo")
        captured: dict[str, Any] = {}

        def slow_call() -> None:
            captured["result"] = host.call_tool(
                name=slow["name"],
                catalog_version=java_list["catalogVersion"],
                tool_schema_hash=slow["toolSchemaHash"],
                arguments={"text": "revoke", "delayMs": 2500},
                call_id="p2a-revoke-inflight",
            )

        worker = threading.Thread(target=slow_call, daemon=True)
        worker.start()
        time.sleep(0.5)
        run_psql(
            args.pg_container,
            args.pg_database,
            disable_connection_sql(CONNECTION_IDS["local_faults"]),
        )
        worker.join(timeout=20)
        if worker.is_alive():
            raise AssertionError("在途 slow_echo 未在 20s 内返回")
        inflight = captured.get("result")
        if inflight is None:
            raise AssertionError("在途调用没有结果")
        inflight_kind = host_error_kind(inflight)
        new_call = host.call_tool(
            name=slow["name"],
            catalog_version=java_list["catalogVersion"],
            tool_schema_hash=slow["toolSchemaHash"],
            arguments={"text": "after-revoke", "delayMs": 0},
            call_id="p2a-revoke-new",
        )
        new_kind = host_error_kind(new_call)
        run_psql(
            args.pg_container,
            args.pg_database,
            restore_connection_sql(CONNECTION_IDS["local_faults"]),
        )
        if inflight_kind not in {"TOOL_REVOKED", "OUTCOME_UNKNOWN"}:
            raise AssertionError(f"在途结果 kind={inflight_kind}")
        if new_kind not in {"TOOL_DISABLED", "SCHEMA_DRIFT", "TOOL_REVOKED"}:
            raise AssertionError(f"撤权后新调用 kind={new_kind}")
        return {
            "inflightKind": inflight_kind,
            "newCallKind": new_kind,
            "inflightStatus": inflight.status,
            "newCallStatus": new_call.status,
        }

    run_case("M-08.revocation-fence", revocation)
    return cases, artifacts


def _run_real(args: argparse.Namespace) -> int:
    env_file = Path(args.env_file)
    if not env_file.exists():
        raise FileNotFoundError(f"环境文件不存在: {env_file}")
    env = _load_env(env_file)
    run_dir = Path(args.work_dir) / f"real-{datetime.now(SHANGHAI).strftime('%Y%m%d-%H%M%S')}"
    run_dir.mkdir(parents=True, exist_ok=True)
    tls = generate_tls_material(run_dir / "tls")
    expected = expected_project_a_catalog()
    _create_database(args.pg_container, args.pg_database, reset=not args.reuse_db)

    gateway_port = args.gateway_port or find_free_port()
    host_port = args.host_port or find_free_port()
    api_url = f"http://127.0.0.1:{gateway_port}"
    mcp_url = f"https://127.0.0.1:{args.mcp_port}/mcp"
    control_url = f"https://127.0.0.1:{args.mcp_port}/__control/schema"
    local_server = ManagedProcess(
        "local-mcp-tls",
        [
            sys.executable,
            str(SERVER_PATH),
            "--host",
            "127.0.0.1",
            "--port",
            str(args.mcp_port),
            "--tls-cert",
            str(tls.cert),
            "--tls-key",
            str(tls.key),
        ],
        REPO_ROOT,
        run_dir / "logs",
    ).start()
    java: ManagedProcess | None = None
    host: ManagedProcess | None = None
    records: list[dict[str, Any]] = []
    artifacts: dict[str, Any] = {"expectedCatalog": expected.__dict__}
    cases: list[dict[str, Any]] = []
    exit_code = 1
    try:
        wait_port(args.mcp_port, timeout=15)
        java_env = _app_env(args, env, expected.catalog_version, gateway_port)
        java = ManagedProcess(
            "java-gateway",
            _java_command(args, tls.truststore),
            REPO_ROOT / "ai-collab-backend",
            run_dir / "logs",
            env=java_env,
        ).start()
        wait_port(gateway_port, timeout=args.startup_timeout)
        gateway = GatewayClient(api_url, SYNTHETIC_TOKEN)
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
            project_a_seed_sql(uuid.UUID(owner_id), mcp_url),
        )
        run_psql_script(
            args.pg_container,
            args.pg_database,
            project_b_seed_sql(uuid.UUID(owner_id)),
        )
        host = ManagedProcess(
            "python-host",
            _host_command(args, host_port),
            REPO_ROOT,
            run_dir / "logs",
            env={
                "MCP_HOST_GATEWAY_URL": api_url,
                "AI_WORKER_SERVICE_TOKEN": SYNTHETIC_TOKEN,
            },
        ).start()
        wait_port(host_port, timeout=args.startup_timeout)
        wait_http(f"http://127.0.0.1:{host_port}/healthz", timeout=args.startup_timeout)
        host_client = HostClient(f"http://127.0.0.1:{host_port}", SYNTHETIC_TOKEN)
        jwt = _login(api_url, env)
        cases, run_artifacts = _real_cases(
            host=host_client,
            gateway=gateway,
            api_url=api_url,
            mcp_url=mcp_url,
            control_url=control_url,
            control_ca_file=tls.cert,
            jwt=jwt,
            env=env,
            args=args,
            catalog_version=expected.catalog_version,
            expected_catalog=expected,
        )
        artifacts.update(run_artifacts)
        exit_code = 0 if all(case["status"] != "FAIL" for case in cases) else 1
    except BaseException as failure:
        cases.append(
            {
                "id": "E2E-BOOTSTRAP",
                "status": "FAIL",
                "error": f"{type(failure).__name__}: {failure}",
                "traceback": traceback.format_exc(limit=5),
                "at": _timestamp(),
            }
        )
    finally:
        for process in (host, java):
            if process is not None:
                process.stop()
                records.append(process.record())
        local_server.stop()
        records.append(local_server.record())
        if args.cleanup:
            with contextlib.suppress(RuntimeError):
                run_psql_script(args.pg_container, args.pg_database, cleanup_sql())
        if not args.keep_db:
            try:
                run_psql(
                    args.pg_container,
                    "postgres",
                    f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    f"WHERE datname='{args.pg_database}'",
                )
                run_psql(
                    args.pg_container, "postgres", f"DROP DATABASE IF EXISTS {args.pg_database}"
                )
            except RuntimeError:
                pass
    matrix = _aggregate_matrix(cases)
    report = {
        "evidenceLevel": "TESTED(REAL_PROCESS)" if exit_code == 0 else "FAILED(REAL_PROCESS)",
        "mode": "real",
        "generatedAt": _timestamp(),
        "python": sys.version.split()[0],
        "commit": _git_commit(),
        "ports": {
            "gateway": gateway_port,
            "management": args.management_port,
            "host": host_port,
            "mcp": args.mcp_port,
        },
        "processes": records,
        "cases": cases,
        "matrix": matrix,
        "artifacts": artifacts,
        "limits": {
            "databaseDropped": not args.keep_db,
            "tlsMaterialDirectory": str((run_dir / "tls").relative_to(run_dir)),
            "logsDirectory": str((run_dir / "logs").relative_to(run_dir)),
        },
    }
    _real_evidence(report, Path(args.output) / "real-process.json")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return exit_code


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


def _aggregate_matrix(cases: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for number in range(1, 13):
        prefix = f"M-{number:02d}."
        related = [case for case in cases if case["id"].startswith(prefix)]
        if not related:
            status = "NOT_RUN"
        elif any(case["status"] == "FAIL" for case in related):
            status = "FAIL"
        elif all(case["status"] == "PASS" for case in related):
            status = "PASS"
        else:
            status = "NOT_RUN"
        result.append(
            {
                "id": f"M-{number:02d}",
                "status": status,
                "cases": [case["id"] for case in related],
            }
        )
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("offline", "real"), default="offline")
    parser.add_argument("--repo-root", type=Path, default=REPO_ROOT)
    parser.add_argument("--output", type=Path, default=EVIDENCE_ROOT / "lane-c" / "raw")
    parser.add_argument("--work-dir", type=Path, default=REPO_ROOT / "target" / "p2a-e2e")
    parser.add_argument("--env-file", type=Path, default=REPO_ROOT / ".env")
    parser.add_argument(
        "--java-jar", default="ai-collab-backend/target/ai-collab-backend-0.0.1-SNAPSHOT.jar"
    )
    parser.add_argument("--java-command", default="")
    parser.add_argument("--host-command", default="")
    parser.add_argument("--gateway-port", type=int, default=0)
    parser.add_argument("--management-port", type=int, default=0)
    parser.add_argument("--host-port", type=int, default=0)
    parser.add_argument("--mcp-port", type=int, default=0)
    parser.add_argument("--startup-timeout", type=float, default=180.0)
    parser.add_argument("--pg-container", default="ai-collab-postgres")
    parser.add_argument("--pg-user", default="ai_collab")
    parser.add_argument("--pg-database", default="ai_collab_p2a_e2e")
    parser.add_argument("--truststore-password", default="p2a-test-changeit")
    parser.add_argument("--reuse-db", action="store_true")
    parser.add_argument("--keep-db", action="store_true")
    parser.add_argument("--cleanup", action="store_true", default=True)
    args = parser.parse_args()
    if args.mode == "offline":
        args.output = Path(args.output)
        return _run_offline(args)
    args.output = Path(args.output)
    if args.gateway_port == 0:
        args.gateway_port = find_free_port()
    if args.management_port == 0:
        args.management_port = find_free_port()
    if args.host_port == 0:
        args.host_port = find_free_port()
    if args.mcp_port == 0:
        args.mcp_port = find_free_port()
    return _run_real(args)


if __name__ == "__main__":
    raise SystemExit(main())
