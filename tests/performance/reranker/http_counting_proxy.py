#!/usr/bin/env python3
"""Local HTTP proxy for counting admin-test /rerank calls."""

from __future__ import annotations

import argparse
import http.client
import json
import threading
import time
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


class Counter:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.requests = 0
        self.records = 0

    def increment(self) -> int:
        with self.lock:
            self.requests += 1
            self.records += 1
            return self.requests

    def reset(self) -> None:
        with self.lock:
            self.requests = 0
            self.records = 0

    def snapshot(self) -> dict[str, int]:
        with self.lock:
            return {"requests": self.requests, "records": self.records}


def make_handler(
    upstream: str,
    log_path: Path,
    counter: Counter,
) -> type[BaseHTTPRequestHandler]:
    parsed = urlparse(upstream)
    upstream_host = parsed.hostname or "127.0.0.1"
    upstream_port = parsed.port or 80
    upstream_path = parsed.path.rstrip("/") + "/rerank"

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_args: Any, **_kwargs: Any) -> None:
            return

        def _send_json(self, status: int, payload: dict[str, Any]) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:
            if self.path == "/stats":
                self._send_json(200, counter.snapshot())
                return
            if self.path == "/health":
                self._send_json(200, {"status": "ok"})
                return
            self._send_json(404, {"error": "not_found"})

        def do_POST(self) -> None:
            if self.path == "/reset":
                counter.reset()
                self._send_json(200, {"status": "ok"})
                return
            if self.path != "/rerank":
                self._send_json(404, {"error": "not_found"})
                return
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length)
            candidate_count = None
            try:
                parsed_body = json.loads(body.decode("utf-8"))
                documents = parsed_body.get("documents")
                candidate_count = len(documents) if isinstance(documents, list) else None
            except (UnicodeDecodeError, json.JSONDecodeError):
                parsed_body = None
            request_no = counter.increment()
            started = time.perf_counter()
            status = 0
            response_body = b""
            try:
                connection = http.client.HTTPConnection(upstream_host, upstream_port, timeout=120)
                connection.request(
                    "POST",
                    upstream_path,
                    body=body,
                    headers={
                        "Content-Type": self.headers.get("Content-Type", "application/json"),
                        "Accept": "application/json",
                    },
                )
                response = connection.getresponse()
                status = response.status
                response_body = response.read()
                connection.close()
            except Exception as exc:
                response_body = json.dumps({"error": f"{type(exc).__name__}: {exc}"}).encode(
                    "utf-8"
                )
                status = 502
            elapsed_ms = (time.perf_counter() - started) * 1000.0
            record = {
                "requestNo": request_no,
                "receivedAt": datetime.now(UTC).isoformat(),
                "candidateCount": candidate_count,
                "contentLength": length,
                "status": status,
                "durationMs": round(elapsed_ms, 3),
            }
            with log_path.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(record, ensure_ascii=False) + "\n")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response_body)))
            self.end_headers()
            self.wfile.write(response_body)

    return Handler


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--upstream", default="http://127.0.0.1:18081")
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18090)
    parser.add_argument("--log", type=Path, required=True)
    args = parser.parse_args()

    args.log.parent.mkdir(parents=True, exist_ok=True)
    args.log.write_text("", encoding="utf-8")
    counter = Counter()
    server = ThreadingHTTPServer(
        (args.bind, args.port),
        make_handler(args.upstream, args.log, counter),
    )
    print(
        json.dumps(
            {
                "status": "listening",
                "bind": args.bind,
                "port": args.port,
                "upstream": args.upstream,
                "log": str(args.log),
            }
        ),
        flush=True,
    )
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
