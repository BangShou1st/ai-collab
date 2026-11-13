"""Local-only webhook receiver for the P2-C Alertmanager pipeline probe."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

OUTPUT = Path("/evidence/received.jsonl")


class Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/alerts":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            self.send_error(400)
            return
        record = {
            "receivedAt": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            "path": self.path,
            "contentType": self.headers.get("Content-Type", ""),
            "alertCount": len(payload.get("alerts", [])),
            "alerts": [
                {
                    "status": alert.get("status"),
                    "labels": alert.get("labels", {}),
                    "annotations": alert.get("annotations", {}),
                    "startsAt": alert.get("startsAt"),
                    "endsAt": alert.get("endsAt"),
                }
                for alert in payload.get("alerts", [])
            ],
        }
        with OUTPUT.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    def log_message(self, fmt: str, *args: object) -> None:
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 54321), Handler).serve_forever()
