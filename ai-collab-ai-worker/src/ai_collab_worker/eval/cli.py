"""``ai-eval`` 命令行：canonical JSON、SHA-256 指纹、校验与 rerank/decide 离线评测。

``canonicalize`` / ``fingerprint`` / ``verify`` 的命令与退出码由
``ai-collab-ai-worker/README.md`` §7 固定：

- ``canonicalize INPUT [-o FILE]``：输出 canonical 字节（无尾随换行），给 ``-o`` 时写文件；
- ``fingerprint INPUT [--json]``：``sha256=<hex> cases=<n>``，``--json`` 输出单行 JSON；
- ``verify INPUT --sha256 <hex>``：匹配打印 ``OK <hex>``；不匹配退出码 3；
- ``rerank SET --scorer heuristic|worker [--json] [--output FILE]``：重排离线评测，
  沿用上面的 0/2/3 语义并新增 4 = worker 评测失败；``--json`` 输出 canonical 报告
  （含 ``sha256``），``--measure-latency`` 才按冻结 §4 做 20 次/2 warmup 采样
  （用法与指纹见 ``tests/fixtures/rerank_set/README.md``）；
- ``--candidate-target N``（1–50）：把每条 case 的候选补齐到 N 条（确定性合成候选），
  供 W3.1 stub 基准覆盖 8/20/50 候选；补齐口径下 NDCG/support 不具可比性，
  报告 ``aggregate.candidateTarget`` 记录该值；
- W3.1 三口径：``aggregate`` 输出 ``overheadP95Ms/overheadP99Ms``、``modelP95Ms``、
  ``modelP99Ms``、``totalP50Ms/totalP95Ms/totalP99Ms``、``samples``、
  ``modelKnownSamples``、``confidence``；``p95Ms`` 保留为 ``totalP95Ms`` 的别名。
  全部分位数用最近秩法（不插值），模型耗时未知时为 ``null``；
- ``decide SET --scorer replay|worker [--json] [--output FILE]``：Agent 单轮决策离线评测；
  ``replay``（默认）进程内复用端点解析核心，``worker`` 打 ``POST /v1/agent/decide``；
  报告含 decisionAgreement/jsonValidityRate/invalidToolCalls/schemaViolations/confidence
  （用法与指纹见 ``tests/fixtures/decide_set/README.md``）；
- 输入不合法退出码 2（stderr 一行 ``invalid dataset: ...``），worker 评测失败退出码 4
  （stderr 一行 ``eval failed: code=...``），任何异常都不打印 traceback。
"""

from __future__ import annotations

import argparse
import io
import json
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any, Final

from ai_collab_worker.eval import decide as decide_eval
from ai_collab_worker.eval.canonical import (
    DatasetError,
    canonicalize_cases,
    canonicalize_document,
    canonicalize_path,
    digest,
    load_dataset,
)
from ai_collab_worker.eval.rerank import (
    DEFAULT_DEADLINE_MS,
    DEFAULT_RUNS,
    DEFAULT_SERVICE_TOKEN_ENV,
    DEFAULT_TOP_K,
    DEFAULT_WARMUP_RUNS,
    EvalReport,
    HeuristicScorer,
    Scorer,
    WorkerError,
    WorkerScorer,
    evaluate,
    load_rerank_set,
    pad_candidates,
    report_document,
    seal_report,
)

#: 成功。
EXIT_OK: Final[int] = 0
#: 输入不合法、无法读取等可预期失败（CI 用来区分"数据坏了"与"指纹不对"）。
EXIT_INVALID_INPUT: Final[int] = 2
#: 指纹不匹配（``verify`` 专用）。
EXIT_DIGEST_MISMATCH: Final[int] = 3
#: rerank 评测失败（worker 错误码透传到 stderr，便于 CI 区分故障类型）。
EXIT_EVAL_FAILED: Final[int] = 4


