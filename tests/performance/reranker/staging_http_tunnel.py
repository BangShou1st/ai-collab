#!/usr/bin/env python3
"""Staging-side HTTP tunnel over the existing SSH stdio channel.

This process runs on the staging host, listens only on the Docker gateway
address, and forwards /rerank request bodies as base64 JSON lines to the local
bridge. It also exposes /health, /stats and /reset for the harness.
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


class Hub:
    def __init__(self) -> None:
        self.condition = threading.Condition()
        self.pending: dict[int, dict[str, Any]] = {}
        self.next_id = 0
        self.requests = 0
        self.lock = threading.Lock()

    def submit(self, body: bytes) -> tuple[int, bytes]:
        with self.lock:
            self.requests += 1
            request_id = self.next_id
            self.next_id += 1
        message = {
            "type": "request",
            "id": request_id,
            "bodyB64": base64.b64encode(body).decode("ascii"),
        }
        print(json.dumps(message), flush=True)
        deadline = time.monotonic() + 120
        with self.condition:
            while request_id not in self.pending:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return 504, b'{"error":"bridge_timeout"}'
                self.condition.wait(remaining)
            response = self.pending.pop(request_id)
        return int(response.get("status", 502)), base64.b64decode(response.get("bodyB64", ""))

    def complete(self, message: dict[str, Any]) -> None:
        request_id = message.get("id")
        if not isinstance(request_id, int):
            return
        with self.condition:
            self.pending[request_id] = message
            self.condition.notify_all()

    def stats(self) -> dict[str, int]:
        with self.lock:
            return {"requests": self.requests, "records": self.requests}

    def reset(self) -> None:
        with self.lock:
            self.requests = 0


def stdin_reader(hub: Hub) -> None:
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(message, dict) and message.get("type") == "response":
            hub.complete(message)


def make_handler(hub: Hub) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_args: Any, **_kwargs: Any) -> None:
            return

        def send_json(self, status: int, payload: dict[str, Any]) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:
            if self.path == "/health":
                self.send_json(200, {"status": "ok"})
                return
            if self.path == "/stats":
                self.send_json(200, hub.stats())
                return
            self.send_json(404, {"error": "not_found"})

        def do_POST(self) -> None:
            if self.path == "/reset":
                hub.reset()
                self.send_json(200, {"status": "ok"})
                return
            if self.path != "/rerank":
                self.send_json(404, {"error": "not_found"})
                return
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length)
            status, response_body = hub.submit(body)
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response_body)))
            self.end_headers()
            self.wfile.write(response_body)

    return Handler


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", required=True)
    parser.add_argument("--port", type=int, default=18081)
    args = parser.parse_args()

    hub = Hub()
    threading.Thread(target=stdin_reader, args=(hub,), daemon=True).start()
    server = ThreadingHTTPServer((args.bind, args.port), make_handler(hub))
    print(
        json.dumps(
            {
                "type": "ready",
                "bind": args.bind,
                "port": args.port,
            }
        ),
        flush=True,
    )
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
