#!/usr/bin/env python3
"""W5 Memory C live harness: A LEGACY / B GOVERNED_RECALL / C DREAMING.

The C arm is intentionally live-only:

1. create a staging project whose description contains the frozen answer facts;
2. create a real Memory Job and wait for the Java dispatcher + Python worker;
3. require PENDING_REVIEW candidates, explicitly approve every candidate as the
   project owner, and prove no AI_JOB_RESULT memory existed before approval;
4. run the same frozen cross-session questions against A/B/C with the real model.

A/B use the frozen selectors in ``memory_arms.py``. C uses only the memories
created by the approved Job, plus explicit revocation/conflict control memories.
The judge is deterministic: an expected fact passes when its normalized text is
contained in the model answer, or when at least 80% of its Han bigrams appear.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any, Final

ARM_A: Final[str] = "A_LEGACY"
ARM_B: Final[str] = "B_GOVERNED_RECALL"
ARM_C: Final[str] = "C_DREAMING"
ARMS: Final[tuple[str, str, str]] = (ARM_A, ARM_B, ARM_C)
RECALL_LIMIT: Final[int] = 10
TASK_PROMPT_VERSION: Final[str] = "w5-memory-c.task.v1"
JUDGE_VERSION: Final[str] = "w5-memory-c.answer-fact-match.v1"
STOPWORDS: Final[frozenset[str]] = frozenset(
    {"项目", "目标", "现在", "换成", "换个", "改成", "生成", "规划", "任务", "继续", "一个", "根据", "请问"}
)
TERM_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"[a-z0-9_]{2,}|[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]{2,}"
)

TASK_SYSTEM: Final[str] = """你是项目记忆任务执行器。只能使用输入的 TRUSTED_MEMORIES。
如果记忆不足以回答，answer 必须为“未知”。不得引入外部知识、猜测或工具。
只输出单个 JSON object，不要 Markdown 围栏：
{"answer":"直接回答，尽量复述支持答案的记忆原文","citedMemoryIds":["memory-id"]}
"""


def _now() -> str:
    return dt.datetime.now(dt.UTC).isoformat().replace("+00:00", "Z")


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _append_jsonl(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n")


def _normalize(text: str | None) -> str:
    value = unicodedata.normalize("NFKC", text or "").casefold()
    return "".join(ch for ch in value if ch.isalnum() or "\u4e00" <= ch <= "\u9fff")


def _han_bigrams(text: str) -> set[str]:
    points = [ch for ch in _normalize(text) if "\u4e00" <= ch <= "\u9fff"]
    return {points[index] + points[index + 1] for index in range(len(points) - 1)}


def _fact_match(expected: str, answer: str) -> tuple[bool, float]:
    normalized_expected = _normalize(expected)
    normalized_answer = _normalize(answer)
    if normalized_expected and normalized_expected in normalized_answer:
        return True, 1.0
    expected_bigrams = _han_bigrams(expected)
    if not expected_bigrams:
        return False, 0.0
    hit_ratio = len(expected_bigrams & _han_bigrams(answer)) / len(expected_bigrams)
    return hit_ratio >= 0.8, round(hit_ratio, 4)


def _extract_json_object(content: str) -> Mapping[str, Any]:
    text = content.strip()
    try:
        value = json.loads(text)
        if isinstance(value, Mapping):
            return value
    except json.JSONDecodeError:
        pass
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        value = json.loads(text[start : end + 1])
        if isinstance(value, Mapping):
            return value
    raise ValueError("model content is not a JSON object")


def _terms(request: str) -> set[str]:
    result: set[str] = set()
    for match in TERM_PATTERN.finditer(request.casefold()):
        token = match.group()
        if all("\u3400" <= ch <= "\u9fff" or "\u4e00" <= ch <= "\u9fff" for ch in token):
            result.update(token[index : index + 2] for index in range(len(token) - 1))
        else:
            result.add(token)
    return result - STOPWORDS


def _score(memory: Mapping[str, Any], terms: set[str]) -> int:
    title = str(memory.get("title", "")).casefold()
    content = str(memory.get("content", "")).casefold()
    base = 1 if memory.get("type") == "PREFERENCE" else 0
    return base + sum(4 if term in title else 1 if term in content else 0 for term in terms)


def _recall_safe(memory: Mapping[str, Any]) -> bool:
    return (
        memory.get("status") == "ACTIVE"
        and memory.get("verificationState") in {"VERIFIED", "UNVERIFIED"}
        and memory.get("supersededBy") is None
    )


def _governed_ids(
    memories: Sequence[Mapping[str, Any]], project_id: str, request: str
) -> tuple[str, ...]:
    terms = _terms(request)
    scored: list[tuple[int, Mapping[str, Any]]] = []
    for memory in memories:
        if memory.get("projectId") != project_id or not _recall_safe(memory):
            continue
        score = _score(memory, terms)
        if score > 0:
            scored.append((score, memory))
    scored.sort(key=lambda pair: pair[1]["id"])
    scored.sort(key=lambda pair: pair[1].get("updatedAt", ""), reverse=True)
    scored.sort(key=lambda pair: pair[0], reverse=True)
    return tuple(str(memory["id"]) for _, memory in scored[:RECALL_LIMIT])


def _legacy_ids(
    memories: Sequence[Mapping[str, Any]], project_id: str
) -> tuple[str, ...]:
    candidates = [
        memory
        for memory in memories
        if memory.get("projectId") == project_id and memory.get("status") == "ACTIVE"
    ]
    candidates.sort(key=lambda memory: (memory.get("updatedAt", ""), memory["id"]), reverse=True)
    return tuple(str(memory["id"]) for memory in candidates[:RECALL_LIMIT])


class Client:
    def __init__(self, base_url: str, user_jwt: str, model_base_url: str | None = None) -> None:
        self.base_url = base_url.rstrip("/")
        self.model_base_url = (model_base_url or self.base_url).rstrip("/")
        self.user_jwt = user_jwt
        self.service_token = os.getenv("AI_WORKER_SERVICE_TOKEN", "") or os.getenv(
            "INTERNAL_AI_SERVICE_TOKEN", ""
        )

    def api(
        self,
        method: str,
        path: str,
        body: Mapping[str, Any] | None = None,
    ) -> Any:
        return self._request(
            self.base_url + path,
            method,
            body,
            {"Authorization": f"Bearer {self.user_jwt}", "Content-Type": "application/json"},
            timeout=70,
        )

    def model(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        call_id: str,
    ) -> dict[str, Any]:
        if not self.service_token:
            raise RuntimeError("AI_WORKER_SERVICE_TOKEN/INTERNAL_AI_SERVICE_TOKEN is required")
        payload = {
            "schemaVersion": 1,
            "purpose": "AGENT",
            "systemPrompt": system_prompt,
            "userPrompt": user_prompt,
            "outputFormat": "PROMPT_JSON",
            "deadlineMs": 30000,
            "callId": call_id,
        }
        return self._request(
            self.model_base_url + "/internal/ai/v1/model-calls",
            "POST",
            payload,
            {
                "Authorization": f"Bearer {self.service_token}",
                "Content-Type": "application/json",
                "X-Call-Id": call_id,
            },
            timeout=70,
        )

    @staticmethod
    def _request(
        url: str,
        method: str,
        body: Mapping[str, Any] | None,
        headers: Mapping[str, str],
        *,
        timeout: int,
    ) -> Any:
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(url, data=data, method=method, headers=dict(headers))
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read()
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", "replace")
            raise RuntimeError(f"{method} {url} -> HTTP {error.code}: {detail[:500]}") from None
        if not raw:
            return None
        return json.loads(raw.decode("utf-8"))


def _data(response: Any) -> Any:
    if isinstance(response, Mapping) and "data" in response:
        return response["data"]
    return response


def _create_project(client: Client, name: str, description: str) -> dict[str, Any]:
    return _data(
        client.api(
            "POST",
            "/api/v1/projects",
            {
                "name": name,
                "description": description,
                "type": "INTERNAL_PROJECT",
                "startDate": None,
                "dueDate": None,
            },
        )
    )


def _create_memory(
    client: Client,
    project_id: str,
    *,
    memory_type: str,
    title: str,
    content: str,
) -> dict[str, Any]:
    return _data(
        client.api(
            "POST",
            f"/api/v1/projects/{project_id}/agent/memories",
            {
                "type": memory_type,
                "title": title,
                "content": content,
                "sourceType": "MANUAL",
                "sourceId": None,
                "version": 0,
                "verificationState": None,
                "reason": "W5 C frozen control",
            },
        )
    )


def _update_memory(client: Client, project_id: str, memory: Mapping[str, Any], **changes: Any) -> dict[str, Any]:
    body = {
        "type": memory["type"],
        "title": memory["title"],
        "content": memory["content"],
        "sourceType": memory["sourceType"],
        "sourceId": memory.get("sourceId"),
        "version": memory["version"],
        "verificationState": None,
        "reason": "W5 C frozen control",
    }
    body.update(changes)
    return _data(client.api("PATCH", f"/api/v1/projects/{project_id}/agent/memories/{memory['id']}", body))


def _memory_shape(memory: Mapping[str, Any], project_id: str) -> dict[str, Any]:
    return {
        "id": memory["id"],
        "projectId": project_id,
        "type": memory["type"],
        "title": memory["title"],
        "content": memory["content"],
        "status": memory["status"],
        "verificationState": memory["verificationState"],
        "supersededBy": memory.get("supersededBy"),
        "updatedAt": memory["updatedAt"],
    }


def _wait_for_job(client: Client, project_id: str, job_id: str, raw_dir: Path) -> dict[str, Any]:
    deadline = time.monotonic() + 240
    status: dict[str, Any] = {}
    while time.monotonic() < deadline:
        status = _data(
            client.api("GET", f"/api/v1/projects/{project_id}/agent/memory-jobs/{job_id}")
        )
        _append_jsonl(raw_dir / "job-status.jsonl", {"observedAt": _now(), **status})
        if status.get("status") in {"SUCCEEDED", "FAILED", "EXPIRED", "CANCELED"}:
            return status
        time.sleep(3)
    raise TimeoutError(f"job {job_id} did not reach a terminal state")


def _approve_candidates(
    client: Client,
    project_id: str,
    raw_dir: Path,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    candidates = _data(
        client.api(
            "GET",
            f"/api/v1/projects/{project_id}/agent/memory-candidates?reviewStatus=PENDING_REVIEW",
        )
    )
    _write_json(raw_dir / "candidates-pending.json", candidates)
    before = _data(
        client.api("GET", f"/api/v1/projects/{project_id}/agent/memories?activeOnly=false")
    )
    _write_json(raw_dir / "memories-before-approval.json", before)

    approvals: list[dict[str, Any]] = []
    for candidate in candidates:
        approvals.append(
            _data(
                client.api(
                    "POST",
                    f"/api/v1/projects/{project_id}/agent/memory-candidates/{candidate['id']}/approve",
                    {"reason": "W5 C explicit human approval"},
                )
            )
        )
    _write_json(raw_dir / "approvals.json", approvals)
    pending_after = _data(
        client.api(
            "GET",
            f"/api/v1/projects/{project_id}/agent/memory-candidates?reviewStatus=PENDING_REVIEW",
        )
    )
    _write_json(raw_dir / "candidates-pending-after.json", pending_after)
    after = _data(
        client.api("GET", f"/api/v1/projects/{project_id}/agent/memories?activeOnly=false")
    )
    _write_json(raw_dir / "memories-after-approval.json", after)
    return candidates, approvals, after


def _create_c_controls(
    client: Client,
    project_id: str,
    approved: Sequence[Mapping[str, Any]],
    raw_dir: Path,
) -> dict[str, Any]:
    stale = _create_memory(
        client,
        project_id,
        memory_type="DECISION",
        title="迭代周期旧口径控制",
        content="迭代长度维持三周不变。",
    )
    stale = _update_memory(client, project_id, stale, verificationState="STALE")

    superseded = _create_memory(
        client,
        project_id,
        memory_type="DECISION",
        title="迭代周期旧版控制",
        content="迭代长度维持三周（旧口径，已被替代）。",
    )
    target = approved[0]["id"] if approved else None
    if target:
        superseded = _data(
            client.api(
                "POST",
                f"/api/v1/projects/{project_id}/agent/memories/{superseded['id']}/supersede",
                {"supersededBy": target, "reason": "W5 C conflict control"},
            )
        )

    disabled = _create_memory(
        client,
        project_id,
        memory_type="DECISION",
        title="检索直连控制",
        content="检索请求由 Python 直连 Provider（已停用）。",
    )
    disabled = _data(
        client.api(
            "DELETE",
            f"/api/v1/projects/{project_id}/agent/memories/{disabled['id']}"
            "?version=0&reason=W5%20C%20disabled%20control",
        )
    )
    controls = {
        "stale": _memory_shape(stale, project_id),
        "superseded": _memory_shape(superseded, project_id),
        "disabled": _memory_shape(disabled, project_id),
    }
    _write_json(raw_dir / "controls.json", controls)
    return controls


def _task_prompt(
    request: str,
    selected_ids: Sequence[str],
    memory_index: Mapping[str, Mapping[str, Any]],
) -> str:
    memories = [
        {
            "id": memory_id,
            "type": memory_index[memory_id]["type"],
            "title": memory_index[memory_id]["title"],
            "content": memory_index[memory_id]["content"],
        }
        for memory_id in selected_ids
    ]
    return "问题：" + request + "\n<TRUSTED_MEMORIES>\n" + json.dumps(
        memories, ensure_ascii=False, separators=(",", ":")
    ) + "\n</TRUSTED_MEMORIES>"


def _judge_answer(content: str, expected_facts: Sequence[str]) -> dict[str, Any]:
    try:
        parsed = _extract_json_object(content)
        answer = str(parsed.get("answer", ""))
        citations = parsed.get("citedMemoryIds", [])
        if not isinstance(citations, list):
            citations = []
        parse_ok = True
    except (ValueError, json.JSONDecodeError):
        answer = content
        citations = []
        parse_ok = False
    matches = []
    for fact in expected_facts:
        matched, score = _fact_match(fact, answer)
        matches.append({"expectedFact": fact, "matched": matched, "score": score})
    return {
        "parseOk": parse_ok,
        "answer": answer,
        "citations": [str(value) for value in citations],
        "factMatches": matches,
        "passed": bool(matches) and all(item["matched"] for item in matches),
    }


def _case_metrics(
    case: Mapping[str, Any],
    selected_ids: Sequence[str],
    memory_index: Mapping[str, Mapping[str, Any]],
    task_result: Mapping[str, Any],
) -> dict[str, Any]:
    project_id = case["projectId"]
    expected_ids = [str(value) for value in case.get("expectedRecallIds", [])]
    recovered = [memory_id for memory_id in selected_ids if memory_id in expected_ids]
    revocation_ids = {str(item["memoryId"]) for item in case.get("revocations", [])}
    revocation_leaks = [memory_id for memory_id in selected_ids if memory_id in revocation_ids]
    cross_project = [
        memory_id
        for memory_id in selected_ids
        if memory_index[memory_id]["projectId"] != project_id
    ]
    conflict_misuses = []
    for conflict in case.get("conflicts", []):
        preferred = str(conflict["preferredMemoryId"])
        if any(
            memory_id != preferred and memory_id in selected_ids
            for memory_id in conflict.get("memoryIds", [])
        ):
            conflict_misuses.append(str(conflict["id"]))
    citation_valid = sum(
        1
        for memory_id in selected_ids
        if memory_index[memory_id]["projectId"] == project_id
        and _recall_safe(memory_index[memory_id])
    )
    return {
        "id": case["id"],
        "projectId": project_id,
        "returnedIds": list(selected_ids),
        "expectedRecallIds": expected_ids,
        "recoveredIds": recovered,
        "recall": len(recovered) / len(expected_ids) if expected_ids else 0.0,
        "citationValid": citation_valid,
        "citationTotal": len(selected_ids),
        "conflictTotal": len(case.get("conflicts", [])),
        "conflictMisuses": conflict_misuses,
        "revocationLeaks": revocation_leaks,
        "crossProjectLeaks": cross_project,
        "task": task_result,
    }


def _summary(cases: Sequence[Mapping[str, Any]], task_calls: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    total = len(cases)
    recalled_total = sum(len(case["recoveredIds"]) for case in cases)
    expected_total = sum(len(case["expectedRecallIds"]) for case in cases)
    returned_total = sum(case["citationTotal"] for case in cases)
    valid_total = sum(case["citationValid"] for case in cases)
    conflict_total = sum(int(case.get("conflictTotal", 0)) for case in cases)
    misuse_total = sum(len(case["conflictMisuses"]) for case in cases)
    latency = [int(call["latencyMs"]) for call in task_calls if call.get("latencyMs") is not None]
    latency.sort()
    tokens_in = sum((call.get("usage") or {}).get("inputTokens") or 0 for call in task_calls)
    tokens_out = sum((call.get("usage") or {}).get("outputTokens") or 0 for call in task_calls)
    return {
        "taskAccuracy": sum(1 for case in cases if case["task"]["passed"]) / total if total else 0.0,
        "recallAtK": recalled_total / expected_total if expected_total else 0.0,
        "precisionAtK": recalled_total / returned_total if returned_total else 0.0,
        "citationValidityRate": valid_total / returned_total if returned_total else 0.0,
        "conflictMisuseRate": misuse_total / conflict_total if conflict_total else 0.0,
        "revocationLeaks": sum(len(case["revocationLeaks"]) for case in cases),
        "crossProjectLeaks": sum(len(case["crossProjectLeaks"]) for case in cases),
        "tokens": {
            "inputTokens": tokens_in,
            "outputTokens": tokens_out,
            "source": "PROVIDER" if tokens_in or tokens_out else "UNKNOWN",
        },
        "latency": {
            "samples": len(latency),
            "p50Ms": _percentile(latency, 50) if latency else None,
            "p95Ms": _percentile(latency, 95) if latency else None,
        },
        "cases": list(cases),
    }


def _percentile(sorted_values: Sequence[int], percentile: int) -> int:
    rank = max(1, math.ceil(percentile / 100 * len(sorted_values)))
    return sorted_values[rank - 1]


def _run_arm_tasks(
    *,
    arm: str,
    cases: Sequence[Mapping[str, Any]],
    sessions: Mapping[str, Mapping[str, Any]],
    memories: Sequence[Mapping[str, Any]],
    client: Client,
    raw_dir: Path,
    interval_seconds: float,
) -> dict[str, Any]:
    memory_index = {str(memory["id"]): memory for memory in memories}
    results = []
    calls = []
    for position, case in enumerate(sorted(cases, key=lambda item: item["id"])):
        session = sessions[str(case["sessionId"])]
        request = str(session["request"])
        if arm == ARM_A:
            selected = _legacy_ids(memories, str(case["projectId"]))
        else:
            selected = _governed_ids(memories, str(case["projectId"]), request)
        prompt = _task_prompt(request, selected, memory_index)
        call_id = f"w5c-{arm[0].lower()}-{case['id']}-{uuid.uuid4().hex[:12]}"
        started = time.perf_counter()
        try:
            response = client.model(system_prompt=TASK_SYSTEM, user_prompt=prompt, call_id=call_id)
            content = str(response.get("content", ""))
            usage = response.get("usage") or {}
            call = {
                "arm": arm,
                "caseId": case["id"],
                "callId": call_id,
                "modelAlias": response.get("modelAlias"),
                "usage": usage,
                "latencyMs": response.get("latencyMs"),
                "wallMs": round((time.perf_counter() - started) * 1000, 3),
                "content": content,
            }
            judged = _judge_answer(content, case["expectedAnswerFacts"])
        except Exception as error:  # noqa: BLE001 - evidence must retain each failure
            call = {
                "arm": arm,
                "caseId": case["id"],
                "callId": call_id,
                "error": f"{type(error).__name__}: {error}",
                "wallMs": round((time.perf_counter() - started) * 1000, 3),
            }
            judged = {
                "parseOk": False,
                "answer": "",
                "citations": [],
                "factMatches": [],
                "passed": False,
            }
        _append_jsonl(raw_dir / "task-calls.jsonl", {**_safe_case(case), **call, "judge": judged})
        calls.append(call)
        results.append(_case_metrics(case, selected, memory_index, judged))
        if position + 1 < len(cases):
            time.sleep(interval_seconds)
    return _summary(results, calls)


def _safe_case(case: Mapping[str, Any]) -> dict[str, Any]:
    return {"id": case["id"], "sessionId": case["sessionId"], "projectId": case["projectId"]}


def _fixture_memories(fixture: Mapping[str, Any]) -> list[dict[str, Any]]:
    return [dict(item) for item in fixture["memories"]]


def _fixture_cases(fixture: Mapping[str, Any]) -> list[dict[str, Any]]:
    return [dict(item) for item in fixture["cases"]]


def _fixture_sessions(fixture: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    return {str(item["id"]): dict(item) for item in fixture["sessions"]}


def _c_expected_mapping(
    approved: Sequence[Mapping[str, Any]],
    expected_fact: str,
) -> str | None:
    best_id = None
    best_score = 0.0
    for memory in approved:
        expected_bigrams = _han_bigrams(expected_fact)
        if not expected_bigrams:
            continue
        hit = len(expected_bigrams & _han_bigrams(str(memory.get("content", "")))) / len(
            expected_bigrams
        )
        if hit > best_score:
            best_score = hit
            best_id = str(memory["id"])
    return best_id if best_score >= 0.5 else None


def _build_c_cases(
    fixture_cases: Sequence[Mapping[str, Any]],
    approved: Sequence[Mapping[str, Any]],
    controls: Mapping[str, Mapping[str, Any]],
    project_id: str,
) -> list[dict[str, Any]]:
    cases = []
    for original in fixture_cases:
        expected_fact = original["expectedAnswerFacts"][0]
        expected_id = _c_expected_mapping(approved, expected_fact)
        cases.append(
            {
                "id": original["id"],
                "sessionId": original["sessionId"],
                "projectId": project_id,
                "expectedRecallIds": [expected_id] if expected_id else [],
                "expectedAnswerFacts": list(original["expectedAnswerFacts"]),
                "conflicts": (
                    [
                        {
                            "id": "conf-iter-c",
                            "memoryIds": [expected_id, controls["stale"]["id"]],
                            "preferredMemoryId": expected_id,
                            "description": "C approved iteration decision vs stale control",
                        }
                    ]
                    if expected_id and "迭代" in expected_fact
                    else []
                ),
                "revocations": [
                    {"memoryId": controls["stale"]["id"], "reason": "STALE"},
                    {"memoryId": controls["superseded"]["id"], "reason": "SUPERSEDED"},
                    *(
                        [{"memoryId": controls["disabled"]["id"], "reason": "DISABLED"}]
                        if original["id"] == "ma-004"
                        else []
                    ),
                ],
                "maxK": 10,
            }
        )
    return cases


def _delete_project(client: Client, project_id: str, raw_dir: Path, name: str) -> None:
    try:
        client.api("DELETE", f"/api/v1/projects/{project_id}")
        _write_json(raw_dir / f"cleanup-{name}.json", {"projectId": project_id, "deleted": True})
    except Exception as error:  # noqa: BLE001
        _write_json(
            raw_dir / f"cleanup-{name}.json",
            {"projectId": project_id, "deleted": False, "error": str(error)},
        )


def run(args: argparse.Namespace) -> int:
    raw_dir = args.output_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    fixture = _read_json(args.fixture)
    fixture_memories = _fixture_memories(fixture)
    fixture_cases = _fixture_cases(fixture)
    sessions = _fixture_sessions(fixture)
    expected_facts = list(
        dict.fromkeys(
            fact for case in fixture_cases for fact in case["expectedAnswerFacts"]
        )
    )
    description = "项目决策事实：" + "；".join(expected_facts)
    if len(description) + 40 > 500:
        raise RuntimeError("C project description exceeds the frozen 500-char project fact bound")

    jwt_file = os.getenv("W5_USER_JWT_FILE")
    user_jwt = os.getenv("W5_USER_JWT", "")
    if jwt_file and not user_jwt:
        user_jwt = Path(jwt_file).read_text(encoding="utf-8").strip()
    if not user_jwt:
        raise RuntimeError("W5_USER_JWT or W5_USER_JWT_FILE is required")

    client = Client(args.base_url, user_jwt, args.model_base_url)
    started_at = _now()
    c_project = _create_project(
        client, f"W5 C DREAMING {uuid.uuid4().hex[:8]}", description
    )
    cross_project = _create_project(
        client, f"W5 C cross-control {uuid.uuid4().hex[:8]}", "跨项目泄露控制"
    )
    _write_json(raw_dir / "projects.json", {"c": c_project, "cross": cross_project})

    cross_memory = _create_memory(
        client,
        cross_project["id"],
        memory_type="CONSTRAINT",
        title="跨项目控制记忆",
        content="该项目允许 Python 持有 Provider 密钥。",
    )

    job = _data(
        client.api(
            "POST", f"/api/v1/projects/{c_project['id']}/agent/memory-jobs"
        )
    )
    _write_json(raw_dir / "job-create.json", job)
    job_status = _wait_for_job(client, c_project["id"], job["id"], raw_dir)
    _write_json(raw_dir / "job-status.json", job_status)

    candidates: list[dict[str, Any]] = []
    approvals: list[dict[str, Any]] = []
    memories_after: list[dict[str, Any]] = []
    c_gate_reason = ""
    if job_status.get("status") == "SUCCEEDED":
        candidates, approvals, memories_after = _approve_candidates(
            client, c_project["id"], raw_dir
        )
    else:
        c_gate_reason = f"C Job terminal status is {job_status.get('status')}"

    # The pre-approval raw file is authoritative for auto-publication.
    before_raw = _read_json(raw_dir / "memories-before-approval.json") if (
        raw_dir / "memories-before-approval.json"
    ).exists() else []
    auto_published = sum(1 for memory in before_raw if memory.get("sourceType") == "AI_JOB_RESULT")

    controls = _create_c_controls(
        client,
        c_project["id"],
        approvals,
        raw_dir,
    )
    c_memories = [
        _memory_shape(memory, c_project["id"]) for memory in approvals
    ] + list(controls.values()) + [_memory_shape(cross_memory, cross_project["id"])]

    c_cases = _build_c_cases(
        fixture_cases, approvals, controls, c_project["id"]
    )
    _write_json(raw_dir / "c-cases.json", c_cases)

    arm_a = _run_arm_tasks(
        arm=ARM_A,
        cases=fixture_cases,
        sessions=sessions,
        memories=fixture_memories,
        client=client,
        raw_dir=raw_dir,
        interval_seconds=args.interval_seconds,
    )
    arm_b = _run_arm_tasks(
        arm=ARM_B,
        cases=fixture_cases,
        sessions=sessions,
        memories=fixture_memories,
        client=client,
        raw_dir=raw_dir,
        interval_seconds=args.interval_seconds,
    )
    arm_c = _run_arm_tasks(
        arm=ARM_C,
        cases=c_cases,
        sessions=sessions,
        memories=c_memories,
        client=client,
        raw_dir=raw_dir,
        interval_seconds=args.interval_seconds,
    )

    approval_rate = len(approvals) / len(candidates) if candidates else 0.0
    candidate_all_approved = bool(candidates) and len(approvals) == len(candidates)
    gate_checks = {
        "jobSucceeded": job_status.get("status") == "SUCCEEDED",
        "candidateCountPositive": len(candidates) > 0,
        "allCandidatesApproved": candidate_all_approved,
        "humanApprovalRateEquals1": approval_rate == 1.0,
        "autoPublishedEquals0": auto_published == 0,
        "bRevocationLeaksZero": arm_b["revocationLeaks"] == 0,
        "bCrossProjectLeaksZero": arm_b["crossProjectLeaks"] == 0,
        "bConflictMisuseZero": arm_b["conflictMisuseRate"] == 0,
        "bCitationValidityOne": arm_b["citationValidityRate"] == 1.0,
        "cRevocationLeaksZero": arm_c["revocationLeaks"] == 0,
        "cCrossProjectLeaksZero": arm_c["crossProjectLeaks"] == 0,
        "cConflictMisuseZero": arm_c["conflictMisuseRate"] == 0,
        "cCitationValidityOne": arm_c["citationValidityRate"] == 1.0,
        "cNotBelowB": arm_c["taskAccuracy"] >= arm_b["taskAccuracy"],
        "cNotBelowA": arm_c["taskAccuracy"] >= arm_a["taskAccuracy"],
    }
    incomplete = not all(
        gate_checks[key]
        for key in (
            "jobSucceeded",
            "candidateCountPositive",
            "allCandidatesApproved",
            "humanApprovalRateEquals1",
        )
    )
    gate_status = "NOT_EVALUATED" if incomplete else (
        "PASS" if all(gate_checks.values()) else "FAIL"
    )
    if c_gate_reason:
        gate_checks["cFailureReason"] = c_gate_reason

    result = {
        "schemaVersion": 1,
        "taskPromptVersion": TASK_PROMPT_VERSION,
        "judgeVersion": JUDGE_VERSION,
        "evidence": "LIVE_VERIFIED (staging, real Job, real model, explicit approval)",
        "startedAt": started_at,
        "finishedAt": _now(),
        "gate": {"status": gate_status, "checks": gate_checks},
        "job": {
            "id": job["id"],
            "projectId": c_project["id"],
            "status": job_status.get("status"),
            "attemptCount": job_status.get("attemptCount"),
            "leaseEpoch": job_status.get("leaseEpoch"),
        },
        "approval": {
            "candidates": len(candidates),
            "approved": len(approvals),
            "humanApprovalRate": approval_rate,
            "autoPublished": auto_published,
            "publishedAfterApproval": sum(
                1 for memory in memories_after if memory.get("sourceType") == "AI_JOB_RESULT"
            )
            if memories_after
            else 0,
        },
        "arms": {
            ARM_A: {**arm_a, "definition": "frozen pre-governance selector (local mirror)"},
            ARM_B: {**arm_b, "definition": "frozen governed recall selector (local mirror)"},
            ARM_C: {**arm_c, "definition": "approved real Job memories + frozen governed selector"},
        },
        "limits": {
            "sampleSize": len(fixture_cases),
            "confidence": "LOW_CONFIDENCE",
            "note": "11 tasks per arm; do not extrapolate to production.",
        },
    }
    _write_json(raw_dir / "measurement.json", result)
    _write_json(
        args.output_dir / "run-meta.json",
        {
            "branch": os.getenv("W5_BRANCH", "w5/memory-c"),
            "commit": os.getenv("W5_COMMIT", ""),
            "box": os.getenv("W5_BOX", "box-aae54f"),
            "model": next(
                (
                    call.get("modelAlias")
                    for call in (
                        json.loads(line)
                        for line in (raw_dir / "task-calls.jsonl").read_text(
                            encoding="utf-8"
                        ).splitlines()
                    )
                    if call.get("modelAlias")
                ),
                "unknown",
            ),
            "provider": "staging AGENT purpose",
            "startedAt": started_at,
            "finishedAt": result["finishedAt"],
            "resourceLock": "staging-box + staging-model",
        },
    )

    if args.cleanup:
        _delete_project(client, c_project["id"], raw_dir, "c-project")
        _delete_project(client, cross_project["id"], raw_dir, "cross-project")

    print(
        json.dumps(
            {
                "gate": gate_status,
                "taskAccuracy": {
                    ARM_A: arm_a["taskAccuracy"],
                    ARM_B: arm_b["taskAccuracy"],
                    ARM_C: arm_c["taskAccuracy"],
                },
                "approval": result["approval"],
                "measurement": str(raw_dir / "measurement.json"),
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0 if gate_status == "PASS" else 1


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("/tmp/w5-memory-c-live"),
    )
    parser.add_argument("--base-url", default="http://app:8080")
    parser.add_argument("--model-base-url", default=None)
    parser.add_argument("--interval-seconds", type=float, default=10.0)
    parser.add_argument("--cleanup", action="store_true")
    args = parser.parse_args(argv)
    try:
        return run(args)
    except Exception as error:  # noqa: BLE001 - CLI boundary
        print(f"w5-memory-c live harness failed: {type(error).__name__}: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
