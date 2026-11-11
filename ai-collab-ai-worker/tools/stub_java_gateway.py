"""本地 stub Java 内部模型网关（仅测试；不打包进 wheel / 生产镜像）。

口径来源：``docs/archive/handoffs/w3-final-freeze-20261112.md`` §1.6。

- 只实现一个端点：``POST /internal/ai/v1/model-calls``，固定返回

  ```json
  {"schemaVersion":1,"content":"<固定 JSON>",
   "usage":{"inputTokens":0,"outputTokens":0,"source":"ESTIMATE"},
   "modelAlias":"stub-rerank","latencyMs":0}
  ```

- 只绑 ``127.0.0.1``、**不鉴权**、不落盘、不打印请求体：请求体里有候选正文与 query，
  连访问日志都关掉，只在启动时打印一行端口信息；
- ``--scenario rerank|decide|plan|planning|memory``：本波只实现 rerank 的 content 形状，
  其余四个是占位 JSON 对象（``{"stub":true,"scenario":"..."}``），等对应波次再补；
- ``--delay-ms``：在响应前 ``sleep``，用于对照"人工注入的传输/排队延迟"；响应里的
  ``latencyMs`` 仍固定 ``0``（冻结要求），因此延迟只会进 worker 的 overhead，不会冒充
  ``T_model``；
- ``--port 0`` 表示让操作系统分配端口，启动行会打印实际端口。

**rerank content 的生成方式**（为什么按候选下标而不是候选 id）：worker 的打分提示词把
候选编号成 ``[0]``、``[1]``…，``cognition/rerank/parser.py`` 也按下标解析
（``{"0": 0.9, "1": 0.4}``），worker 再按输入顺序把下标映射回调用方的候选 id。因此 stub
从请求的 ``userPrompt`` 里用正则 ``^\\[(\\d+)\\] `` 提取全部下标，按下标生成分数对象；
提取不到（例如被别的调用方复用）时退化为固定的 8 条（0–7）。分数取固定循环表
``0.9, 0.8, …, 0.0``（按 ``index % 10``），确定性且全部落在 ``[0,1]``。

**覆盖候选数**：8 / 20 / 50 的候选数由 ``ai-eval rerank --candidate-target`` 补齐产生
（冻结 §1.6），stub 只按提示词里的下标数生成同样数量的分数，不需要预先知道候选数。

用法：

```bash
uv run python tools/stub_java_gateway.py --port 9199 --scenario rerank
# worker 侧：
#   AI_WORKER_JAVA_GATEWAY_URL=http://127.0.0.1:9199
```

不作为生产代码路径：``Dockerfile`` 只 COPY ``src/`` 与 ``prompts/``，wheel 只打包
``src/ai_collab_worker``，``tools/`` 不在两者之内。
"""

from __future__ import annotations

import argparse
import json
import re
import time
from collections.abc import Sequence
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Final, cast

#: Java 内部模型网关路径（与 ``clients/java_gateway.py`` 的常量一致）。
MODEL_CALL_PATH: Final[str] = "/internal/ai/v1/model-calls"

#: 健康检查（stub 专用；生产 Java 没有这个端点）。
HEALTH_PATH: Final[str] = "/healthz"

#: 契约版本（冻结 §1.4：只加字段，版本仍为 1）。
SCHEMA_VERSION: Final[int] = 1

#: 固定模型别名（stub 身份，绝不冒充真实模型名）。
STUB_MODEL_ALIAS: Final[str] = "stub-rerank"

#: 固定 usage：stub 不调 Provider，用量按 0 + ``ESTIMATE`` 明确标注（不冒充 PROVIDER）。
STUB_USAGE: Final[dict[str, object]] = {
    "inputTokens": 0,
    "outputTokens": 0,
    "source": "ESTIMATE",
}

#: 允许的 scenario 取值。
SCENARIOS: Final[tuple[str, ...]] = ("rerank", "decide", "plan", "planning", "memory")

#: 提示词里的候选下标标记：``[0] Title: ...`` / ``[1] Content: ...``。
CANDIDATE_INDEX = re.compile(r"^\[(\d+)\] ", re.MULTILINE)

#: 提取不到下标时按固定 8 条候选生成（与默认 topK 一致）。
DEFAULT_CANDIDATE_COUNT: Final[int] = 8

#: 分数循环表：确定性、全部在 ``[0,1]``、相邻下标不同分，便于发现"下标错位"。
RERANK_SCORE_CYCLE: Final[tuple[float, ...]] = (
    0.9,
    0.8,
    0.7,
    0.6,
    0.5,
    0.4,
    0.3,
    0.2,
    0.1,
    0.0,
)

#: 非 rerank 场景的占位 content（本波只实现 rerank；这些形状等对应波次再定）。
PLACEHOLDER_CONTENT: Final[dict[str, str]] = {
    scenario: json.dumps({"stub": True, "scenario": scenario}, separators=(",", ":"))
    for scenario in SCENARIOS
    if scenario != "rerank"
}


