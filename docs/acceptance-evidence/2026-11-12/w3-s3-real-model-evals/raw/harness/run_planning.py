#!/usr/bin/env python3
"""S3.2 planning：真实模型（经 Java 网关，purpose=PLANNING）+ 冻结校验器。

- 每个 case 用 ``build_planning_prompt`` 渲染 Prompt（PromptStore=/app/prompts）；
- ``JavaGatewayClient.call_model`` 单次调用，deadline 用 ``W3S3_GATEWAY_DEADLINE_MS``；
- 返回 content 原样作为 ``PlanningCase.model_output``，再交给
  ``ai_collab_worker.eval.planning.evaluate`` / ``report_document`` / ``seal_report``；
- 429 记录 + sleep 60 + 最多 2 次重试；其它失败 content="" 并记错误码（evaluate 判 schema fail）；
- 额外做一次在线端点冒烟 ``POST /v1/planning/draft``（只证明端点可用，不替代指标）。
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import time
from pathlib import Path

import httpx

from ai_collab_worker.clients.java_gateway import (
    PURPOSE_PLANNING,
    JavaGatewayClient,
    ModelCallResult,
)
from ai_collab_worker.cognition.planning.prompt import build_planning_prompt
from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.eval.planning import (
    PlanningCase,
    canonical_report_bytes,
    evaluate,
    load_planning_set,
    report_document,
)
from ai_collab_worker.prompts.store import PromptStore

FIXTURE = Path("/repo/ai-collab-ai-worker/tests/fixtures/planning_set/planning_baseline.v1.jsonl")
OUT = Path("/out")
RAW = OUT / "raw"
RAW.mkdir(parents=True, exist_ok=True)

MIN_INTERVAL_SECONDS = 5.0
MAX_429_RETRIES = 2
RATE_LIMIT_SLEEP_SECONDS = 60.0
DEADLINE_MS = int(os.environ.get("W3S3_GATEWAY_DEADLINE_MS", "30000"))
PROVIDER_MAX_OUTPUT_TOKENS = 8192
GATEWAY_URL = "http://app:8080"
WORKER_URL = "http://ai-worker-api:9100"
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
    case: PlanningCase,
) -> tuple[PlanningCase, dict[str, object]]:
    prompt = build_planning_prompt(store, case.request)
    record: dict[str, object] = {
        "id": case.id,
        "subset": case.subset,
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
                call_id=f"planning:{case.id}",
                purpose=PURPOSE_PLANNING,
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
        except Exception as exc:  # noqa: BLE001 - 未预期异常也要落证据、不中断整轮
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
    new_case = PlanningCase(
        id=case.id,
        subset=case.subset,
        request=case.request,
        expected_pass=case.expected_pass,
        model_output=content,
    )
    return new_case, record


async def smoke_endpoint(case: PlanningCase, pacer: Pacer, token: str) -> dict[str, object]:
    body = case.request.model_dump(by_alias=True, mode="json")
    body["schemaVersion"] = 1
    payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    url = f"{WORKER_URL}/v1/planning/draft"
    pacer.before()
    started = time.monotonic()
    record: dict[str, object] = {"caseId": case.id, "url": url, "deadlineMs": body.get("deadlineMs")}
    try:
        async with httpx.AsyncClient() as client:
            response = await client.post(
                url,
                content=payload,
                headers={
                    "Authorization": f"Bearer {token}",
                    "Content-Type": "application/json",
                },
                timeout=httpx.Timeout(DEADLINE_MS / 1000.0 + 5.0, connect=2.0),
            )
        elapsed = int((time.monotonic() - started) * 1000)
        try:
            response_body = response.json()
        except ValueError:
            response_body = {"raw": response.text[:1000]}
        record.update(
            httpStatus=response.status_code,
            elapsedMs=elapsed,
            body=response_body,
        )
    except Exception as exc:  # noqa: BLE001
        record.update(
            httpStatus=None,
            elapsedMs=int((time.monotonic() - started) * 1000),
            errorType=type(exc).__name__,
        )
    finally:
        pacer.after()
    (RAW / "planning-smoke.json").write_text(
        json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return record


async def run() -> int:
    token = os.environ.get(TOKEN_ENV, "").strip()
    if not token:
        print(f"FATAL: {TOKEN_ENV} missing", file=sys.stderr)
        return 2

    cases = load_planning_set(FIXTURE)
    print(
        "planning cases="
        f"{len(cases)} golden={sum(1 for c in cases if c.subset == 'golden')} "
        f"adversarial={sum(1 for c in cases if c.subset == 'adversarial')} "
        f"deadlineMs={DEADLINE_MS}"
    )
    store = PromptStore(Path("/app/prompts"))
    pacer = Pacer(MIN_INTERVAL_SECONDS)
    client = JavaGatewayClient(base_url=GATEWAY_URL, service_token=token)
    started = time.time()
    new_cases: list[PlanningCase] = []
    records: list[dict[str, object]] = []
    try:
        for case in cases:
            new_case, record = await call_one(client, store, pacer, case)
            new_cases.append(new_case)
            records.append(record)
            print(
                f"case={case.id} subset={case.subset} status={record['status']} "
                f"error={record['errorCode']} chars={record['contentChars']} "
                f"elapsedMs={record['elapsedMs']}"
            )
    finally:
        await client.aclose()

    report = evaluate(new_cases)
    document = report_document(report)
    payload = canonical_report_bytes(document)
    (OUT / "aggregate-planning.json").write_bytes(payload)

    outcomes = {case.id: outcome for case, outcome in report.cases}
    truncation_suspects: list[str] = []
    for record in records:
        outcome = outcomes[str(record["id"])]
        usage = record.get("usage") or {}
        output_tokens = usage.get("outputTokens") if isinstance(usage, dict) else None
        record["eval"] = {
            "schemaPass": outcome.schema_pass,
            "reason": outcome.reason,
            "warnings": outcome.warnings,
            "sourceViolations": outcome.source_violations,
            "assigneeViolations": outcome.assignee_violations,
            "duplicateTitles": outcome.duplicate_titles,
            "sourceRefsEmpty": outcome.source_refs_empty,
        }
        suspected = (
            isinstance(output_tokens, int) and output_tokens >= PROVIDER_MAX_OUTPUT_TOKENS
        ) or (not outcome.schema_pass and outcome.reason is not None and "JSON" in outcome.reason)
        record["truncationSuspected"] = suspected
        if suspected:
            truncation_suspects.append(str(record["id"]))

    with (RAW / "planning-cases.jsonl").open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")

    sealed = json.loads(payload)
    aggregate = sealed["aggregate"]
    summary = {
        "sha256": sealed["sha256"],
        "elapsedSeconds": round(time.time() - started, 1),
        "deadlineMs": DEADLINE_MS,
        "rateLimitedTotal": sum(int(r["rateLimited"]) for r in records),
        "failedCases": [
            {"id": r["id"], "subset": r["subset"], "errorCode": r["errorCode"]}
            for r in records
            if r["status"] != "ok"
        ],
        "truncationSuspects": truncation_suspects,
        "aggregate": aggregate,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    # 在线端点冒烟：第一条 golden case（按 id 排序）
    golden = sorted(
        (case for case in cases if case.subset == "golden"), key=lambda item: item.id
    )
    smoke_record = await smoke_endpoint(golden[0], pacer, token)
    print(
        "planning smoke "
        + json.dumps(
            {
                "caseId": smoke_record.get("caseId"),
                "httpStatus": smoke_record.get("httpStatus"),
                "elapsedMs": smoke_record.get("elapsedMs"),
                "usage": (smoke_record.get("body") or {}).get("usage")
                if isinstance(smoke_record.get("body"), dict)
                else None,
                "latencyMs": (smoke_record.get("body") or {}).get("latencyMs")
                if isinstance(smoke_record.get("body"), dict)
                else None,
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(run()))
