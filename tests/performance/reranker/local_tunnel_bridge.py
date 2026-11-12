#!/usr/bin/env python3
"""Local SSH stdio bridge for the staging HTTP tunnel."""

from __future__ import annotations

import argparse
import base64
import http.client
import json
import subprocess
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


class Bridge:
    def __init__(self, upstream_host: str, upstream_port: int, log_path: Path) -> None:
        self.upstream_host = upstream_host
        self.upstream_port = upstream_port
        self.log_path = log_path
        self.calls = 0

    def log(self, record: dict[str, Any]) -> None:
        with self.log_path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
            stream.flush()

    def forward(self, message: dict[str, Any]) -> dict[str, Any]:
        request_id = message.get("id")
        body = base64.b64decode(message.get("bodyB64", ""))
        started = time.perf_counter()
        status = 502
        response_body = b'{"error":"bridge_upstream_error"}'
        try:
            connection = http.client.HTTPConnection(
                self.upstream_host, self.upstream_port, timeout=120
            )
            connection.request(
                "POST",
                "/rerank",
                body=body,
                headers={"Content-Type": "application/json", "Accept": "application/json"},
            )
            response = connection.getresponse()
            status = response.status
            response_body = response.read()
            connection.close()
        except Exception as exc:
            response_body = json.dumps({"error": f"{type(exc).__name__}: {exc}"}).encode("utf-8")
        self.calls += 1
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        self.log(
            {
                "requestNo": self.calls,
                "receivedAt": datetime.now(UTC).isoformat(),
                "contentLength": len(body),
                "status": status,
                "durationMs": round(elapsed_ms, 3),
            }
        )
        return {
            "type": "response",
            "id": request_id,
            "status": status,
            "bodyB64": base64.b64encode(response_body).decode("ascii"),
        }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ssh-config", type=Path, required=True)
    parser.add_argument("--host", default="instabox-box-aae54f")
    parser.add_argument("--remote-script", default="/data/home/w3b-http-tunnel.py")
    parser.add_argument("--bind", required=True)
    parser.add_argument("--port", type=int, default=18081)
    parser.add_argument("--upstream-host", default="127.0.0.1")
    parser.add_argument("--upstream-port", type=int, default=18090)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--ssh-stderr", type=Path, required=True)
    args = parser.parse_args()

    args.log.parent.mkdir(parents=True, exist_ok=True)
    args.log.write_text("", encoding="utf-8")
    args.ssh_stderr.parent.mkdir(parents=True, exist_ok=True)
    stderr_stream = args.ssh_stderr.open("ab")
    command = f"python3 {args.remote_script} --bind {args.bind} --port {args.port}"
    child = subprocess.Popen(
        [
            "ssh",
            "-T",
            "-F",
            str(args.ssh_config),
            "-o",
            "ServerAliveInterval=30",
            "-o",
            "ServerAliveCountMax=3",
            args.host,
            command,
        ],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=stderr_stream,
        text=True,
        bufsize=1,
    )
    assert child.stdin is not None
    assert child.stdout is not None
    bridge = Bridge(args.upstream_host, args.upstream_port, args.log)
    print(
        json.dumps(
            {
                "status": "bridge_started",
                "host": args.host,
                "bind": args.bind,
                "port": args.port,
                "upstream": f"{args.upstream_host}:{args.upstream_port}",
            }
        ),
        flush=True,
    )
    try:
        for line in child.stdout:
            if not line.strip():
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                print(f"non-JSON tunnel output: {line.rstrip()}", file=sys.stderr, flush=True)
                continue
            if message.get("type") != "request":
                continue
            response = bridge.forward(message)
            child.stdin.write(json.dumps(response) + "\n")
            child.stdin.flush()
    finally:
        child.terminate()
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            child.kill()
        stderr_stream.close()
    return child.returncode or 0


if __name__ == "__main__":
    raise SystemExit(main())
