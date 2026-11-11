"""Rerank stub 基准：本机 worker + 本地 stub Java 网关，按候选数分档测 overhead。

口径来源：``docs/archive/handoffs/w3-final-freeze-20261112.md`` §1.6 / §1.7（测量 A）。

做三件事：

1. 起两个子进程：``tools/stub_java_gateway.py``（固定 ``latencyMs=0``，即 ``T_model=0``）
   与本机 worker（``uvicorn ai_collab_worker.main:app``，``AI_WORKER_JAVA_GATEWAY_URL``
   指向 stub）；
2. 对每个候选数档位调用
   ``python -m ai_collab_worker.eval.cli rerank <冻结集> --scorer worker --worker-url … \
   --candidate-target N --measure-latency --runs 34 --warmup 0 --json``，
   解析 ``aggregate`` 得到 P50/P95/P99、overhead P95/P99、samples、confidence；
3. 断言 ``overheadP95Ms <= 800`` 且 ``overheadP99Ms <= 1500``（P99 是工程护栏），
   失败退出码 1，并把 ``passed=false`` 写进 JSON 证据。

**为什么 ``--warmup 0``**：冻结 §1.7 要求每档 ≥500 样本（15 case × 34 runs = 510，
confidence=HIGH）。CLI 默认 ``--warmup 2`` 会让有效样本变成 15×32=480（MEDIUM），
与冻结的样本量口径不一致；这里显式关闭 warmup，让 34 次全部计入样本。
门禁数值本身**不可配置**（常量写死），本脚本不做任何放宽。

**证据文件**：JSON（含每档分位数与门禁结果），默认写到 ``ai-collab-ai-worker/target/``；
不含机器绝对路径、不含 query/候选正文，只含档位、数字与固定说明。

用法：

```bash
cd ai-collab-ai-worker
uv run python tools/rerank_stub_bench.py \
  --output target/rerank-stub-bench.json
```

依赖：``uv sync`` 后的 venv（uvicorn、httpx 都在里面）；不需要任何真实模型、密钥或
Java 服务。脚本只绑 ``127.0.0.1``，不对外网发包。
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from collections.abc import Sequence
from pathlib import Path
from typing import IO, Any, Final

#: worker 工程根（``ai-collab-ai-worker/``）。
PROJECT_ROOT: Final[Path] = Path(__file__).resolve().parents[1]

#: 工具目录（stub 网关与本脚本同目录）。
TOOLS_DIR: Final[Path] = Path(__file__).resolve().parent

#: 冻结 rerank 集（15 条中文合成 case）。
DEFAULT_SET: Final[Path] = (
    PROJECT_ROOT / "tests" / "fixtures" / "rerank_set" / "rerank_baseline.v1.jsonl"
)

#: 冻结 §1.6：每档 ``--runs 34``（15 × 34 = 510 样本）。
DEFAULT_RUNS: Final[int] = 34

#: 冻结 §1.7 的候选数档位。
DEFAULT_TARGETS: Final[tuple[int, ...]] = (8, 20, 50)

#: 门禁（冻结 §1.7 / §1.8）：**写死，不提供命令行开关**。
GATE_OVERHEAD_P95_MS: Final[float] = 800.0
GATE_OVERHEAD_P99_MS: Final[float] = 1500.0

#: stub 场景下 worker 的 logs 级别（降低噪音；不影响测量）。
WORKER_LOG_LEVEL: Final[str] = "warning"

#: 本地 stub 服务令牌：只用于本机回环，不是生产令牌，也不写任何文件。
BENCH_SERVICE_TOKEN: Final[str] = "stub-bench-token"

#: 子进程就绪/评测超时（秒）。
READY_TIMEOUT_SECONDS: Final[float] = 90.0
EVAL_TIMEOUT_SECONDS: Final[float] = 1800.0
STOP_TIMEOUT_SECONDS: Final[float] = 10.0

#: 证据文件默认位置（受控目录，日志/证据不落项目根）。
DEFAULT_OUTPUT: Final[Path] = PROJECT_ROOT / "target" / "rerank-stub-bench.json"

#: 证据里固定写明的说明（不含机器相关信息）。
EVIDENCE_NOTES: Final[tuple[str, ...]] = (
    "stub 网关固定 latencyMs=0，因此 T_model 已知且恒为 0，overhead == total",
    "候选由 --candidate-target 补齐（labels=0/supports=false），NDCG 与支持率不具可比性",
    "门禁只断言 overhead：P95<=800ms、P99<=1500ms；P99 为工程护栏",
    "测量 A 证据等级：TESTED（stub），不是真实模型环境的 LIVE_VERIFIED",
)


class BenchError(RuntimeError):
    """基准无法完成（子进程起不来、评测失败、aggregate 缺字段等）。"""


class _Child:
    """受管子进程：输出重定向到临时文件，失败时只回读尾部做诊断。"""

    def __init__(self, name: str, argv: Sequence[str], *, env: dict[str, str], cwd: Path) -> None:
        self.name = name
        # 临时文件必须活到子进程结束（作为它的 stdout/stderr 句柄），故不用 with。
        self._log: IO[bytes] = tempfile.TemporaryFile()  # noqa: SIM115
        self.process = subprocess.Popen(
            list(argv),
            cwd=str(cwd),
            env=env,
            stdout=self._log,
            stderr=subprocess.STDOUT,
        )

    def tail(self, limit: int = 4000) -> str:
        """读取输出尾部（最多 ``limit`` 字节），用于失败诊断；不打印正常流量。"""

        try:
            self._log.flush()
            self._log.seek(0)
            data = self._log.read()
        except (OSError, ValueError):
            return "<log unavailable>"
        text = data[-limit:].decode("utf-8", errors="replace")
        return text.strip() or "<no output>"

    def stop(self) -> None:
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=STOP_TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=STOP_TIMEOUT_SECONDS)
        self._log.close()


def _subprocess_env(extra: dict[str, str]) -> dict[str, str]:
    """构造子进程环境：清掉继承的 ``AI_WORKER_*``，只留本脚本显式设置的值。"""

    env = {
        key: value for key, value in os.environ.items() if not key.upper().startswith("AI_WORKER_")
    }
    env.update(extra)
    return env


def _free_port() -> int:
    """向操作系统要一个空闲的本地端口（关闭后立即复用，单机基准足够）。"""

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def _wait_ready(url: str, child: _Child) -> None:
    """轮询就绪端点；子进程提前退出时抛出带输出尾部的错误。"""

    deadline = time.monotonic() + READY_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        if child.process.poll() is not None:
            raise BenchError(f"{child.name} 提前退出：\n{child.tail()}")
        try:
            with urllib.request.urlopen(url, timeout=2.0) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            time.sleep(0.2)
    raise BenchError(f"{child.name} 在 {READY_TIMEOUT_SECONDS:.0f}s 内未就绪：\n{child.tail()}")


def _start_stub(child_ref: list[_Child], port: int) -> _Child:
    child = _Child(
        "stub 网关",
        [
            sys.executable,
            str(TOOLS_DIR / "stub_java_gateway.py"),
            "--port",
            str(port),
            "--scenario",
            "rerank",
        ],
        env=_subprocess_env({}),
        cwd=PROJECT_ROOT,
    )
    child_ref.append(child)
    _wait_ready(f"http://127.0.0.1:{port}/healthz", child)
    return child


def _start_worker(child_ref: list[_Child], *, worker_port: int, stub_port: int) -> _Child:
    env = _subprocess_env(
        {
            "AI_WORKER_PROMPTS_DIR": str(PROJECT_ROOT / "prompts"),
            "AI_WORKER_SERVICE_TOKEN": BENCH_SERVICE_TOKEN,
            "AI_WORKER_JAVA_GATEWAY_URL": f"http://127.0.0.1:{stub_port}",
            "AI_WORKER_LOG_LEVEL": WORKER_LOG_LEVEL,
            "AI_WORKER_PORT": str(worker_port),
        }
    )
    child = _Child(
        "worker",
        [
            sys.executable,
            "-m",
            "uvicorn",
            "ai_collab_worker.main:app",
            "--host",
            "127.0.0.1",
            "--port",
            str(worker_port),
            "--log-level",
            "warning",
        ],
        env=env,
        cwd=PROJECT_ROOT,
    )
    child_ref.append(child)
    _wait_ready(f"http://127.0.0.1:{worker_port}/readyz", child)
    return child


def _run_tier(
    *,
    set_path: Path,
    worker_port: int,
    target: int,
    runs: int,
) -> dict[str, Any]:
    """跑一档候选数的评测，返回证据条目（字段名与 CLI aggregate 对齐）。"""

    argv = [
        sys.executable,
        "-m",
        "ai_collab_worker.eval.cli",
        "rerank",
        str(set_path),
        "--scorer",
        "worker",
        "--worker-url",
        f"http://127.0.0.1:{worker_port}",
        "--candidate-target",
        str(target),
        "--measure-latency",
        "--runs",
        str(runs),
        "--warmup",
        "0",
        "--json",
    ]
    completed = subprocess.run(
        argv,
        cwd=str(PROJECT_ROOT),
        env=_subprocess_env({"AI_WORKER_SERVICE_TOKEN": BENCH_SERVICE_TOKEN}),
        capture_output=True,
        timeout=EVAL_TIMEOUT_SECONDS,
        check=False,
    )
    stdout = completed.stdout.decode("utf-8", errors="replace")
    if completed.returncode != 0:
        stderr = completed.stderr.decode("utf-8", errors="replace").strip()
        raise BenchError(f"candidateTarget={target} 评测失败（{completed.returncode}）：{stderr}")
    try:
        payload = json.loads(stdout)
    except json.JSONDecodeError as exc:
        raise BenchError(f"candidateTarget={target} 的评测输出不是 JSON：{exc}") from exc

    aggregate = payload.get("aggregate")
    if not isinstance(aggregate, dict):
        raise BenchError(f"candidateTarget={target} 的报告缺少 aggregate")
    return {
        "candidateTarget": target,
        "p50Ms": aggregate.get("totalP50Ms"),
        "p95Ms": aggregate.get("totalP95Ms"),
        "p99Ms": aggregate.get("totalP99Ms"),
        "overheadP95Ms": aggregate.get("overheadP95Ms"),
        "overheadP99Ms": aggregate.get("overheadP99Ms"),
        "modelP95Ms": aggregate.get("modelP95Ms"),
        "modelP99Ms": aggregate.get("modelP99Ms"),
        "samples": aggregate.get("samples"),
        "modelKnownSamples": aggregate.get("modelKnownSamples"),
        "confidence": aggregate.get("confidence"),
        "ndcgAtTopK": aggregate.get(f"ndcgAt{payload.get('topK', 8)}"),
        "supportRate": aggregate.get("supportRate"),
    }


def _is_number(value: object) -> bool:
    """JSON 数字判定：排除 ``bool``（``True`` 在 Python 里是 int，但门禁不接受）。"""

    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _tier_passes(tier: dict[str, Any]) -> bool:
    """门禁：overhead P95/P99 必须已知且不超上限（缺字段视为失败）。"""

    p95 = tier.get("overheadP95Ms")
    p99 = tier.get("overheadP99Ms")
    return (
        _is_number(p95)
        and _is_number(p99)
        and p95 <= GATE_OVERHEAD_P95_MS
        and p99 <= GATE_OVERHEAD_P99_MS
    )


def _format(value: object, unit: str = "ms") -> str:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return f"{value:.3f}{unit}"
    return "UNKNOWN"


def _write_evidence(document: dict[str, Any], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="rerank_stub_bench",
        description="rerank stub 基准（本机 worker + stub Java 网关，按候选数分档）",
    )
    parser.add_argument(
        "--set",
        type=Path,
        default=DEFAULT_SET,
        dest="set_path",
        help="冻结 rerank 集路径，默认 tests/fixtures/rerank_set/rerank_baseline.v1.jsonl",
    )
    parser.add_argument(
        "--runs",
        type=int,
        default=DEFAULT_RUNS,
        help=f"每条 case 的执行次数（--warmup 固定 0），默认 {DEFAULT_RUNS}",
    )
    parser.add_argument(
        "--targets",
        default=",".join(str(target) for target in DEFAULT_TARGETS),
        help="候选数档位，逗号分隔，默认 8,20,50",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help="JSON 证据输出路径，默认 ai-collab-ai-worker/target/rerank-stub-bench.json",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    set_path: Path = args.set_path
    children: list[_Child] = []
    try:
        targets = _parse_targets(args.targets)
        if args.runs < 1:
            raise BenchError("--runs 必须 >= 1")
        if not set_path.is_file():
            raise BenchError(f"冻结集不存在：{set_path}")

        stub_port = _free_port()
        worker_port = _free_port()
        while worker_port == stub_port:
            worker_port = _free_port()
        _start_stub(children, stub_port)
        _start_worker(children, worker_port=worker_port, stub_port=stub_port)

        tiers = [
            _run_tier(
                set_path=set_path,
                worker_port=worker_port,
                target=target,
                runs=args.runs,
            )
            for target in targets
        ]
    except BenchError as exc:
        print(f"bench failed: {exc}", file=sys.stderr)
        return 1
    except subprocess.TimeoutExpired as exc:
        print(f"bench failed: 评测超时：{exc}", file=sys.stderr)
        return 1
    finally:
        for child in reversed(children):
            child.stop()

    passed = all(_tier_passes(tier) for tier in tiers)
    document: dict[str, Any] = {
        "schemaVersion": 1,
        "benchmark": "rerank-stub-bench",
        "protocol": {
            "set": set_path.name,
            "runs": args.runs,
            "warmup": 0,
            "targets": targets,
        },
        "gate": {
            "metric": "overhead",
            "overheadP95MsMax": GATE_OVERHEAD_P95_MS,
            "overheadP99MsMax": GATE_OVERHEAD_P99_MS,
        },
        "notes": list(EVIDENCE_NOTES),
        "tiers": tiers,
        "passed": passed,
    }
    _write_evidence(document, args.output)

    for tier in tiers:
        status = "PASS" if _tier_passes(tier) else "NOT_MET"
        print(
            f"[{status}] candidateTarget={tier['candidateTarget']} "
            f"p50={_format(tier['p50Ms'])} p95={_format(tier['p95Ms'])} "
            f"p99={_format(tier['p99Ms'])} "
            f"overheadP95={_format(tier['overheadP95Ms'])} "
            f"overheadP99={_format(tier['overheadP99Ms'])} "
            f"samples={tier['samples']} confidence={tier['confidence']}"
        )
    print(f"evidence: {args.output}")
    if not passed:
        print("bench NOT_MET：overhead 门禁未通过（不改门禁数值）", file=sys.stderr)
        return 1
    print("bench PASS：overhead P95<=800ms 且 P99<=1500ms")
    return 0


def _parse_targets(raw: str) -> list[int]:
    targets: list[int] = []
    for piece in raw.split(","):
        piece = piece.strip()
        if not piece:
            continue
        try:
            value = int(piece)
        except ValueError as exc:
            raise BenchError(f"非法候选数档位：{piece!r}") from exc
        if not 1 <= value <= 50:
            raise BenchError(f"候选数档位必须在 1–50：{value}")
        targets.append(value)
    if not targets:
        raise BenchError("至少需要一个候选数档位")
    return targets


if __name__ == "__main__":
    raise SystemExit(main())
