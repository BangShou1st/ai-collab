"""``ai-eval`` 命令行：canonical JSON、SHA-256 指纹、校验与 memory 离线评测。

``canonicalize`` / ``fingerprint`` / ``verify`` 的命令与退出码由
``ai-collab-ai-worker/README.md`` §7 固定：

- ``canonicalize INPUT [-o FILE]``：输出 canonical 字节（无尾随换行），给 ``-o`` 时写文件；
- ``fingerprint INPUT [--json]``：``sha256=<hex> cases=<n>``，``--json`` 输出单行 JSON；
- ``verify INPUT --sha256 <hex>``：匹配打印 ``OK <hex>``；不匹配退出码 3；
- ``memory SET [--json] [--output FILE] [--matcher exact|semantic]``：Memory 候选离线评测
  （**只有 replay**）：进程内复用 ``parse_candidates``，输出 precision/recall/
  candidateAgreement/duplicateRate/schemaPassRate/confidence；``--matcher exact`` 为默认旧
  行为，``semantic`` 显式启用 D7 词面代理并写 ``matchAudit``（precision/recall 仅诊断）；
- ``memory-arms SET [--json] [--output FILE] [--measure-latency --runs N]``：三组跨会话
  记忆对照骨架（D7-B）：A/B 本地镜像诊断，C DREAMING 未实现 → NOT_VERIFIED；门禁
  NOT_EVALUATED（用法见 ``tests/fixtures/memory_arms/``）；
- ``--evidence LABEL --quality-claim LABEL [--model NAME]``（memory，D10）：
  显式覆盖证据标签与质量声明；默认值保持冻结核不变，空标签退出码 2；真实模型运行必须显式
  传 ``LIVE_VERIFIED``；
- 输入不合法退出码 2（stderr 一行 ``invalid dataset: ...``），任何异常都不打印 traceback。
"""

from __future__ import annotations

import argparse
import io
import json
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any, Final

from ai_collab_worker.eval import memory as memory_eval
from ai_collab_worker.eval import memory_arms as memory_arms_eval
from ai_collab_worker.eval.canonical import (
    DatasetError,
    canonicalize_cases,
    canonicalize_document,
    canonicalize_path,
    digest,
    load_dataset,
)

#: 成功。
EXIT_OK: Final[int] = 0
#: 输入不合法、无法读取等可预期失败（CI 用来区分"数据坏了"与"指纹不对"）。
EXIT_INVALID_INPUT: Final[int] = 2
#: 指纹不匹配（``verify`` 专用）。
EXIT_DIGEST_MISMATCH: Final[int] = 3


