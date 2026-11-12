#!/usr/bin/env python3
"""S3.3 memory：真实模型（经 Java 网关，purpose=AGENT）+ 冻结解析器。

- 每个 case 用 ``build_memory_prompt`` 渲染 Prompt（max_candidates=50）；
- ``JavaGatewayClient.call_model`` 单次调用，deadline 用 ``W3S3_GATEWAY_DEADLINE_MS``；
- 返回 content 原样作为 ``MemoryCase.model_output``，再交给
  ``ai_collab_worker.eval.memory.evaluate`` / ``report_document`` / ``seal_report``
  （内部调用线上同一段 ``cognition.memory.parser.parse_candidates``）；
- 429 记录 + sleep 60 + 最多 2 次重试；其它失败 content="" 并记错误码；
- ``/v1/jobs`` 端点在 static 令牌模式下 fail-closed 503，本脚本不调用，报告标 NOT_VERIFIED。
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import time
from pathlib import Path

from ai_collab_worker.clients.java_gateway import (
    PURPOSE_AGENT,
    JavaGatewayClient,
    ModelCallResult,
)
from ai_collab_worker.cognition.memory.prompt import build_memory_prompt
from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.eval.memory import (
    MemoryCase,
    canonical_report_bytes,
    evaluate,
    load_memory_set,
    report_document,
)
from ai_collab_worker.prompts.store import PromptStore

FIXTURE = Path("/repo/ai-collab-ai-worker/tests/fixtures/memory_set/memory_baseline.v1.jsonl")
OUT = Path("/out")
RAW = OUT / "raw"
RAW.mkdir(parents=True, exist_ok=True)

MIN_INTERVAL_SECONDS = 5.0
MAX_429_RETRIES = 2
RATE_LIMIT_SLEEP_SECONDS = 60.0
DEADLINE_MS = int(os.environ.get("W3S3_GATEWAY_DEADLINE_MS", "30000"))
PROVIDER_MAX_OUTPUT_TOKENS = 8192
MAX_CANDIDATES = 50
GATEWAY_URL = "http://app:8080"
TOKEN_ENV = "AI_WORKER_SERVICE_TOKEN"


class Pacer:
    def __init__(self, interval: float) -> None:
        self.interval = interval
        self._next = 0.0

    def before(self) -> None:
        delay = self._next - time.monotonic()
        if delay > 0:
            time.sleep(delay)

    def after(self) -> None:
        self._next = time.monotonic() + self.interval


def usage_dict(result: ModelCallResult) -> dict[str, object] | None:
    usage = result.usage
    if usage is None:
        return None
    return {
        "inputTokens": usage.input_tokens,
        "outputTokens": usage.output_tokens,
        "source": usage.source.value,
    }


async def call_one(
    client: JavaGatewayClient,
    store: PromptStore,
    pacer: Pacer,
    case: MemoryCase,
) -> tuple[MemoryCase, dict[str, object]]:
    prompt = build_memory_prompt(store, case.snapshot, max_candidates=MAX_CANDIDATES)
    record: dict[str, object] = {
        "id": case.id,
        "promptVersion": prompt.version,
        "promptBytes": len(prompt.system.encode("utf-8")) + len(prompt.user.encode("utf-8")),
        "deadlineMs": DEADLINE_MS,
        "attempts": [],
        "rateLimited": 0,
        "status": "error",
        "errorCode": None,
        "content": "",
        "contentChars": 0,
        "latencyMs": None,
        "modelAlias": None,
        "usage": None,
        "elapsedMs": 0,
    }
    content = ""
    started = time.time()

    for attempt_no in range(1, MAX_429_RETRIES + 2):
        pacer.before()
        t0 = time.monotonic()
        try:
            result = await client.call_model(
                system_prompt=prompt.system,
                user_prompt=prompt.user,
                deadline_ms=DEADLINE_MS,
                call_id=f"memory:{case.id}",
                purpose=PURPOSE_AGENT,
            )
            pacer.after()
            content = result.content
            record.update(
                status="ok",
                content=content,
                contentChars=len(content),
                latencyMs=result.latency_ms,
                modelAlias=result.model_alias,
                usage=usage_dict(result),
            )
            record["attempts"].append(  # type: ignore[union-attr]
                {
                    "attempt": attempt_no,
                    "result": "ok",
                    "elapsedMs": int((time.monotonic() - t0) * 1000),
                }
            )
            break
        except AppError as exc:
            pacer.after()
            rate_limited = exc.code == ErrorCode.MODEL_RATE_LIMITED or exc.status_code == 429
            if rate_limited:
                record["rateLimited"] = int(record["rateLimited"]) + 1  # type: ignore[arg-type]
            record["attempts"].append(  # type: ignore[union-attr]
                {
                    "attempt": attempt_no,
                    "result": "error",
                    "code": exc.code.value,
                    "httpStatus": exc.status_code,
                    "message": exc.message,
                    "details": exc.details,
                    "rateLimited": rate_limited,
                    "elapsedMs": int((time.monotonic() - t0) * 1000),
                }
            )
            if rate_limited and attempt_no <= MAX_429_RETRIES:
                time.sleep(RATE_LIMIT_SLEEP_SECONDS)
                continue
            record["errorCode"] = exc.code.value
            break
        except Exception as exc:  # noqa: BLE001
            pacer.after()
            record["attempts"].append(  # type: ignore[union-attr]
                {
                    "attempt": attempt_no,
                    "result": "error",
                    "code": "UNEXPECTED",
                    "errorType": type(exc).__name__,
                    "elapsedMs": int((time.monotonic() - t0) * 1000),
                }
            )
            record["errorCode"] = f"UNEXPECTED:{type(exc).__name__}"
            break

    record["elapsedMs"] = int((time.time() - started) * 1000)
    new_case = MemoryCase(
        id=case.id,
        snapshot=case.snapshot,
        judgement=case.judgement,
        model_output=content,
    )
    return new_case, record


async def run() -> int:
    token = os.environ.get(TOKEN_ENV, "").strip()
    if not token:
        print(f"FATAL: {TOKEN_ENV} missing", file=sys.stderr)
        return 2

    cases = load_memory_set(FIXTURE)
    print(f"memory cases={len(cases)} deadlineMs={DEADLINE_MS}")
    store = PromptStore(Path("/app/prompts"))
    pacer = Pacer(MIN_INTERVAL_SECONDS)
    client = JavaGatewayClient(base_url=GATEWAY_URL, service_token=token)
    started = time.time()
    new_cases: list[MemoryCase] = []
    records: list[dict[str, object]] = []
    try:
        for case in cases:
            new_case, record = await call_one(client, store, pacer, case)
            new_cases.append(new_case)
            records.append(record)
            print(
                f"case={case.id} status={record['status']} error={record['errorCode']} "
                f"chars={record['contentChars']} elapsedMs={record['elapsedMs']}"
            )
    finally:
        await client.aclose()

    report = evaluate(new_cases)
    document = report_document(report)
    payload = canonical_report_bytes(document)
    (OUT / "aggregate-memory.json").write_bytes(payload)

    outcomes = {case.id: outcome for case, outcome in report.cases}
    truncation_suspects: list[str] = []
    for record in records:
        outcome = outcomes[str(record["id"])]
        usage = record.get("usage") or {}
        output_tokens = usage.get("outputTokens") if isinstance(usage, dict) else None
        record["eval"] = {
            "schemaPass": outcome.schema_pass,
            "reason": outcome.reason,
            "candidates": outcome.candidates,
            "matched": outcome.matched,
            "duplicates": outcome.duplicates,
            "invalidSource": outcome.invalid_source,
            "invalidDedupe": outcome.invalid_dedupe,
        }
        suspected = (
            isinstance(output_tokens, int) and output_tokens >= PROVIDER_MAX_OUTPUT_TOKENS
        ) or (not outcome.schema_pass and outcome.reason is not None and "JSON" in outcome.reason)
        record["truncationSuspected"] = suspected
        if suspected:
            truncation_suspects.append(str(record["id"]))

    with (RAW / "memory-cases.jsonl").open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")

    sealed = json.loads(payload)
    summary = {
        "sha256": sealed["sha256"],
        "elapsedSeconds": round(time.time() - started, 1),
        "deadlineMs": DEADLINE_MS,
        "rateLimitedTotal": sum(int(r["rateLimited"]) for r in records),
        "failedCases": [
            {"id": r["id"], "errorCode": r["errorCode"]} for r in records if r["status"] != "ok"
        ],
        "truncationSuspects": truncation_suspects,
        "aggregate": sealed["aggregate"],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(run()))
