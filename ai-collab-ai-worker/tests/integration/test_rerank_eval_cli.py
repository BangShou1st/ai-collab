"""``ai-eval rerank`` 的集成测试。

真实子进程覆盖：canonical 报告两次运行逐字节一致、``--output`` 与 stdout 相同、
``--measure-latency`` 的 20×2 口径（这里用 3×1 缩短耗时）、文本摘要与退出码。

worker 路径用 ``httpx.MockTransport`` 在进程内注入：正常响应、请求契约（§1 字段与
``Authorization`` 头）、503 错误码透传、非法响应、连接超时与缺令牌。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

import httpx
import pytest

from ai_collab_worker.eval import cli, rerank
from ai_collab_worker.eval.canonical import canonicalize_document, digest
from ai_collab_worker.eval.rerank import (
    DEFAULT_TOP_K,
    WorkerError,
    build_worker_request,
    load_rerank_set,
    ndcg_at_k,
    support_rate,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
RERANK_SET = PROJECT_ROOT / "tests" / "fixtures" / "rerank_set" / "rerank_baseline.v1.jsonl"
CLI_MODULE = "ai_collab_worker.eval.cli"
TOKEN_ENV = "AI_EVAL_W3_S2_TEST_TOKEN"


def _run(*args: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        [sys.executable, "-m", CLI_MODULE, *args],
        cwd=PROJECT_ROOT,
        capture_output=True,
        check=False,
        timeout=300,
    )


def _rerank_json() -> dict[str, Any]:
    result = _run("rerank", str(RERANK_SET), "--scorer", "heuristic", "--json")
    assert result.returncode == 0, result.stderr
    payload: dict[str, Any] = json.loads(result.stdout)
    return payload


# --------------------------------------------------------------------------- #
# 子进程：heuristic 路径
# --------------------------------------------------------------------------- #


def test_heuristic_json_is_byte_stable_and_self_consistent() -> None:
    first = _run("rerank", str(RERANK_SET), "--scorer", "heuristic", "--json")
    second = _run("rerank", str(RERANK_SET), "--scorer", "heuristic", "--json")

    assert first.returncode == 0, first.stderr
    assert second.returncode == 0, second.stderr
    assert first.stdout == second.stdout
    assert first.stderr == b""
    assert not first.stdout.endswith(b"\n")

    payload = json.loads(first.stdout)
    assert payload["schemaVersion"] == 1
    assert payload["scorer"] == "heuristic"
    assert payload["topK"] == DEFAULT_TOP_K
    assert payload["aggregate"]["cases"] == 15
    assert 0.0 <= payload["aggregate"]["ndcgAt8"] <= 1.0
    assert 0.0 <= payload["aggregate"]["supportRate"] <= 1.0
    assert payload["aggregate"]["p95Ms"] is None
    # W3.1 冻结 §1.5：不测延迟时三口径全 null、样本 0、置信度 LOW、未补齐候选。
    assert payload["aggregate"]["totalP50Ms"] is None
    assert payload["aggregate"]["totalP95Ms"] is None
    assert payload["aggregate"]["totalP99Ms"] is None
    assert payload["aggregate"]["overheadP95Ms"] is None
    assert payload["aggregate"]["overheadP99Ms"] is None
    assert payload["aggregate"]["modelP95Ms"] is None
    assert payload["aggregate"]["modelP99Ms"] is None
    assert payload["aggregate"]["samples"] == 0
    assert payload["aggregate"]["modelKnownSamples"] == 0
    assert payload["aggregate"]["confidence"] == "LOW_CONFIDENCE"
    assert payload["aggregate"]["candidateTarget"] is None
    assert payload["latency"] == {
        "measured": False,
        "runs": 0,
        "warmup": 0,
        "samples": 0,
        "p95Ms": None,
    }

    # sha256 口径：canonical(document 去掉 sha256 键)。
    body = {key: value for key, value in payload.items() if key != "sha256"}
    assert payload["sha256"] == digest(canonicalize_document(body))

    # 报告只含 id/分数/数字，不应出现 \uXXXX 转义；顶层键按码点升序。
    assert b"\\u" not in first.stdout
    assert list(payload) == sorted(payload)

    # 逐 case 的 NDCG/支持率与冻结集一致（独立复算，防止报告与指标脱节）。
    cases = {case.id: case for case in load_rerank_set(RERANK_SET)}
    for case_report in payload["cases"]:
        case = cases[case_report["id"]]
        assert case_report["ndcgAt8"] == ndcg_at_k(case_report["ranking"], case.labels, 8)
        assert case_report["supportRate"] == support_rate(case_report["ranking"], case.supports, 8)
        assert len(case_report["ranking"]) <= 8


def test_output_file_matches_stdout(tmp_path: Path) -> None:
    output = tmp_path / "report.json"
    result = _run(
        "rerank",
        str(RERANK_SET),
        "--scorer",
        "heuristic",
        "--json",
        "--output",
        str(output),
    )
    assert result.returncode == 0, result.stderr
    assert result.stdout == b""
    assert (
        output.read_bytes()
        == _run("rerank", str(RERANK_SET), "--scorer", "heuristic", "--json").stdout
    )


def test_text_summary_has_scores_and_digest() -> None:
    result = _run("rerank", str(RERANK_SET), "--scorer", "heuristic")
    assert result.returncode == 0, result.stderr
    text = result.stdout.decode("utf-8")
    assert text.startswith("scorer=heuristic topK=8 cases=15\n")
    assert "ndcg@8=" in text
    assert "supportRate=" in text
    assert "latency: not measured" in text
    # W3.1 冻结 §1.5：三行延迟摘要（overhead/model/total，P95+P99），并注明门禁只看 overhead。
    assert "overhead: p95=UNKNOWN p99=UNKNOWN" in text
    assert "门禁只看 overhead" in text
    assert "model: p95=UNKNOWN p99=UNKNOWN" in text
    assert "total: p95=UNKNOWN p99=UNKNOWN" in text
    assert "sha256=" in text
    payload = _rerank_json()
    assert f"sha256={payload['sha256']}" in text


def test_measure_latency_samples_all_cases() -> None:
    result = _run(
        "rerank",
        str(RERANK_SET),
        "--scorer",
        "heuristic",
        "--json",
        "--measure-latency",
        "--runs",
        "4",
        "--warmup",
        "1",
    )
    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    assert payload["latency"]["measured"] is True
    assert payload["latency"]["runs"] == 4
    assert payload["latency"]["warmup"] == 1
    # 20×2 的缩小版：每条 case 4 次、前 1 次不计 → 15 * 3 个样本。
    assert payload["latency"]["samples"] == 15 * 3
    assert payload["latency"]["p95Ms"] is not None
    assert payload["latency"]["p95Ms"] >= 0.0
    assert payload["aggregate"]["p95Ms"] == payload["latency"]["p95Ms"]
    # heuristic 没有模型调用：total 已知，model/overhead 必须为 null（不填 0 冒充）。
    assert payload["aggregate"]["totalP95Ms"] == payload["latency"]["p95Ms"]
    assert payload["aggregate"]["samples"] == 15 * 3
    assert payload["aggregate"]["modelKnownSamples"] == 0
    assert payload["aggregate"]["modelP95Ms"] is None
    assert payload["aggregate"]["modelP99Ms"] is None
    assert payload["aggregate"]["overheadP95Ms"] is None
    assert payload["aggregate"]["overheadP99Ms"] is None
    assert payload["aggregate"]["totalP50Ms"] is not None
    assert payload["aggregate"]["totalP99Ms"] is not None
    # 45 个样本 <200 → LOW_CONFIDENCE（冻结 §1.5）。
    assert payload["aggregate"]["confidence"] == "LOW_CONFIDENCE"


def test_candidate_target_pads_candidates_and_is_reported() -> None:
    baseline = _rerank_json()
    result = _run(
        "rerank",
        str(RERANK_SET),
        "--scorer",
        "heuristic",
        "--json",
        "--candidate-target",
        "20",
    )
    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)

    assert baseline["aggregate"]["candidateTarget"] is None
    assert payload["aggregate"]["candidateTarget"] == 20
    assert payload["aggregate"]["cases"] == baseline["aggregate"]["cases"]
    # 补齐只影响候选数，不改变 topK 与逐 case 报告结构。
    assert payload["topK"] == baseline["topK"]
    assert [case["id"] for case in payload["cases"]] == [case["id"] for case in baseline["cases"]]
    assert all(len(case["ranking"]) <= 8 for case in payload["cases"])


@pytest.mark.parametrize("target", ["0", "51", "-1"])
def test_invalid_candidate_target_returns_2(target: str) -> None:
    result = _run("rerank", str(RERANK_SET), "--candidate-target", target, "--json")
    assert result.returncode == 2
    assert b"--candidate-target" in result.stderr


def test_invalid_dataset_returns_2_without_traceback(tmp_path: Path) -> None:
    bad = tmp_path / "bad.jsonl"
    bad.write_bytes(b'{"id":"a","query":"x","candidates":[],"labels":{},"supports":{}}\n')
    result = _run("rerank", str(bad), "--scorer", "heuristic", "--json")
    assert result.returncode == 2
    assert result.stderr.startswith(b"invalid dataset: ")
    assert b"Traceback" not in result.stderr


def test_invalid_top_k_returns_2() -> None:
    result = _run("rerank", str(RERANK_SET), "--top-k", "0", "--json")
    assert result.returncode == 2
    assert b"--top-k" in result.stderr


def test_invalid_deadline_returns_2() -> None:
    result = _run("rerank", str(RERANK_SET), "--deadline-ms", "0", "--json")
    assert result.returncode == 2
    assert b"--deadline-ms" in result.stderr


def test_worker_without_url_returns_2() -> None:
    result = _run("rerank", str(RERANK_SET), "--scorer", "worker", "--json")
    assert result.returncode == 2
    assert b"--worker-url" in result.stderr


def _run_without_httpx(
    code: str, env: dict[str, str] | None = None
) -> subprocess.CompletedProcess[bytes]:
    prelude = "import sys; sys.modules['httpx'] = None; "
    return subprocess.run(
        [sys.executable, "-c", prelude + code],
        cwd=PROJECT_ROOT,
        capture_output=True,
        check=False,
        timeout=120,
        env=env,
    )


def test_p0_subcommands_do_not_require_httpx() -> None:
    """生产镜像不装 dev 依赖：fingerprint/canonicalize/verify 不得因缺 httpx 失效。"""

    set_path = json.dumps(str(RERANK_SET))
    result = _run_without_httpx(
        "from ai_collab_worker.eval.cli import main; "
        f"raise SystemExit(main(['fingerprint', {set_path}]))"
    )
    assert result.returncode == 0, result.stderr
    assert b"sha256=" in result.stdout


def test_worker_without_httpx_reports_client_unavailable() -> None:
    set_path = json.dumps(str(RERANK_SET))
    env = {**os.environ, TOKEN_ENV: "test-token-not-real"}
    result = _run_without_httpx(
        "from ai_collab_worker.eval.cli import main; "
        f"raise SystemExit(main(['rerank', {set_path}, '--scorer', 'worker', "
        "'--worker-url', 'http://worker.test', "
        f"'--service-token-env', {json.dumps(TOKEN_ENV)}, '--json']))",
        env=env,
    )
    assert result.returncode == 4
    assert b"code=WORKER_CLIENT_UNAVAILABLE" in result.stderr


# --------------------------------------------------------------------------- #
# worker：MockTransport 注入
# --------------------------------------------------------------------------- #


def _score_from_position(index: int) -> float:
    return ((index * 3) % 10) / 10.0


def _mock_scorer(handler: Any) -> rerank.WorkerScorer:
    return rerank.WorkerScorer(
        "http://worker.test",
        top_k=DEFAULT_TOP_K,
        token_env=TOKEN_ENV,
        transport=httpx.MockTransport(handler),
    )


def _worker_args() -> list[str]:
    return [
        "rerank",
        str(RERANK_SET),
        "--scorer",
        "worker",
        "--worker-url",
        "http://worker.test",
        "--service-token-env",
        TOKEN_ENV,
        "--json",
    ]


def _capture(monkeypatch: pytest.MonkeyPatch) -> list[bytes]:
    chunks: list[bytes] = []
    monkeypatch.setattr(cli, "_write_stdout", chunks.append)
    return chunks


def test_worker_success_uses_frozen_request_and_is_byte_stable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")
    captured: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        captured.append(
            {
                "url": str(request.url),
                "authorization": request.headers.get("Authorization"),
                "body": body,
            }
        )
        scores = [
            {"id": candidate["id"], "score": _score_from_position(index)}
            for index, candidate in enumerate(body["candidates"])
        ]
        return httpx.Response(
            200,
            json={
                "schemaVersion": 1,
                "scores": scores,
                "modelAlias": "rerank-default",
                "usage": {"inputTokens": 0, "outputTokens": 0, "source": "PROVIDER"},
            },
        )

    chunks = _capture(monkeypatch)
    monkeypatch.setattr(cli, "_make_worker_scorer", lambda _args: _mock_scorer(handler))
    assert cli.main(_worker_args()) == 0
    assert cli.main(_worker_args()) == 0

    assert len(chunks) == 2
    assert chunks[0] == chunks[1]
    payload = json.loads(chunks[0])
    assert payload["scorer"] == "worker"
    assert payload["aggregate"]["cases"] == 15

    # 请求契约：URL、Bearer 头、§1 字段与候选裁剪。
    assert captured[0]["url"] == "http://worker.test/v1/rag/rerank"
    assert captured[0]["authorization"] == "Bearer test-token-not-real"
    body = captured[0]["body"]
    assert body["schemaVersion"] == 1
    assert body["topK"] == 8
    assert body["deadlineMs"] == 3000
    assert set(body) == {"schemaVersion", "query", "topK", "candidates", "deadlineMs"}
    assert all(set(item) == {"id", "title", "text"} for item in body["candidates"])

    # worker 排序 = score 降序（同分稳定），指标与独立复算一致。
    cases = {case.id: case for case in load_rerank_set(RERANK_SET)}
    first = captured[0]["body"]
    ids = [item["id"] for item in first["candidates"]]
    positions = {candidate_id: index for index, candidate_id in enumerate(ids)}
    expected_ranking = sorted(ids, key=lambda cid: -_score_from_position(positions[cid]))[:8]
    case = cases[payload["cases"][0]["id"]]
    assert case.query == first["query"]
    assert payload["cases"][0]["ranking"] == expected_ranking
    assert payload["cases"][0]["ndcgAt8"] == ndcg_at_k(expected_ranking, case.labels, 8)


def _constant_handler(model_latency: object) -> Any:
    """返回所有候选同分（0.5）的 worker 响应；``model_latency`` 为 ``...`` 表示不带该字段。"""

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        scores = [{"id": candidate["id"], "score": 0.5} for candidate in body["candidates"]]
        payload: dict[str, Any] = {
            "schemaVersion": 1,
            "scores": scores,
            "modelAlias": "rerank-default",
        }
        if model_latency is not ...:
            payload["modelLatencyMs"] = model_latency
        return httpx.Response(200, json=payload)

    return handler


def test_worker_measure_latency_records_model_and_overhead(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")
    chunks = _capture(monkeypatch)
    monkeypatch.setattr(
        cli, "_make_worker_scorer", lambda _args: _mock_scorer(_constant_handler(37))
    )

    args = [*_worker_args(), "--measure-latency", "--runs", "2", "--warmup", "0"]
    assert cli.main(args) == 0

    aggregate = json.loads(chunks[0])["aggregate"]
    assert aggregate["samples"] == 15 * 2
    assert aggregate["modelKnownSamples"] == 15 * 2
    assert aggregate["modelP95Ms"] == 37.0
    assert aggregate["modelP99Ms"] == 37.0
    # T_model=37ms 已知 → overhead = max(0, total-37) 有样本，且不可能为负。
    assert aggregate["overheadP95Ms"] is not None
    assert aggregate["overheadP99Ms"] is not None
    assert aggregate["overheadP95Ms"] >= 0.0
    assert aggregate["totalP95Ms"] == aggregate["p95Ms"]
    assert aggregate["confidence"] == "LOW_CONFIDENCE"


def test_worker_measure_latency_without_model_latency_keeps_null(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")
    chunks = _capture(monkeypatch)
    monkeypatch.setattr(
        cli, "_make_worker_scorer", lambda _args: _mock_scorer(_constant_handler(...))
    )

    args = [*_worker_args(), "--measure-latency", "--runs", "2", "--warmup", "0"]
    assert cli.main(args) == 0

    aggregate = json.loads(chunks[0])["aggregate"]
    assert aggregate["samples"] == 15 * 2
    assert aggregate["modelKnownSamples"] == 0
    assert aggregate["modelP95Ms"] is None
    assert aggregate["modelP99Ms"] is None
    assert aggregate["overheadP95Ms"] is None
    assert aggregate["overheadP99Ms"] is None
    assert aggregate["totalP95Ms"] is not None


def test_worker_503_passes_error_code(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            503,
            json={"code": "MODEL_UNAVAILABLE", "message": "模型不可用", "retryable": True},
        )

    monkeypatch.setattr(cli, "_make_worker_scorer", lambda _args: _mock_scorer(handler))
    assert cli.main(_worker_args()) == cli.EXIT_EVAL_FAILED
    error = capsys.readouterr().err
    assert "code=MODEL_UNAVAILABLE" in error
    assert "status=503" in error
    assert "Traceback" not in error


def test_worker_503_without_envelope_maps_status(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="upstream down")

    monkeypatch.setattr(cli, "_make_worker_scorer", lambda _args: _mock_scorer(handler))
    assert cli.main(_worker_args()) == cli.EXIT_EVAL_FAILED
    assert "code=MODEL_UNAVAILABLE" in capsys.readouterr().err


def test_worker_invalid_response_is_rejected(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "schemaVersion": 1,
                "scores": [{"id": "c1", "score": 0.5}],
            },
        )

    monkeypatch.setattr(cli, "_make_worker_scorer", lambda _args: _mock_scorer(handler))
    assert cli.main(_worker_args()) == cli.EXIT_EVAL_FAILED
    assert "code=INVALID_RESPONSE" in capsys.readouterr().err


def test_worker_timeout_maps_to_deadline_exceeded(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("deadline", request=request)

    monkeypatch.setattr(cli, "_make_worker_scorer", lambda _args: _mock_scorer(handler))
    assert cli.main(_worker_args()) == cli.EXIT_EVAL_FAILED
    assert "code=DEADLINE_EXCEEDED" in capsys.readouterr().err


def test_worker_connect_error_maps_to_transport_error(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    monkeypatch.setattr(cli, "_make_worker_scorer", lambda _args: _mock_scorer(handler))
    assert cli.main(_worker_args()) == cli.EXIT_EVAL_FAILED
    assert "code=TRANSPORT_ERROR" in capsys.readouterr().err


def test_worker_missing_service_token_fails_closed(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.delenv(TOKEN_ENV, raising=False)
    assert cli.main(_worker_args()) == cli.EXIT_EVAL_FAILED
    assert "code=SERVICE_TOKEN_MISSING" in capsys.readouterr().err


def test_worker_non_numeric_score_reports_invalid_response(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        scores = [{"id": item["id"], "score": "high"} for item in body["candidates"]]
        return httpx.Response(200, json={"schemaVersion": 1, "scores": scores})

    monkeypatch.setattr(cli, "_make_worker_scorer", lambda _args: _mock_scorer(handler))
    assert cli.main(_worker_args()) == cli.EXIT_EVAL_FAILED
    error = capsys.readouterr().err
    assert "code=INVALID_RESPONSE" in error
    assert "invalid dataset" not in error


def test_worker_scorer_rejects_out_of_range_score(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(TOKEN_ENV, "test-token-not-real")

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        scores = [{"id": item["id"], "score": 1.5} for item in body["candidates"]]
        return httpx.Response(200, json={"schemaVersion": 1, "scores": scores})

    scorer = _mock_scorer(handler)
    try:
        with pytest.raises(WorkerError, match="score"):
            scorer.rank(load_rerank_set(RERANK_SET)[0])
    finally:
        scorer.close()


def test_build_worker_request_is_pure() -> None:
    case = load_rerank_set(RERANK_SET)[0]
    first = build_worker_request(case, top_k=8, deadline_ms=3000)
    second = build_worker_request(case, top_k=8, deadline_ms=3000)
    assert first == second
    assert json.dumps(first, ensure_ascii=False) == json.dumps(second, ensure_ascii=False)
