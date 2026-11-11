"""``ai-eval`` 命令行：canonical JSON、SHA-256 指纹与校验。

命令与退出码由 ``ai-collab-ai-worker/README.md`` §7 固定：

- ``canonicalize INPUT [-o FILE]``：输出 canonical 字节（无尾随换行），给 ``-o`` 时写文件；
- ``fingerprint INPUT [--json]``：``sha256=<hex> cases=<n>``，``--json`` 输出单行 JSON；
- ``verify INPUT --sha256 <hex>``：匹配打印 ``OK <hex>``；不匹配退出码 3；
- 输入不合法退出码 2（stderr 一行 ``invalid dataset: ...``），任何异常都不打印 traceback。
"""

from __future__ import annotations

import argparse
import io
import json
import sys
from collections.abc import Sequence
from pathlib import Path
from typing import Final

from ai_collab_worker.eval.canonical import (
    DatasetError,
    canonicalize_cases,
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
        print("ai-eval: 需要子命令（canonicalize / fingerprint / verify）", file=sys.stderr)
        return EXIT_INVALID_INPUT

    try:
        if command == "canonicalize":
            return _run_canonicalize(args.input, args.output)
        if command == "fingerprint":
            return _run_fingerprint(args.input, args.json_output)
        if command == "verify":
            return _run_verify(args.input, args.expected_digest)
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
        description="冻结评测集的 canonical JSON 与 SHA-256 指纹",
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
