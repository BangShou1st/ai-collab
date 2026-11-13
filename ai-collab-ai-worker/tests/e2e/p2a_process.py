"""真实进程编排的最小工具集。

只负责启动/记录/回收进程和等待 HTTP 就绪；不包含 MCP 业务断言，避免 E2E 脚本与
夹具逻辑耦合。所有日志写入调用方指定的临时目录。
"""

from __future__ import annotations

import json
import os
import shutil
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

SHANGHAI = timezone(timedelta(hours=8))


def now_shanghai() -> str:
    return datetime.now(SHANGHAI).isoformat(timespec="seconds")


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def wait_port(port: int, timeout: float = 10.0, host: str = "127.0.0.1") -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(0.25)
            if probe.connect_ex((host, port)) == 0:
                return
        time.sleep(0.1)
    raise TimeoutError(f"port {host}:{port} did not open in {timeout}s")


def wait_http(
    url: str,
    timeout: float = 30.0,
    *,
    expected_status: int = 200,
    ca_file: Path | None = None,
) -> None:
    deadline = time.monotonic() + timeout
    context = ssl.create_default_context(cafile=str(ca_file)) if ca_file else None
    last_error = ""
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2, context=context) as response:
                if response.status == expected_status:
                    return
                last_error = f"status={response.status}"
        except urllib.error.HTTPError as failure:
            if failure.code == expected_status:
                return
            last_error = f"status={failure.code}"
        except OSError as failure:
            last_error = f"{type(failure).__name__}: {failure}"
        time.sleep(0.25)
    raise TimeoutError(f"{url} did not become ready in {timeout}s ({last_error})")


def wait_port_closed(port: int, timeout: float = 10.0, host: str = "127.0.0.1") -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(0.2)
            if probe.connect_ex((host, port)) != 0:
                return True
        time.sleep(0.1)
    return False


@dataclass
class ManagedProcess:
    name: str
    command: list[str]
    cwd: Path
    log_dir: Path
    env: dict[str, str] = field(default_factory=dict)
    log_name: str = ""
    started_at: str = ""
    stopped_at: str = ""
    pid: int | None = None
    exit_code: int | None = None
    _log_path: Path | None = None
    _process: subprocess.Popen[bytes] | None = None

    def start(self) -> ManagedProcess:
        self.log_dir.mkdir(parents=True, exist_ok=True)
        self.log_name = self.log_name or f"{self.name}.log"
        self._log_path = self.log_dir / self.log_name
        log_handle = self._log_path.open("ab")
        process_env = os.environ.copy()
        process_env.update(self.env)
        creationflags = 0
        kwargs: dict[str, Any] = {}
        if sys.platform == "win32":
            creationflags = subprocess.CREATE_NEW_PROCESS_GROUP
        else:
            kwargs["start_new_session"] = True
        self._process = subprocess.Popen(
            self.command,
            cwd=str(self.cwd),
            env=process_env,
            stdin=subprocess.DEVNULL,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            creationflags=creationflags,
            **kwargs,
        )
        # Popen 不持有文件句柄生命周期；日志句柄需要在进程退出后关闭。
        self._process._p2a_log_handle = log_handle  # type: ignore[attr-defined]
        self.pid = self._process.pid
        self.started_at = now_shanghai()
        return self

    def poll(self) -> int | None:
        if self._process is None:
            return self.exit_code
        return self._process.poll()

    def stop(self, timeout: float = 15.0) -> None:
        process = self._process
        if process is None:
            return
        if process.poll() is None:
            if sys.platform == "win32":
                subprocess.run(
                    ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                    check=False,
                    capture_output=True,
                    text=True,
                    creationflags=subprocess.CREATE_NO_WINDOW,
                )
            else:
                process.terminate()
            try:
                process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=timeout)
        self.exit_code = process.returncode
        self.stopped_at = now_shanghai()
        log_handle = getattr(process, "_p2a_log_handle", None)
        if log_handle is not None:
            log_handle.close()
        self._process = None

    def record(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "command": self.command,
            "cwd": str(self.cwd),
            "pid": self.pid,
            "startedAt": self.started_at,
            "stoppedAt": self.stopped_at,
            "exitCode": self.exit_code,
            "log": str(self._log_path.relative_to(self.log_dir)) if self._log_path else None,
        }

    def tail(self, lines: int = 40) -> str:
        if self._log_path is None or not self._log_path.exists():
            return ""
        content = self._log_path.read_text(encoding="utf-8", errors="replace").splitlines()
        return "\n".join(content[-lines:])

    def __enter__(self) -> ManagedProcess:
        return self.start()

    def __exit__(self, *_exc: object) -> None:
        self.stop()


def run_psql(
    container: str,
    database: str,
    sql: str,
    *,
    user: str = "ai_collab",
    timeout: float = 60.0,
) -> str:
    docker = shutil.which("docker")
    if docker is None:
        raise RuntimeError("未找到 docker CLI，无法执行测试库 SQL")
    completed = subprocess.run(
        [
            docker,
            "exec",
            "-i",
            container,
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            user,
            "-d",
            database,
            "-tAc",
            sql,
        ],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"psql 执行失败 database={database}: "
            f"{completed.stderr.strip() or completed.stdout.strip()}"
        )
    return completed.stdout.strip()


def run_psql_script(
    container: str,
    database: str,
    sql: str,
    *,
    user: str = "ai_collab",
    timeout: float = 120.0,
) -> str:
    docker = shutil.which("docker")
    if docker is None:
        raise RuntimeError("未找到 docker CLI，无法执行测试库 SQL")
    completed = subprocess.run(
        [
            docker,
            "exec",
            "-i",
            container,
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            user,
            "-d",
            database,
            "-f",
            "-",
        ],
        check=False,
        capture_output=True,
        input=sql,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"psql script 执行失败 database={database}: "
            f"{completed.stderr.strip() or completed.stdout.strip()}"
        )
    return completed.stdout.strip()


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


__all__ = [
    "SHANGHAI",
    "ManagedProcess",
    "find_free_port",
    "now_shanghai",
    "run_psql",
    "run_psql_script",
    "wait_http",
    "wait_port",
    "wait_port_closed",
    "write_json",
]