def main(argv: Sequence[str] | None = None) -> int:
    """CLI 入口；返回进程退出码（console script 会交给 ``sys.exit``）。"""

    parser = _build_parser()
    args = parser.parse_args(argv)
    command: str | None = args.command
    if command is None:
        parser.print_usage(sys.stderr)
        print(
            "ai-eval: 需要子命令（canonicalize / fingerprint / verify / rerank / decide）",
            file=sys.stderr,
        )
        return EXIT_INVALID_INPUT

    try:
        if command == "canonicalize":
            return _run_canonicalize(args.input, args.output)
        if command == "fingerprint":
            return _run_fingerprint(args.input, args.json_output)
        if command == "verify":
            return _run_verify(args.input, args.expected_digest)
        if command == "rerank":
            return _run_rerank(args)
        if command == "decide":
            return _run_decide(args)
        print(f"ai-eval: 未知子命令 {command!r}", file=sys.stderr)
        return EXIT_INVALID_INPUT
    except (WorkerError, decide_eval.WorkerError) as exc:
        status = "none" if exc.status is None else str(exc.status)
        print(
            f"eval failed: code={exc.code} status={status} message={exc.detail}",
            file=sys.stderr,
        )
        return EXIT_EVAL_FAILED
    except DatasetError as exc:
        print(f"invalid dataset: {exc}", file=sys.stderr)
        return EXIT_INVALID_INPUT
    except OSError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_INVALID_INPUT
    except Exception as exc:  # CLI 边界：任何异常都收敛成一行 stderr + 退出码 2
        print(f"error: {type(exc).__name__}: {exc}", file=sys.stderr)
        return EXIT_INVALID_INPUT


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ai-eval",
        description="冻结评测集的 canonical JSON、SHA-256 指纹与 rerank/decide 离线评测",
    )
    subparsers = parser.add_subparsers(dest="command", metavar="COMMAND")

    canonicalize = subparsers.add_parser("canonicalize", help="输出 canonical JSON 字节")
    canonicalize.add_argument("input", metavar="INPUT", type=Path)
    canonicalize.add_argument(
        "-o",
        "--output",
        metavar="FILE",
        type=Path,
        default=None,
        help="写入文件而不是 stdout（精确字节，无尾随换行）",
    )

    fingerprint = subparsers.add_parser("fingerprint", help="输出 canonical 字节的 SHA-256")
    fingerprint.add_argument("input", metavar="INPUT", type=Path)
    fingerprint.add_argument(
        "--json",
        action="store_true",
        dest="json_output",
        help="输出单行 JSON：{bytes,cases,sha256}",
    )

    verify = subparsers.add_parser("verify", help="校验给定 SHA-256 摘要")
    verify.add_argument("input", metavar="INPUT", type=Path)
    verify.add_argument(
        "--sha256",
        metavar="HEX",
        dest="expected_digest",
        required=True,
        help="期望的 SHA-256 十六进制摘要",
    )

    rerank = subparsers.add_parser("rerank", help="重排离线评测：NDCG@k / 引用支持率 / P95")
    rerank.add_argument("input", metavar="SET", type=Path)
    rerank.add_argument(
        "--scorer",
        choices=("heuristic", "worker"),
        default="heuristic",
        help="heuristic=Python 复刻 Java 基线；worker=HTTP 打 /v1/rag/rerank",
    )
    rerank.add_argument(
        "--worker-url",
        metavar="URL",
        default=None,
        dest="worker_url",
        help="worker 根地址（--scorer worker 必填，如 http://127.0.0.1:9100）",
    )
    rerank.add_argument(
        "--top-k",
        metavar="N",
        type=int,
        default=DEFAULT_TOP_K,
        dest="top_k",
        help=f"评测截断位置，默认 {DEFAULT_TOP_K}",
    )
    rerank.add_argument(
        "--json",
        action="store_true",
        dest="json_output",
        help="输出 canonical JSON 报告（含 sha256）而不是人类摘要",
    )
    rerank.add_argument(
        "-o",
        "--output",
        metavar="FILE",
        type=Path,
        default=None,
        help="写入文件而不是 stdout（精确字节）",
    )
    rerank.add_argument(
        "--measure-latency",
        action="store_true",
        dest="measure_latency",
        help=(
            "开启冻结 §4 的延迟采样（每条 case 连续 N 次，warmup 不计）；"
            "默认不测，因为实测耗时会破坏同输入的逐字节一致性"
        ),
    )
    rerank.add_argument(
        "--runs",
        metavar="N",
        type=int,
        default=DEFAULT_RUNS,
        dest="runs",
        help=f"--measure-latency 时每条 case 的执行次数（含 warmup），默认 {DEFAULT_RUNS}",
    )
    rerank.add_argument(
        "--warmup",
        metavar="N",
        type=int,
        default=DEFAULT_WARMUP_RUNS,
        help=f"--measure-latency 时每条 case 的预热次数（不计入样本），默认 {DEFAULT_WARMUP_RUNS}",
    )
    rerank.add_argument(
        "--service-token-env",
        metavar="NAME",
        default=DEFAULT_SERVICE_TOKEN_ENV,
        dest="service_token_env",
        help=f"读取内部服务令牌的环境变量名，默认 {DEFAULT_SERVICE_TOKEN_ENV}",
    )
    rerank.add_argument(
        "--deadline-ms",
        metavar="MS",
        type=int,
        default=DEFAULT_DEADLINE_MS,
        dest="deadline_ms",
        help=f"请求体 deadlineMs，默认 {DEFAULT_DEADLINE_MS}",
    )
    rerank.add_argument(
        "--candidate-target",
        metavar="N",
        type=int,
        default=None,
        dest="candidate_target",
        help=(
            "把每条 case 的候选补齐到 N 条（1–50；不足时追加确定性合成候选），"
            "仅用于 stub 基准覆盖候选数；补齐口径下 NDCG 不具可比性"
        ),
    )

    decide = subparsers.add_parser(
        "decide",
        help="Agent 单轮决策评测：一致率 / JSON 合法率 / 无效工具调用数",
    )
    decide.add_argument("input", metavar="SET", type=Path)
    decide.add_argument(
        "--scorer",
        choices=("replay", "worker"),
        default="replay",
        help="replay=进程内复用解析/校验核心（默认）；worker=HTTP 打 /v1/agent/decide",
    )
    decide.add_argument(
        "--worker-url",
        metavar="URL",
        default=None,
        dest="worker_url",
        help="worker 根地址（--scorer worker 必填，如 http://127.0.0.1:9100）",
    )
    decide.add_argument(
        "--json",
        action="store_true",
        dest="json_output",
        help="输出 canonical JSON 报告（含 sha256）而不是人类摘要",
    )
    decide.add_argument(
        "-o",
        "--output",
        metavar="FILE",
        type=Path,
        default=None,
        help="写入文件而不是 stdout（精确字节）",
    )
    decide.add_argument(
        "--service-token-env",
        metavar="NAME",
        default=DEFAULT_SERVICE_TOKEN_ENV,
        dest="service_token_env",
        help=f"读取内部服务令牌的环境变量名，默认 {DEFAULT_SERVICE_TOKEN_ENV}",
    )
    return parser