def main(argv: Sequence[str] | None = None) -> int:
    """CLI 入口；返回进程退出码（console script 会交给 ``sys.exit``）。"""

    parser = _build_parser()
    args = parser.parse_args(argv)
    command: str | None = args.command
    if command is None:
        parser.print_usage(sys.stderr)
        print(
            "ai-eval: 需要子命令（canonicalize / fingerprint / verify / memory / memory-arms）",
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
        if command == "memory":
            return _run_memory(args)
        if command == "memory-arms":
            return _run_memory_arms(args)
        print(f"ai-eval: 未知子命令 {command!r}", file=sys.stderr)
        return EXIT_INVALID_INPUT
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
        description="冻结评测集的 canonical JSON、SHA-256 指纹与 memory 离线评测",
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

    memory = subparsers.add_parser(
        "memory",
        help="Memory 候选评测：precision / recall / 一致率 / 重复率（replay）",
    )
    memory.add_argument("input", metavar="SET", type=Path)
    memory.add_argument(
        "--json",
        action="store_true",
        dest="json_output",
        help="输出 canonical JSON 报告（含 sha256）而不是人类摘要",
    )
    memory.add_argument(
        "-o",
        "--output",
        metavar="FILE",
        type=Path,
        default=None,
        help="写入文件而不是 stdout（精确字节）",
    )
    memory.add_argument(
        "--matcher",
        choices=(memory_eval.MATCHER_EXACT, memory_eval.MATCHER_SEMANTIC),
        default=memory_eval.MATCHER_EXACT,
        help="标题匹配口径：exact=旧行为（默认）；semantic=D7 确定性词面代理（仅诊断）",
    )
    _add_evidence_options(memory)

    memory_arms = subparsers.add_parser(
        "memory-arms",
        help="三组跨会话记忆对照骨架：A/B 本地镜像诊断，C DREAMING NOT_VERIFIED",
    )
    memory_arms.add_argument("input", metavar="SET", type=Path)
    memory_arms.add_argument(
        "--json",
        action="store_true",
        dest="json_output",
        help="输出 canonical JSON 报告（含 sha256）而不是人类摘要",
    )
    memory_arms.add_argument(
        "-o",
        "--output",
        metavar="FILE",
        type=Path,
        default=None,
        help="写入文件而不是 stdout（精确字节）",
    )
    memory_arms.add_argument(
        "--measure-latency",
        action="store_true",
        dest="measure_latency",
        help="开启本地检索耗时采样（默认不测，保证报告逐字节稳定；结果仅本地、非生产）",
    )
    memory_arms.add_argument(
        "--runs",
        metavar="N",
        type=int,
        default=20,
        help="--measure-latency 时每条 case 的重复次数，默认 20",
    )
    return parser


def _add_evidence_options(parser: argparse.ArgumentParser) -> None:
    """D10：给 memory 增加显式证据标签参数（默认值保持不变）。"""

    parser.add_argument(
        "--evidence",
        metavar="LABEL",
        default=None,
        help="显式证据标签（如 LIVE_VERIFIED）；默认沿用冻结核标签",
    )
    parser.add_argument(
        "--quality-claim",
        metavar="LABEL",
        dest="quality_claim",
        default=None,
        help="显式质量声明（如 QUALITY_OK）；默认 NOT_VERIFIED",
    )
    parser.add_argument(
        "--model",
        metavar="NAME",
        default=None,
        help="写入报告的模型名（可选元数据）",
    )


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


def _run_memory(args: argparse.Namespace) -> int:
    cases = memory_eval.load_memory_set(args.input)
    report = memory_eval.evaluate(cases, matcher=args.matcher)
    document = memory_eval.seal_report(
        memory_eval.report_document(
            report,
            evidence=_cli_label(args.evidence, "--evidence"),
            quality_claim=_cli_label(args.quality_claim, "--quality-claim"),
            model=_cli_label(args.model, "--model"),
        )
    )
    payload = (
        canonicalize_document(document)
        if args.json_output
        else _memory_summary_text(report, document)
    )
    output_path: Path | None = args.output
    if output_path is None:
        _write_stdout(payload)
    else:
        output_path.write_bytes(payload)
    return EXIT_OK


def _run_memory_arms(args: argparse.Namespace) -> int:
    if not isinstance(args.runs, int) or not 1 <= args.runs <= 1000:
        raise DatasetError("--runs 必须在 1–1000 之间")
    dataset = memory_arms_eval.load_memory_arms(args.input)
    report = memory_arms_eval.evaluate_memory_arms(
        dataset,
        measure_latency=args.measure_latency,
        runs=args.runs,
    )
    document = memory_arms_eval.seal_report(memory_arms_eval.report_document(report))
    payload = (
        canonicalize_document(document)
        if args.json_output
        else _memory_arms_summary_text(report, document)
    )
    output_path: Path | None = args.output
    if output_path is None:
        _write_stdout(payload)
    else:
        output_path.write_bytes(payload)
    return EXIT_OK


def _cli_label(value: object, flag: str) -> str | None:
    """CLI 边界校验：空标签直接退出码 2，不进入报告。"""

    if value is None:
        return None
    if not isinstance(value, str):
        raise DatasetError(f"{flag} 必须是字符串")
    cleaned = value.strip()
    if not cleaned:
        raise DatasetError(f"{flag} 不能为空")
    return cleaned


def _memory_arms_summary_text(
    report: memory_arms_eval.MemoryArmsReport, document: Mapping[str, Any]
) -> bytes:
    """人类可读摘要；不打印记忆正文。"""

    lines = [
        f"memory-arms cases={report.case_count} memories={report.memory_count} "
        f"projects={report.project_count} sessions={report.session_count}",
    ]
    for arm in report.arms:
        if arm.arm == memory_arms_eval.ARM_DREAMING:
            lines.append(f"{arm.arm}: status=NOT_VERIFIED（未实现，不得用 stub 冒充）")
            continue
        lines.append(
            f"{arm.arm}: status={arm.status} recallAtK={arm.recall_at_k:.6f} "
            f"citationValidityRate={_format_optional_rate(arm.citation_validity_rate)} "
            f"conflictMisuseRate={_format_optional_rate(arm.conflict_misuse_rate)} "
            f"revocationLeaks={arm.revocation_leaks} "
            f"crossProjectLeaks={arm.cross_project_leaks}"
        )
    lines.append(f"gate={document['gate']['status']} reason={document['gate']['reason']}")
    lines.append(f"evidence={document['evidence']} qualityClaim={document['qualityClaim']}")
    lines.append(f"limitations: {document['limitations']}")
    lines.append(f"sha256={document['sha256']}")
    return ("\n".join(lines) + "\n").encode("utf-8")


def _format_optional_rate(value: float | str | int) -> str:
    return f"{value:.6f}" if isinstance(value, float) else str(value)


def _memory_summary_text(
    report: memory_eval.MemoryEvalReport, document: Mapping[str, Any]
) -> bytes:
    """人类可读摘要；不含候选正文与快照内容。"""

    aggregate = document["aggregate"]
    lines = [
        f"scorer=replay cases={len(report.cases)}",
        f"precision={report.precision:.6f} recall={report.recall:.6f} "
        f"candidateAgreement={report.candidate_agreement:.6f} "
        f"duplicateRate={report.duplicate_rate:.6f}",
        f"schemaPassRate={report.schema_pass_rate:.6f} "
        f"candidates={aggregate['candidates']} confidence={report.confidence}",
        f"invalidSource={aggregate['invalidSource']} invalidDedupe={aggregate['invalidDedupe']} "
        f"evidence={document['evidence']} qualityClaim={document['qualityClaim']}",
        f"limitations: {document['limitations']}",
    ]
    if document.get("matcher") == memory_eval.MATCHER_SEMANTIC:
        lines.insert(
            1,
            f"matcher=semantic audit={len(document.get('matchAudit', []))}"
            "（precision/recall 仅诊断，不设门禁）",
        )
    for case, outcome in report.cases:
        actual = "pass" if outcome.schema_pass else f"fail({outcome.reason})"
        lines.append(
            f"case {case.id}: judged={len(case.judgement)} actual={actual} "
            f"candidates={outcome.candidates} matched={outcome.matched} "
            f"duplicates={outcome.duplicates} invalidSource={outcome.invalid_source} "
            f"invalidDedupe={outcome.invalid_dedupe}"
        )
    lines.append(f"sha256={document['sha256']}")
    return ("\n".join(lines) + "\n").encode("utf-8")


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
