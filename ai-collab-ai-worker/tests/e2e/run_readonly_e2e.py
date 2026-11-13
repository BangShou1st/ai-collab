#!/usr/bin/env python3
"""Run the P1 read-only MCP contract E2E against the stub or a real Gateway."""

from __future__ import annotations

import argparse
import contextlib
import json
import os
import subprocess
import sys
import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any

from mcp_contract import (
    REPO_ROOT,
    JsonRpcClient,
    JsonRpcError,
    run_real_gateway_scenarios,
    run_stub_scenarios,
)


def _commit() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, text=True
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


@contextlib.contextmanager
def _gateway_process(command: str, url: str, token: str | None, timeout: float) -> Iterator[None]:
    process = subprocess.Popen(command, shell=True, cwd=REPO_ROOT)
    deadline = time.monotonic() + timeout
    ready = False
    try:
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"Gateway command exited with {process.returncode}")
            try:
                JsonRpcClient(url, token, timeout=2).call("ping", {"schemaVersion": 1})
                ready = True
                break
            except (JsonRpcError, OSError):
                time.sleep(1)
        if not ready:
            raise RuntimeError("Gateway did not become ready in time")
        yield
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


def _summary(results: list[dict[str, Any]]) -> dict[str, int]:
    return {
        "pass": sum(1 for result in results if result["status"] == "PASS"),
        "skip": sum(1 for result in results if result["status"] == "SKIP"),
        "fail": sum(1 for result in results if result["status"] == "FAIL"),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("stub", "real"), default="stub")
    parser.add_argument("--gateway-url", default=os.environ.get("MCP_GATEWAY_BASE_URL", ""))
    parser.add_argument("--gateway-token", default=os.environ.get("MCP_GATEWAY_TOKEN", ""))
    parser.add_argument("--gateway-command", default=os.environ.get("MCP_GATEWAY_COMMAND", ""))
    parser.add_argument("--gateway-ready-timeout", type=float, default=120.0)
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    if args.mode == "stub":
        results = run_stub_scenarios()
        evidence_level = "TESTED(STUB)"
        gateway = "local-contract-stub"
    else:
        if not args.gateway_url:
            parser.error("--gateway-url is required for --mode real")
        token = args.gateway_token or None
        if args.gateway_command:
            with _gateway_process(
                args.gateway_command, args.gateway_url, token, args.gateway_ready_timeout
            ):
                results = run_real_gateway_scenarios(args.gateway_url, token)
        else:
            results = run_real_gateway_scenarios(args.gateway_url, token)
        evidence_level = "TESTED(REAL_GATEWAY)"
        gateway = "real-gateway"

    summary = _summary(results)
    report = {
        "evidenceLevel": evidence_level,
        "mode": args.mode,
        "gateway": gateway,
        "python": sys.version.split()[0],
        "commit": _commit(),
        "summary": summary,
        "scenarios": results,
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered + "\n", encoding="utf-8")
    return 0 if summary["fail"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