def _run_canonicalize(input_path: Path, output_path: Path | None) -> int:
    data = canonicalize_path(input_path)
    if output_path is None:
        _write_stdout(data)
    else:
        output_path.write_bytes(data)
    return EXIT_OK


def _run_fingerprint(input_path: Path, json_output: bool) -> int:
    cases = load_dataset(input_path)
    data = canonicalize_cases(cases)
    sha256 = digest(data)
    if json_output:
        payload = {"bytes": len(data), "cases": len(cases), "sha256": sha256}
        line = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        _write_stdout(line.encode("utf-8"))
    else:
        print(f"sha256={sha256} cases={len(cases)}")
    return EXIT_OK


def _run_verify(input_path: Path, expected_digest: str) -> int:
    expected = expected_digest.strip().lower()
    cases = load_dataset(input_path)
    data = canonicalize_cases(cases)
    actual = digest(data)
    if actual != expected:
        print("sha256 mismatch", file=sys.stderr)
        print(f"expected sha256={expected}", file=sys.stderr)
        print(f"actual   sha256={actual}", file=sys.stderr)
        return EXIT_DIGEST_MISMATCH
    print(f"OK {actual}")
    return EXIT_OK


def _run_rerank(args: argparse.Namespace) -> int:
    top_k = args.top_k
    runs = args.runs
    warmup = args.warmup
    if not isinstance(top_k, int) or not 1 <= top_k <= 50:
        raise DatasetError("--top-k 必须在 1–50 之间")
    if not isinstance(runs, int) or runs < 1:
        raise DatasetError("--runs 必须 >= 1")
    if not isinstance(warmup, int) or warmup < 0 or warmup >= runs:
        raise DatasetError("--warmup 必须 >=0 且小于 --runs")
    deadline_ms = args.deadline_ms
    if not isinstance(deadline_ms, int) or not 1 <= deadline_ms <= 30000:
        raise DatasetError("--deadline-ms 必须在 1–30000 之间")
    candidate_target = args.candidate_target
    if candidate_target is not None and (
        not isinstance(candidate_target, int) or not 1 <= candidate_target <= 50
    ):
        raise DatasetError("--candidate-target 必须在 1–50 之间")

    cases = load_rerank_set(args.input)
    if candidate_target is not None:
        cases = [pad_candidates(case, candidate_target) for case in cases]
    scorer = _make_scorer(args)
    try:
        report = evaluate(
            cases,
            scorer,
            top_k=top_k,
            runs=runs if args.measure_latency else 0,
            warmup=warmup if args.measure_latency else 0,
            candidate_target=candidate_target,
        )
    finally:
        scorer.close()

    document = seal_report(report_document(report))
    payload = (
        canonicalize_document(document) if args.json_output else _summary_text(report, document)
    )
    output_path: Path | None = args.output
    if output_path is None:
        _write_stdout(payload)
    else:
        output_path.write_bytes(payload)
    return EXIT_OK