def rerank_content(user_prompt: str) -> str:
    """按提示词里的候选下标生成分数 JSON；覆盖全部下标、键为字符串下标。"""

    indices = sorted({int(match.group(1)) for match in CANDIDATE_INDEX.finditer(user_prompt)})
    if not indices:
        indices = list(range(DEFAULT_CANDIDATE_COUNT))
    scores = {str(index): RERANK_SCORE_CYCLE[index % len(RERANK_SCORE_CYCLE)] for index in indices}
    return json.dumps(scores, separators=(",", ":"))


def build_content(scenario: str, user_prompt: str) -> str:
    """按 scenario 返回固定 content；rerank 依赖 ``user_prompt`` 的候选下标。"""

    if scenario == "rerank":
        return rerank_content(user_prompt)
    return PLACEHOLDER_CONTENT[scenario]


class StubServer(ThreadingHTTPServer):
    """绑定 ``127.0.0.1`` 的 stub 网关；scenario 与延迟随 server 实例携带。"""

    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], *, scenario: str, delay_ms: int) -> None:
        super().__init__(address, _StubHandler)
        self.scenario = scenario
        self.delay_ms = delay_ms


class _StubHandler(BaseHTTPRequestHandler):
    """``/internal/ai/v1/model-calls`` 的固定响应实现。"""

    protocol_version = "HTTP/1.1"
    server_version = "StubJavaGateway/1.0"
    sys_version = ""

    def do_GET(self) -> None:
        if self.path == HEALTH_PATH:
            self._write_json(200, {"status": "ok"})
            return
        self._write_json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        if self.path != MODEL_CALL_PATH:
            # 先把请求体读掉再回 404：带未读体的连接在 Windows 上会被 RST，
            # 客户端可能在读响应前拿到 ConnectionAbortedError（仅测试工具的稳定性修复）。
            self._drain_body()
            self._write_json(404, {"error": "not_found"})
            return
        payload = self._read_json()
        if payload is None:
            return

        server = cast(StubServer, self.server)
        user_prompt = payload.get("userPrompt")
        content = build_content(
            server.scenario, user_prompt if isinstance(user_prompt, str) else ""
        )
        if server.delay_ms > 0:
            time.sleep(server.delay_ms / 1000.0)
        self._write_json(
            200,
            {
                "schemaVersion": SCHEMA_VERSION,
                "content": content,
                "usage": dict(STUB_USAGE),
                "modelAlias": STUB_MODEL_ALIAS,
                "latencyMs": 0,
            },
        )

    def log_message(self, _format: str, *_args: object) -> None:
        """关闭访问日志：请求体可能含候选正文/query，stub 一律不打印、不落盘。"""

    def _drain_body(self) -> None:
        """读掉并丢弃请求体；不合法长度按 0 处理（只为避免连接被 RST）。"""

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return
        if length > 0:
            self.rfile.read(length)

    def _read_json(self) -> dict[str, object] | None:
        """读请求体并解析成对象；不合法返回 400（不回显任何原文）。"""

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._write_json(400, {"error": "invalid_length"})
            return None
        raw = self.rfile.read(max(0, length))
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._write_json(400, {"error": "invalid_json"})
            return None
        if not isinstance(payload, dict):
            self._write_json(400, {"error": "invalid_payload"})
            return None
        return payload

    def _write_json(self, status: int, payload: dict[str, object]) -> None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def create_server(
    *, port: int, scenario: str = "rerank", delay_ms: int = 0, host: str = "127.0.0.1"
) -> StubServer:
    """构造（未启动的）stub server；``port=0`` 由操作系统分配。"""

    if scenario not in SCENARIOS:
        raise ValueError(f"未知 scenario：{scenario}")
    if delay_ms < 0:
        raise ValueError("delay_ms 不能为负数")
    return StubServer((host, port), scenario=scenario, delay_ms=delay_ms)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="stub_java_gateway",
        description="本地 stub Java 内部模型网关（仅测试，不鉴权，只绑 127.0.0.1）",
    )
    parser.add_argument("--port", type=int, default=9199, help="监听端口，默认 9199；0 表示随机")
    parser.add_argument(
        "--delay-ms", type=int, default=0, dest="delay_ms", help="响应前 sleep 的毫秒数，默认 0"
    )
    parser.add_argument(
        "--scenario",
        choices=SCENARIOS,
        default="rerank",
        help="固定 content 形状；本波只实现 rerank",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    server = create_server(
        port=args.port,
        scenario=args.scenario,
        delay_ms=args.delay_ms,
    )
    print(
        f"stub-java-gateway listening on http://127.0.0.1:{server.server_address[1]}"
        f" scenario={args.scenario} delay_ms={args.delay_ms}",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
