#!/usr/bin/env python3
"""M-12 Gateway-off 真实进程证据编排。

真实启动 PostgreSQL/Flyway、Spring Boot 主应用（测试专用入口）、Python Host 和本地
TLS 只读 MCP Server。Java 探针在 ApplicationReadyEvent 后通过生产
McpAgentToolProvider 枚举并执行一次 echo_text；本脚本只负责进程、端口、HTTP/DB
编排和证据落盘，不把配置测试当作真实进程证据。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from p2a_client import http_json
from p2a_process import (
    ManagedProcess,
    find_free_port,
    run_psql,
    wait_http,
    wait_port,
    write_json,
)
from p2a_tls import generate_tls_material

REPO_ROOT = Path(__file__).resolve().parents[3]
BACKEND_ROOT = REPO_ROOT / "ai-collab-backend"
MCP_SERVER = REPO_ROOT / "tests" / "fixtures" / "mcp" / "local_readonly_server.py"
SYNTHETIC_TOKEN = "p2b1-synthetic-internal-token"
SHANGHAI = timezone(timedelta(hours=8))
LOCK_ROOT = Path(r"E:\project\ai-collab-worktrees\.locks")
PROCESS_LOCK = LOCK_ROOT / "p2b1-process"


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


def _drop_database(container: str, database: str) -> None:
    run_psql(
        container,
        "postgres",
        f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='{database}'",
    )
    run_psql(container, "postgres", f"DROP DATABASE IF EXISTS {database}")


def _git_commit() -> str:
    completed = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"],
        cwd=str(REPO_ROOT),
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return completed.stdout.strip() if completed.returncode == 0 else "UNKNOWN"


def _build_classpath(run_dir: Path) -> Path:
    classpath_file = BACKEND_ROOT / "target" / "p2b1-classpath.txt"
    classpath_file.parent.mkdir(parents=True, exist_ok=True)
    mvnw = BACKEND_ROOT / ("mvnw.cmd" if sys.platform == "win32" else "mvnw")
    commands = [
        [str(mvnw), "-B", "-ntp", "-DskipTests", "test-compile"],
        [
            str(mvnw),
            "-B",
            "-ntp",
            "-DincludeScope=test",
            "dependency:build-classpath",
            f"-Dmdep.outputFile={classpath_file.as_posix()}",
        ],
    ]
    log_path = run_dir / "logs" / "maven-build.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as log:
        for command in commands:
            completed = subprocess.run(
                command,
                cwd=str(BACKEND_ROOT),
                stdout=log,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
            if completed.returncode != 0:
                raise RuntimeError(f"Maven 准备失败，详见 {log_path}")
    if not classpath_file.exists():
        raise RuntimeError(f"Maven 未生成 classpath: {classpath_file}")
    return classpath_file


def _classpath_value(classpath_file: Path) -> str:
    dependencies = classpath_file.read_text(encoding="utf-8", errors="replace").strip()
    if not dependencies:
        raise RuntimeError("Maven classpath 为空")
    entries = [
        str(BACKEND_ROOT / "target" / "test-classes"),
        str(BACKEND_ROOT / "target" / "classes"),
        dependencies,
    ]
    return os.pathsep.join(entries)


def _java_command(
    *,
    classpath: str,
    truststore: Path,
    truststore_password: str,
    argfile: Path,
) -> list[str]:
    arguments = [
        f"-Djavax.net.ssl.trustStore={truststore.resolve().as_posix()}",
        f"-Djavax.net.ssl.trustStorePassword={truststore_password}",
        "-Djavax.net.ssl.trustStoreType=PKCS12",
        "-Dfile.encoding=UTF-8",
        "-Dspring.main.banner-mode=off",
        "-Dspring.devtools.restart.enabled=false",
        "-Dspring.devtools.add-properties=false",
        "-cp",
        classpath,
        "com.shitulelv.aicollab.p2b1probe.P2B1GatewayOffMain",
    ]
    argfile.parent.mkdir(parents=True, exist_ok=True)
    lines = []
    for argument in arguments:
        normalized = argument.replace("\\", "/")
        if " " in normalized or "\t" in normalized:
            normalized = '"' + normalized.replace('"', '\\"') + '"'
        lines.append(normalized)
    argfile.write_text(
        "\n".join(lines) + "\n",
        encoding="utf-8",
    )
    return ["java", f"@{argfile.resolve().as_posix()}"]


def _java_env(
    *,
    env: dict[str, str],
    env_file: Path,
    gateway_port: int,
    management_port: int,
    mcp_url: str,
    probe_file: Path,
    database: str,
) -> dict[str, str]:
    values = {
        "SPRING_PROFILES_ACTIVE": "local,p2b1-probe",
        "SPRING_CONFIG_IMPORT": f"optional:file:{env_file.resolve().as_posix()}[.properties]",
        "SPRING_DATASOURCE_URL": (
            f"jdbc:postgresql://127.0.0.1:{env.get('AI_COLLAB_PG_HOST_PORT', '5433')}/{database}"
        ),
        "SPRING_DATASOURCE_USERNAME": env.get("POSTGRES_USER", "ai_collab"),
        "SPRING_DATASOURCE_PASSWORD": env.get("POSTGRES_PASSWORD", ""),
        "SERVER_PORT": str(gateway_port),
        "MANAGEMENT_SERVER_PORT": str(management_port),
        "JWT_SECRET": env.get("JWT_SECRET", ""),
        "MODEL_CONFIG_MASTER_KEY": env.get("MODEL_CONFIG_MASTER_KEY", ""),
        "DEMO_OWNER_USERNAME": env.get("DEMO_OWNER_USERNAME", "owner"),
        "DEMO_OWNER_PASSWORD": env.get("DEMO_OWNER_PASSWORD", ""),
        "MAIL_ENABLED": "false",
        "AUTH_PUBLIC_REGISTRATION_ENABLED": "false",
        "MQ_ENABLED": "false",
        "AGENT_MCP_ALLOWED_HOSTS": "127.0.0.1",
        "AGENT_MCP_TEST_LOOPBACK_ENABLED": "true",
        "AGENT_MCP_GATEWAY_ENABLED": "false",
        "RERANK_MODE": "java",
        "RERANK_SERVICE_TOKEN": SYNTHETIC_TOKEN,
        "INTERNAL_AI_TOKEN_MODE": "static",
        "AI_WORKER_SERVICE_TOKEN": SYNTHETIC_TOKEN,
        "MCP_HOST_GATEWAY_URL": f"http://127.0.0.1:{gateway_port}",
        "P2B1_PROBE_FILE": str(probe_file.resolve()),
        "P2B1_MCP_URL": mcp_url,
        "TZ": "Asia/Shanghai",
    }
    return {key: value for key, value in values.items() if value != ""}


def _java_cwd(configured: str, env_file: Path) -> Path:
    if configured:
        return Path(configured).resolve()
    sibling = env_file.resolve().parent / "ai-collab-backend"
    return sibling if sibling.is_dir() else BACKEND_ROOT


def _host_command(port: int) -> list[str]:
    uv = shutil.which("uv")
    if uv is None:
        raise RuntimeError("未找到 uv")
    return [
        uv,
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


def _host_error_kind(result: Any) -> str:
    body = result.body if isinstance(result.body, dict) else {}
    error = body.get("error")
    if isinstance(error, dict) and isinstance(error.get("kind"), str):
        return str(error["kind"])
    return f"HTTP_{result.status}"


def _server_pid(log_path: Path) -> int | None:
    if not log_path.exists():
        return None
    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = re.search(r"Started server process \[(\d+)\]", line)
        if match:
            return int(match.group(1))
    return None


def _wait_probe(path: Path, process: ManagedProcess, timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(payload, dict):
                    return payload
            except (OSError, json.JSONDecodeError):
                pass
        if process.poll() is not None:
            raise RuntimeError(
                f"Java 进程提前退出 exit={process.poll()}，日志尾部:\n{process.tail(80)}"
            )
        time.sleep(0.25)
    raise TimeoutError(f"等待 M-12 探针超时: {path}")


def _wait_port_checked(
    port: int,
    process: ManagedProcess,
    timeout: float,
    host: str = "127.0.0.1",
) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(
                f"{process.name} 提前退出 exit={process.poll()}，日志尾部:\n{process.tail(80)}"
            )
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(0.25)
            if probe.connect_ex((host, port)) == 0:
                return
        time.sleep(0.1)
    raise TimeoutError(f"port {host}:{port} did not open in {timeout}s")


def _acquire_process_lock(timeout: float = 1200.0) -> None:
    LOCK_ROOT.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            PROCESS_LOCK.mkdir()
            return
        except FileExistsError:
            time.sleep(1)
    raise TimeoutError(f"等待真实进程资源锁超时: {PROCESS_LOCK}")


def _release_process_lock() -> None:
    try:
        PROCESS_LOCK.rmdir()
    except FileNotFoundError:
        return


def run(args: argparse.Namespace) -> int:
    run_dir = (
        Path(args.work_dir) / f"gateway-off-{datetime.now(SHANGHAI).strftime('%Y%m%d-%H%M%S')}"
    )
    run_dir.mkdir(parents=True, exist_ok=True)
    env = _load_env(args.env_file)
    if not env:
        raise RuntimeError(f"环境文件为空或不存在: {args.env_file}")
    _acquire_process_lock()
    processes: list[ManagedProcess] = []
    checks: dict[str, dict[str, Any]] = {}
    database_dropped = False
    result: dict[str, Any] = {
        "schemaVersion": 1,
        "evidenceLevel": "REAL_PROCESS",
        "status": "FAIL",
        "mode": "gateway-off",
        "generatedAt": _timestamp(),
        "commit": _git_commit(),
        "processes": [],
        "ports": {},
        "checks": checks,
        "probe": None,
        "databaseDropped": False,
    }
    try:
        _create_database(args.pg_container, args.pg_database, reset=True)
        tls = generate_tls_material(run_dir / "tls")
        gateway_port = args.gateway_port or find_free_port()
        management_port = args.management_port or find_free_port()
        host_port = args.host_port or find_free_port()
        mcp_port = args.mcp_port or find_free_port()
        result["ports"] = {
            "gateway": gateway_port,
            "management": management_port,
            "host": host_port,
            "mcp": mcp_port,
        }
        mcp_url = f"https://127.0.0.1:{mcp_port}/mcp"
        probe_file = run_dir / "m12-probe.json"
        log_dir = run_dir / "logs"

        mcp = ManagedProcess(
            "local-mcp-tls",
            [
                sys.executable,
                str(MCP_SERVER),
                "--host",
                "127.0.0.1",
                "--port",
                str(mcp_port),
                "--tls-cert",
                str(tls.cert),
                "--tls-key",
                str(tls.key),
            ],
            REPO_ROOT,
            log_dir,
        ).start()
        processes.append(mcp)
        wait_port(mcp_port, timeout=15)

        classpath_file = _build_classpath(run_dir)
        classpath = _classpath_value(classpath_file)
        java = ManagedProcess(
            "java-gateway-off",
            _java_command(
                classpath=classpath,
                truststore=tls.truststore,
                truststore_password=tls.truststore_password,
                argfile=run_dir / "java.args",
            ),
            _java_cwd(args.java_cwd, args.env_file),
            log_dir,
            env=_java_env(
                env=env,
                env_file=args.env_file,
                gateway_port=gateway_port,
                management_port=management_port,
                mcp_url=mcp_url,
                probe_file=probe_file,
                database=args.pg_database,
            ),
        ).start()
        processes.append(java)
        _wait_port_checked(gateway_port, java, args.startup_timeout)
        wait_http(
            f"http://127.0.0.1:{management_port}/actuator/health", timeout=args.startup_timeout
        )
        probe = _wait_probe(probe_file, java, args.startup_timeout)
        result["probe"] = probe

        endpoint = http_json(
            f"http://127.0.0.1:{gateway_port}/internal/ai/v1/mcp",
            payload={
                "jsonrpc": "2.0",
                "id": "p2b1-gateway-off",
                "method": "ping",
                "params": {"schemaVersion": 1},
            },
            token=SYNTHETIC_TOKEN,
            timeout=10,
        )
        checks["gatewayEndpointAbsent"] = {
            "status": "PASS" if endpoint.status == 404 else "FAIL",
            "httpStatus": endpoint.status,
            "body": endpoint.body if isinstance(endpoint.body, dict) else endpoint.text[:500],
        }
        checks["gatewayPropertyFalse"] = {
            "status": "PASS" if probe.get("gatewayEnabled") is False else "FAIL",
            "value": probe.get("gatewayEnabled"),
        }
        gateway_beans = (
            probe.get("gatewayBeans") if isinstance(probe.get("gatewayBeans"), dict) else {}
        )
        checks["gatewayBeansAbsent"] = {
            "status": "PASS"
            if not any(bool(value) for value in gateway_beans.values())
            else "FAIL",
            "beans": gateway_beans,
        }
        legacy = (
            probe.get("legacyProvider") if isinstance(probe.get("legacyProvider"), dict) else {}
        )
        execution = probe.get("execution") if isinstance(probe.get("execution"), dict) else {}
        checks["legacyProviderBeanPresent"] = {
            "status": "PASS" if legacy.get("beanPresent") is True else "FAIL",
            "beanPresent": legacy.get("beanPresent"),
        }
        checks["legacyProviderTools"] = {
            "status": "PASS"
            if "mcp.local_readonly.echo_text" in (legacy.get("toolNames") or [])
            else "FAIL",
            "toolCount": legacy.get("toolCount"),
            "toolNames": legacy.get("toolNames"),
        }
        checks["legacyProviderExecution"] = {
            "status": "PASS" if execution.get("status") == "SUCCEEDED" else "FAIL",
            "execution": execution,
        }

        host = ManagedProcess(
            "python-host",
            _host_command(host_port),
            REPO_ROOT,
            log_dir,
            env={"MCP_HOST_GATEWAY_URL": f"http://127.0.0.1:{gateway_port}"},
        ).start()
        processes.append(host)
        _wait_port_checked(host_port, host, args.startup_timeout)
        wait_http(f"http://127.0.0.1:{host_port}/healthz", timeout=args.startup_timeout)
        python_server_pid = _server_pid(log_dir / "python-host.log")
        host_result = http_json(
            f"http://127.0.0.1:{host_port}/v1/mcp/list",
            payload={"requestId": "p2b1-gateway-off-host-list"},
            token=SYNTHETIC_TOKEN,
            timeout=15,
        )
        host_kind = _host_error_kind(host_result)
        checks["pythonHostStarted"] = {"status": "PASS"}
        checks["pythonHostGatewayRejected"] = {
            "status": "PASS" if host_result.status == 502 and host_kind == "INTERNAL" else "FAIL",
            "httpStatus": host_result.status,
            "kind": host_kind,
        }
        checks["noGatewayExternalMcpCall"] = {
            "status": "PASS"
            if checks["gatewayEndpointAbsent"]["status"] == "PASS"
            and checks["gatewayBeansAbsent"]["status"] == "PASS"
            and checks["pythonHostGatewayRejected"]["status"] == "PASS"
            else "FAIL",
            "basis": [
                "Gateway controller endpoint returned 404",
                "Gateway controller/service/catalog beans were absent",
                "Python Host received a rejected upstream response",
            ],
        }

        passed = all(item.get("status") == "PASS" for item in checks.values())
        result["status"] = "PASS" if passed else "FAIL"
    finally:
        for process in reversed(processes):
            process.stop()
        result["processes"] = [process.record() for process in processes]
        for record in result["processes"]:
            if record["name"] == "python-host":
                record["actualPid"] = python_server_pid if "python_server_pid" in locals() else None
        try:
            _drop_database(args.pg_container, args.pg_database)
            database_dropped = True
        except Exception as failure:
            result["databaseDropError"] = f"{type(failure).__name__}: {failure}"
        result["databaseDropped"] = database_dropped
        result["finishedAt"] = _timestamp()
        _release_process_lock()
        output = Path(args.output) if args.output else run_dir / "m12-real-process.json"
        write_json(output, result)
        print(json.dumps({"status": result["status"], "output": str(output)}, ensure_ascii=False))
    return 0 if result["status"] == "PASS" else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=REPO_ROOT / ".env")
    parser.add_argument("--pg-container", default="ai-collab-postgres")
    parser.add_argument("--pg-database", default="ai_collab_p2b1_gateway_off")
    parser.add_argument("--gateway-port", type=int, default=0)
    parser.add_argument("--management-port", type=int, default=0)
    parser.add_argument("--host-port", type=int, default=0)
    parser.add_argument("--mcp-port", type=int, default=0)
    parser.add_argument("--startup-timeout", type=float, default=180.0)
    parser.add_argument("--java-cwd", default="")
    parser.add_argument("--work-dir", default="target/p2b1-gateway-off")
    parser.add_argument("--output", default="")
    args = parser.parse_args()
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