def _make_scorer(args: argparse.Namespace) -> Scorer:
    """按 ``--scorer`` 构造打分器；单测通过覆盖 :func:`_make_worker_scorer` 注入 MockTransport。"""

    if args.scorer == "worker":
        return _make_worker_scorer(args)
    return HeuristicScorer(top_k=args.top_k)


def _make_worker_scorer(args: argparse.Namespace) -> WorkerScorer:
    worker_url = args.worker_url
    if not isinstance(worker_url, str) or not worker_url.strip():
        raise DatasetError("--scorer worker 必须提供 --worker-url")
    token_env = args.service_token_env
    if not isinstance(token_env, str) or not token_env:
        raise DatasetError("--service-token-env 不能为空")
    return WorkerScorer(
        worker_url,
        top_k=args.top_k,
        deadline_ms=args.deadline_ms,
        token_env=token_env,
    )


def _run_decide(args: argparse.Namespace) -> int:
    cases = decide_eval.load_decide_set(args.input)
    scorer = _make_decide_scorer(args)
    try:
        report = decide_eval.evaluate(cases, scorer)
    finally:
        scorer.close()

    document = decide_eval.seal_report(decide_eval.report_document(report))
    payload = (
        canonicalize_document(document)
        if args.json_output
        else _decide_summary_text(report, document)
    )
    output_path: Path | None = args.output
    if output_path is None:
        _write_stdout(payload)
    else:
        output_path.write_bytes(payload)
    return EXIT_OK


def _make_decide_scorer(args: argparse.Namespace) -> decide_eval.Decider:
    """按 ``--scorer`` 构造打分器。

    单测通过覆盖 ``_make_decide_worker_scorer`` 注入 ``httpx.MockTransport``。
    """

    if args.scorer == "worker":
        return _make_decide_worker_scorer(args)
    return decide_eval.ReplayScorer()


def _make_decide_worker_scorer(args: argparse.Namespace) -> decide_eval.WorkerScorer:
    worker_url = args.worker_url
    if not isinstance(worker_url, str) or not worker_url.strip():
        raise DatasetError("--scorer worker 必须提供 --worker-url")
    token_env = args.service_token_env
    if not isinstance(token_env, str) or not token_env:
        raise DatasetError("--service-token-env 不能为空")
    return decide_eval.WorkerScorer(worker_url, token_env=token_env)


