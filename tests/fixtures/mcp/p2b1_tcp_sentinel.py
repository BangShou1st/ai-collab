#!/usr/bin/env python3
"""Raw TCP sentinel for the P2-B1 M-01 external-negative evidence.

The sentinel only accepts and records connections. It never completes a TLS
handshake, so a successful accept proves that the Java caller attempted a
connection to the protected destination.
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import socket
import sys
from pathlib import Path


def write_count(path: Path, accepted: int, host: str, port: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "pid": os.getpid(),
        "host": host,
        "port": port,
        "acceptedConnections": accepted,
    }
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--count-file", type=Path, required=True)
    args = parser.parse_args()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.host, args.port))
    server.listen(16)
    accepted = 0
    write_count(args.count_file, accepted, args.host, args.port)
    print(
        json.dumps(
            {
                "event": "ready",
                "host": args.host,
                "port": args.port,
                "pid": os.getpid(),
            },
            ensure_ascii=False,
        ),
        flush=True,
    )

    def stop(_signum: int, _frame: object) -> None:
        server.close()
        sys.exit(0)

    signal.signal(signal.SIGTERM, stop)
    while True:
        try:
            connection, _address = server.accept()
        except OSError:
            return 0
        accepted += 1
        write_count(args.count_file, accepted, args.host, args.port)
        connection.close()


if __name__ == "__main__":
    raise SystemExit(main())
