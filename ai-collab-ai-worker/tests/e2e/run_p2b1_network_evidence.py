#!/usr/bin/env python3
"""P2-B1 M-01/M-02 real-process network evidence orchestration.

The Java harness is deliberately a separate JVM. The Python side only prepares
test TLS/listener fixtures and records process metadata; the production MCP
policy and HTTP client remain the code under test.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import time
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from p2a_process import ManagedProcess, write_json
from p2a_tls import TRUSTSTORE_PASSWORD, generate_tls_material

REPO_ROOT = Path(__file__).resolve().parents[3]
BACKEND_DIR = REPO_ROOT / "ai-collab-backend"
SERVER_PATH = REPO_ROOT / "tests" / "fixtures" / "mcp" / "local_readonly_server.py"
SENTINEL_PATH = REPO_ROOT / "tests" / "fixtures" / "mcp" / "p2b1_tcp_sentinel.py"
JAVA_MAIN = "com.shitulelv.aicollab.agent.infrastructure.mcp.P2B1NetworkEvidenceMain"
SHANGHAI = timezone(timedelta(hours=8))
DEFAULT_LOCK = Path(
    os.environ.get(
        "P2B1_PROCESS_LOCK",
        r"E:\project\ai-collab-worktrees\.locks\p2b1-process",
    )
)


def timestamp() -> str:
    return datetime.now(SHANGHAI).isoformat(timespec="seconds")


def _find_port_on(host: str) -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind((host, 0))
        return int(probe.getsockname()[1])


def _shared_port() -> int:
    for _ in range(30):
        port = _find_port_on("127.0.0.1")
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            try:
                probe.bind(("127.0.0.2", port))
            except OSError:
                continue
            return port
    raise RuntimeError("无法在 127.0.0.1 与 127.0.0.2 找到共享测试端口")


def _private_ipv4() -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.connect(("8.8.8.8", 53))
        address = str(probe.getsockname()[0])
    parts = [int(item) for item in address.split(".")]
    private = (
        parts[0] == 10
        or (parts[0] == 172 and 16 <= parts[1] <= 31)
        or (parts[0] == 192 and parts[1] == 168)
    )
    if not private:
        raise RuntimeError(f"未找到可用于 fixture 的私网 IPv4 地址: {address}")
    return address


def _wait_file(path: Path, timeout: float = 15.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            return
        time.sleep(0.05)
    raise TimeoutError(f"fixture count file 未生成: {path}")


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


@contextmanager
def process_lock(path: Path, timeout: float = 1200.0) -> Iterator[None]:
    path.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + timeout
    while True:
        try:
            path.mkdir()
            break
        except FileExistsError:
            if time.monotonic() >= deadline:
                raise TimeoutError(f"等待 p2b1-process 锁超时: {path}") from None
            time.sleep(0.5)
    try:
        yield
    finally:
        shutil.rmtree(path, ignore_errors=True)


def _maven_command(*arguments: str) -> list[str]:
    if os.name == "nt":
        return ["cmd.exe", "/c", str(BACKEND_DIR / "mvnw.cmd"), *arguments]
    return [str(BACKEND_DIR / "mvnw"), *arguments]


def _classpath() -> str:
    subprocess.run(
        _maven_command("-B", "-ntp", "-DskipTests", "test-compile"),
        cwd=str(BACKEND_DIR),
        check=True,
    )
    classpath_file = BACKEND_DIR / "target" / "p2b1-test-classpath.txt"
    subprocess.run(
        _maven_command(
            "-B",
            "-ntp",
            "-DincludeScope=test",
            "dependency:build-classpath",
            "-Dmdep.outputFile=target/p2b1-test-classpath.txt",
        ),
        cwd=str(BACKEND_DIR),
        check=True,
    )
    entries = [
        str(BACKEND_DIR / "target" / "classes"),
        str(BACKEND_DIR / "target" / "test-classes"),
        classpath_file.read_text(encoding="utf-8").strip(),
    ]
    return os.pathsep.join(entries)


def _fixture_command(
    *,
    host: str,
    port: int,
    count_file: Path,
    tls_cert: Path | None = None,
    tls_key: Path | None = None,
    server_version: str = "1.0.0",
) -> list[str]:
    command = [
        sys.executable,
        str(SERVER_PATH),
        "--host",
        host,
        "--port",
        str(port),
        "--count-file",
        str(count_file),
        "--server-version",
        server_version,
    ]
    if tls_cert is not None and tls_key is not None:
        command.extend(["--tls-cert", str(tls_cert), "--tls-key", str(tls_key)])
    return command


def _sentinel_command(*, host: str, port: int, count_file: Path) -> list[str]:
    return [
        sys.executable,
        str(SENTINEL_PATH),
        "--host",
        host,
        "--port",
        str(port),
        "--count-file",
        str(count_file),
    ]


def _process_record(process: ManagedProcess) -> dict[str, Any]:
    record = process.record()
    args_file = getattr(process, "_p2b1_args_file", None)
    if args_file is not None:
        record["argsFile"] = str(args_file)
    return record


def _run_java(
    *,
    mode: str,
    output: Path,
    classpath: str,
    run_dir: Path,
    args: list[str],
    truststore: Path,
    extra_jvm: list[str],
) -> ManagedProcess:
    argfile = run_dir / f"java-{mode}.args"
    effective = [
        *extra_jvm,
        "-cp",
        classpath,
        JAVA_MAIN,
        mode,
        "--output",
        str(output),
        "--truststore",
        str(truststore),
        *args,
    ]
    argfile.write_text(
        "\n".join(
            '"' + argument.replace("\\", "/").replace('"', '\\"') + '"' for argument in effective
        )
        + "\n",
        encoding="utf-8",
    )
    command = [shutil.which("java") or "java", f"@{argfile.as_posix()}"]
    process = ManagedProcess(
        f"p2b1-{mode}-java",
        command,
        BACKEND_DIR,
        run_dir / "logs",
        env={"P2B1_TRUSTSTORE_PASSWORD": TRUSTSTORE_PASSWORD},
    ).start()
    process._p2b1_args_file = argfile  # type: ignore[attr-defined]
    process._process.wait(timeout=120)  # type: ignore[union-attr]
    process.stop()
    return process


def _m01(
    *,
    output: Path,
    run_dir: Path,
    classpath: str,
    tls_cert: Path,
    tls_key: Path,
    truststore: Path,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    private_ip = _private_ipv4()
    control_port = _find_port_on("127.0.0.1")
    private_port = _find_port_on(private_ip)
    outside_port = _find_port_on("127.0.0.1")
    counts = run_dir / "counts"
    control_count = counts / "m01-control.json"
    private_count = counts / "m01-private.json"
    outside_count = counts / "m01-outside.json"
    processes: list[ManagedProcess] = []
    records: list[dict[str, Any]] = []
    try:
        control = ManagedProcess(
            "p2b1-m01-control-tls",
            _fixture_command(
                host="127.0.0.1",
                port=control_port,
                count_file=control_count,
                tls_cert=tls_cert,
                tls_key=tls_key,
                server_version="p2b1-control-v1",
            ),
            REPO_ROOT,
            run_dir / "logs",
        ).start()
        processes.append(control)
        private = ManagedProcess(
            "p2b1-m01-private-sentinel",
            _sentinel_command(
                host=private_ip,
                port=private_port,
                count_file=private_count,
            ),
            REPO_ROOT,
            run_dir / "logs",
        ).start()
        processes.append(private)
        outside = ManagedProcess(
            "p2b1-m01-outside-sentinel",
            _sentinel_command(
                host="127.0.0.1",
                port=outside_port,
                count_file=outside_count,
            ),
            REPO_ROOT,
            run_dir / "logs",
        ).start()
        processes.append(outside)
        _wait_file(control_count)
        _wait_file(private_count)
        _wait_file(outside_count)

        java = _run_java(
            mode="m01",
            output=output,
            classpath=classpath,
            run_dir=run_dir,
            truststore=truststore,
            extra_jvm=["-Djava.security.manager=allow"],
            args=[
                "--control-port",
                str(control_port),
                "--control-count-file",
                str(control_count),
                "--control-version",
                "p2b1-control-v1",
                "--private-address",
                private_ip,
                "--private-port",
                str(private_port),
                "--private-count-file",
                str(private_count),
                "--outside-port",
                str(outside_port),
                "--outside-count-file",
                str(outside_count),
            ],
        )
        processes.append(java)
        raw = _read_json(output)
        for process in reversed(processes):
            process.stop()
        records.extend(_process_record(item) for item in processes)
        raw["fixtures"] = {
            "privateAddress": private_ip,
            "privatePort": private_port,
            "outsideAddress": "127.0.0.1",
            "outsidePort": outside_port,
            "controlPort": control_port,
        }
        raw["counts"] = {
            "control": _read_json(control_count),
            "private": _read_json(private_count),
            "outside": _read_json(outside_count),
        }
        raw["processes"] = records
        return raw, records
    finally:
        for process in reversed(processes):
            process.stop()


def _m02(
    *,
    output: Path,
    run_dir: Path,
    classpath: str,
    tls_cert: Path,
    tls_key: Path,
    truststore: Path,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    port = _shared_port()
    counts = run_dir / "counts"
    good_count = counts / "m02-good.json"
    evil_count = counts / "m02-evil.json"
    processes: list[ManagedProcess] = []
    records: list[dict[str, Any]] = []
    try:
        good = ManagedProcess(
            "p2b1-m02-good-tls",
            _fixture_command(
                host="127.0.0.1",
                port=port,
                count_file=good_count,
                tls_cert=tls_cert,
                tls_key=tls_key,
                server_version="p2b1-good-v1",
            ),
            REPO_ROOT,
            run_dir / "logs",
        ).start()
        processes.append(good)
        evil = ManagedProcess(
            "p2b1-m02-evil-tls",
            _fixture_command(
                host="127.0.0.2",
                port=port,
                count_file=evil_count,
                tls_cert=tls_cert,
                tls_key=tls_key,
                server_version="p2b1-evil-v1",
            ),
            REPO_ROOT,
            run_dir / "logs",
        ).start()
        processes.append(evil)
        _wait_file(good_count)
        _wait_file(evil_count)

        overlay = BACKEND_DIR / "target" / "test-classes" / "p2b1-resolver-overlay"
        m02_classpath = os.pathsep.join([str(overlay), classpath])
        java = _run_java(
            mode="m02",
            output=output,
            classpath=m02_classpath,
            run_dir=run_dir,
            truststore=truststore,
            extra_jvm=[
                "-Dp2b1.rebind.enabled=true",
                "-Dsun.net.inetaddr.ttl=0",
            ],
            args=[
                "--port",
                str(port),
                "--good-count-file",
                str(good_count),
                "--evil-count-file",
                str(evil_count),
                "--good-version",
                "p2b1-good-v1",
            ],
        )
        processes.append(java)
        raw = _read_json(output)
        for process in reversed(processes):
            process.stop()
        records.extend(_process_record(item) for item in processes)
        raw["fixtures"] = {
            "goodAddress": "127.0.0.1",
            "evilAddress": "127.0.0.2",
            "port": port,
        }
        raw["counts"] = {
            "good": _read_json(good_count),
            "evil": _read_json(evil_count),
        }
        raw["processes"] = records
        return raw, records
    finally:
        for process in reversed(processes):
            process.stop()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, default=Path("target/p2b1-network-evidence"))
    parser.add_argument("--process-lock", type=Path, default=DEFAULT_LOCK)
    parser.add_argument("--lock-timeout", type=float, default=1200)
    args = parser.parse_args()

    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    run_dir = args.work_dir.resolve() / f"run-{datetime.now(SHANGHAI).strftime('%Y%m%d-%H%M%S')}"
    run_dir.mkdir(parents=True, exist_ok=True)
    with process_lock(args.process_lock, args.lock_timeout):
        classpath = _classpath()
        tls = generate_tls_material(run_dir / "tls")
        m01_raw, m01_processes = _m01(
            output=output / "m01-real-process.json",
            run_dir=run_dir,
            classpath=classpath,
            tls_cert=tls.cert,
            tls_key=tls.key,
            truststore=tls.truststore,
        )
        m02_raw, m02_processes = _m02(
            output=output / "m02-real-process.json",
            run_dir=run_dir,
            classpath=classpath,
            tls_cert=tls.cert,
            tls_key=tls.key,
            truststore=tls.truststore,
        )
        summary = {
            "schemaVersion": 1,
            "generatedAt": timestamp(),
            "mode": "REAL_PROCESS",
            "commit": _git_commit(),
            "processes": m01_processes + m02_processes,
            "M-01": m01_raw.get("M-01", {}),
            "M-02": m02_raw.get("M-02", {}),
            "runDir": str(run_dir),
            "tlsMaterial": str(run_dir / "tls"),
        }
        write_json(output / "network-evidence.json", summary)
        write_json(output / "processes.json", m01_processes + m02_processes)
        passed = (
            m01_raw.get("M-01", {}).get("status") == "PASS"
            and m02_raw.get("M-02", {}).get("status") == "PASS"
        )
        print(
            json.dumps(
                {"status": "PASS" if passed else "FAIL", "output": str(output)},
                ensure_ascii=False,
            )
        )
        return 0 if passed else 1


def _git_commit() -> str:
    completed = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"],
        cwd=str(REPO_ROOT),
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return completed.stdout.strip() if completed.returncode == 0 else "unknown"


if __name__ == "__main__":
    raise SystemExit(main())