def _decide_summary_text(
    report: decide_eval.DecideEvalReport, document: Mapping[str, Any]
) -> bytes:
    """人类可读摘要；不参与 canonical 口径，但同样不含 case 正文。"""

    lines = [
        f"scorer={report.scorer} cases={len(report.cases)}",
        f"decisionAgreement={report.decision_agreement:.6f} "
        f"jsonValidityRate={report.json_validity:.6f}",
        f"invalidToolCalls={report.invalid_tool_calls} "
        f"schemaViolations={report.schema_violations} confidence={report.confidence}",
        f"evidence={report.evidence}",
    ]
    for case, outcome in report.cases:
        expected_tool = "" if case.expected_tool_name is None else f"/{case.expected_tool_name}"
        if outcome.valid and outcome.decision is not None:
            actual = outcome.decision.value
            actual_tool = "" if outcome.tool_name is None else f"/{outcome.tool_name}"
        else:
            actual = "invalid"
            actual_tool = ""
        lines.append(
            f"case {case.id}: expected={case.expected_decision.value}{expected_tool} "
            f"actual={actual}{actual_tool} "
            f"agreement={'true' if decide_eval.agrees(case, outcome) else 'false'}"
        )
    lines.append(f"sha256={document['sha256']}")
    return ("\n".join(lines) + "\n").encode("utf-8")


def _summary_text(report: EvalReport, document: Mapping[str, Any]) -> bytes:
    """人类可读摘要；不参与 canonical 口径，但同样不含路径等易变字段。"""

    ndcg_label = f"ndcg@{report.top_k}"
    lines = [
        f"scorer={report.scorer} topK={report.top_k} cases={len(report.cases)}",
        f"{ndcg_label}={report.ndcg:.6f} supportRate={report.support:.6f}",
    ]
    if report.latency.measured and report.latency.p95_ms is not None:
        lines.append(
            f"latency: p95Ms={report.latency.p95_ms:.3f} samples={report.latency.samples} "
            f"runs={report.latency.runs} warmup={report.latency.warmup}"
        )
    else:
        lines.append("latency: not measured（加 --measure-latency 开启 20×2 采样）")
    lines.extend(_latency_lines(report))
    if report.candidate_target is not None:
        lines.append(
            f"candidateTarget={report.candidate_target}"
            "（合成候选补齐；NDCG/support 与原始候选数不可比）"
        )
    for result in report.cases:
        lines.append(
            f"case {result.id}: ndcg={result.ndcg:.6f} support={result.support:.6f}"
            f" ranking={','.join(result.ranking)}"
        )
    lines.append(f"sha256={document['sha256']}")
    return ("\n".join(lines) + "\n").encode("utf-8")


def _latency_lines(report: EvalReport) -> list[str]:
    """W3.1 冻结 §1.5 的三行延迟摘要：overhead / model / total（P95 + P99）。"""

    latency = report.latency
    return [
        (
            f"overhead: p95={_format_ms(latency.overhead_p95_ms)} "
            f"p99={_format_ms(latency.overhead_p99_ms)}"
            "（门禁只看 overhead：P95<=800ms，P99<=1500ms 为工程护栏）"
        ),
        (
            f"model: p95={_format_ms(latency.model_p95_ms)} "
            f"p99={_format_ms(latency.model_p99_ms)}"
            f"（仅观测不进门禁；known={latency.model_known_samples}/{latency.samples}）"
        ),
        (
            f"total: p95={_format_ms(latency.p95_ms)} p99={_format_ms(latency.p99_ms)}"
            f"（仅观测不进门禁；samples={latency.samples}"
            f" confidence={latency.confidence}）"
        ),
    ]


def _format_ms(value: float | None) -> str:
    """分位数格式化：未知统一写 ``UNKNOWN``（不写 0，避免与真实 0ms 混淆）。"""

    return "UNKNOWN" if value is None else f"{value:.3f}ms"


def _write_stdout(data: bytes) -> None:
    """把字节精确写到 stdout（不追加换行）。

    优先写 ``sys.stdout.buffer``：Windows 控制台的文本层默认是 GBK/CP1252，直接写文本
    会把 canonical JSON 里的中文压成别的编码，破坏跨语言字节一致性。
    """

    if isinstance(sys.stdout, io.TextIOWrapper):
        sys.stdout.buffer.write(data)
        sys.stdout.flush()
        return
    sys.stdout.write(data.decode("utf-8"))
    sys.stdout.flush()


if __name__ == "__main__":
    raise SystemExit(main())
