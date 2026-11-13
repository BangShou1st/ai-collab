"""Targeted checks for the P2-B1 network evidence fixtures."""

from __future__ import annotations

import json
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from p2a_process import find_free_port

REPO_ROOT = Path(__file__).resolve().parents[3]
SERVER_PATH = REPO_ROOT / "tests" / "fixtures" / "mcp" / "local_readonly_server.py"
SENTINEL_PATH = REPO_ROOT / "tests" / "fixtures" / "mcp" / "p2b1_tcp_sentinel.py"


def _wait_for_count(path: Path, minimum: int, timeout: float = 10.0) -> int:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            count = int(json.loads(path.read_text(encoding="utf-8"))["acceptedConnections"])
            if count >= minimum:
                return count
        time.sleep(0.05)
    raise AssertionError(f"count file did not reach {minimum}: {path}")


def _stop(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def test_tcp_sentinel_records_real_accept(tmp_path: Path) -> None:
    count_file = tmp_path / "sentinel.json"
    port = find_free_port()
    process = subprocess.Popen(
        [
            sys.executable,
            str(SENTINEL_PATH),
            "--host",
            "127.0.0.1",
            "--port",
            str(port),
            "--count-file",
            str(count_file),
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        assert _wait_for_count(count_file, 0) == 0
        with socket.create_connection(("127.0.0.1", port), timeout=2):
            pass
        assert _wait_for_count(count_file, 1) == 1
    finally:
        _stop(process)


def test_tls_fixture_exposes_version_and_counts_connections(tmp_path: Path) -> None:
    count_file = tmp_path / "server.json"
    port = find_free_port()
    process = subprocess.Popen(
        [
            sys.executable,
            str(SERVER_PATH),
            "--host",
            "127.0.0.1",
            "--port",
            str(port),
            "--server-version",
            "p2b1-test-version",
            "--count-file",
            str(count_file),
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        assert _wait_for_count(count_file, 0) == 0
        request = urllib.request.Request(
            f"http://127.0.0.1:{port}/mcp",
            data=json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": "fixture-check",
                    "method": "initialize",
                    "params": {},
                }
            ).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=5) as response:
            payload = json.loads(response.read().decode("utf-8"))
        assert payload["result"]["serverInfo"]["version"] == "p2b1-test-version"
        assert _wait_for_count(count_file, 1) == 1
    finally:
        _stop(process)
