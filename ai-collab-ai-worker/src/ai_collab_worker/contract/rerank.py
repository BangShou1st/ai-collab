"""Rerank 端点契约（设计 §7.3，冻结见 W3 施工冻结文档 §1）。

请求固定为 camelCase：

```json
{"schemaVersion":1,"query":"…","topK":8,
 "candidates":[{"id":"c1","title":"…","text":"…"}],"deadlineMs":3000}
```

响应固定为：

```json
{"schemaVersion":1,"scores":[{"id":"c1","score":0.83}],
 "modelAlias":"rerank-default","usage":{"inputTokens":0,"outputTokens":0,"source":"PROVIDER"},
 "modelLatencyMs":1882}
```

约定：

- ``scores`` 必须覆盖**全部**输入 id，按输入顺序返回，``score ∈ [0,1]``；
- ``usage.source`` 只允许 ``PROVIDER`` / ``ESTIMATE`` / ``UNKNOWN``；未知时 token 数为
  ``null`` 而不是伪造 0（设计 §1.3 第 6 条"未知即未知"）；
- ``modelLatencyMs``（W3.1 冻结 §1.4）可选：Java 内部网关返回的 ``latencyMs``，``>=0``；
  未知时为 ``null``；``schemaVersion`` 仍为 1（只加字段）；
- ``deadlineMs`` 是端到端硬 deadline，默认 3000ms、上限 30000ms（rerank 专用，比通用
  请求的 10 分钟更短：单次重排不值得占用更长预算）；
- 模型漏掉的 id 由服务端用 0.0 补齐并记录 ``missing_ids``，但契约本身不区分
  "真实 0 分"与"缺失补 0"——这一点由响应外的指标/日志承载。
"""

from __future__ import annotations

from enum import StrEnum
from typing import Final, Literal, Self

from pydantic import Field, model_validator

from ai_collab_worker.contract.common import SCHEMA_VERSION, CommonRequest, ContractModel

#: rerank 请求允许的候选数上限（与设计 §7.3 一致）。
MAX_CANDIDATES: Final[int] = 50

#: rerank 请求候选数下限。
MIN_CANDIDATES: Final[int] = 1

#: 单条候选正文长度上限（字符/Unicode 码点）。
MAX_TEXT_CHARS: Final[int] = 2000

#: 标题长度上限（字符/Unicode 码点）。
MAX_TITLE_CHARS: Final[int] = 500

#: 查询长度上限。
MAX_QUERY_CHARS: Final[int] = 4000

#: 候选 id 长度上限。
MAX_ID_CHARS: Final[int] = 128

#: ``topK`` 默认值与区间。
DEFAULT_TOP_K: Final[int] = 8
MAX_TOP_K: Final[int] = 50

#: rerank 专用 deadline 默认值与区间（毫秒）。
DEFAULT_RERANK_DEADLINE_MS: Final[int] = 3000
MAX_RERANK_DEADLINE_MS: Final[int] = 30_000


class RerankCandidate(ContractModel):
    """单个待打分候选；``title`` 可空，``text`` 不接受空串。"""

    id: str = Field(
        min_length=1,
        max_length=MAX_ID_CHARS,
        description="调用方给定的候选标识；请求内必须唯一。",
    )
    title: str = Field(
        default="",
        max_length=MAX_TITLE_CHARS,
        description="候选标题；为空时打分 Prompt 不输出 Title 段。",
    )
    text: str = Field(
        min_length=1,
        max_length=MAX_TEXT_CHARS,
        description="候选正文；超过 2000 字符直接 422，不做静默截断。",
    )


class RerankRequest(CommonRequest):
    """``POST /v1/rag/rerank`` 请求。

    ``deadline_ms`` 覆写通用请求的 10 分钟上限：rerank 单次调用只允许 1–30000ms，
    默认 3000ms。语义校验（query 去空白非空、候选 id 唯一）放在模型内，保证
    直接构造模型与经由 HTTP 两条路径得到同样的 422。
    """

    query: str = Field(
        min_length=1,
        max_length=MAX_QUERY_CHARS,
        description="用户查询；去首尾空白后必须非空。",
    )
    top_k: int = Field(
        default=DEFAULT_TOP_K,
        ge=1,
        le=MAX_TOP_K,
        description="期望返回的 top-K；响应仍覆盖全部候选分数，由调用方截取。",
    )
    candidates: tuple[RerankCandidate, ...] = Field(
        min_length=MIN_CANDIDATES,
        max_length=MAX_CANDIDATES,
        description="待打分候选，1–50 条；id 请求内唯一。",
    )
    deadline_ms: int = Field(
        default=DEFAULT_RERANK_DEADLINE_MS,
        ge=1,
        le=MAX_RERANK_DEADLINE_MS,
        description="端到端硬 deadline（毫秒）；到点返回 DEADLINE_EXCEEDED，无半成品。",
    )

    @model_validator(mode="before")
    @classmethod
    def _reject_boolean_schema_version(cls, data: object) -> object:
        """``True == 1`` 在 Python 里成立；JSON 的 ``true`` 不得冒充 ``schemaVersion``。"""

        if isinstance(data, dict):
            for key in ("schemaVersion", "schema_version"):
                if isinstance(data.get(key), bool):
                    msg = "schemaVersion 必须为整数 1"
                    raise ValueError(msg)
        return data

    @model_validator(mode="after")
    def _check_semantics(self) -> Self:
        if not self.query.strip():
            msg = "query 去首尾空白后不能为空"
            raise ValueError(msg)
        ids = [candidate.id for candidate in self.candidates]
        if len(set(ids)) != len(ids):
            msg = "candidates.id 必须在请求内唯一"
            raise ValueError(msg)
        return self


class RerankUsageSource(StrEnum):
    """usage 来源：Provider 真值、本地估算、未知。"""

    PROVIDER = "PROVIDER"
    ESTIMATE = "ESTIMATE"
    UNKNOWN = "UNKNOWN"


class RerankScore(ContractModel):
    """单个候选的分数；有限且 ``0 <= score <= 1``。"""

    id: str = Field(min_length=1, max_length=MAX_ID_CHARS)
    score: float = Field(ge=0.0, le=1.0, allow_inf_nan=False)


class RerankUsage(ContractModel):
    """模型用量。

    ``source=UNKNOWN`` 时 ``input_tokens``/``output_tokens`` 保持 ``None``：不填 0
    冒充真实用量（设计 §1.3 第 6 条）。
    """

    input_tokens: int | None = Field(default=None, ge=0)
    output_tokens: int | None = Field(default=None, ge=0)
    source: RerankUsageSource = RerankUsageSource.UNKNOWN


class RerankResponse(ContractModel):
    """``POST /v1/rag/rerank`` 响应；``schemaVersion`` 固定 1（只加字段）。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    scores: tuple[RerankScore, ...] = Field(
        min_length=1,
        max_length=MAX_CANDIDATES,
        description="按输入顺序覆盖全部候选 id 的分数。",
    )
    model_alias: str = Field(min_length=1, max_length=128)
    usage: RerankUsage = Field(default_factory=RerankUsage)
    model_latency_ms: int | None = Field(
        default=None,
        ge=0,
        description=(
            "Java 内部网关返回的模型调用耗时（毫秒，T_model 的唯一来源）；"
            "未知时为 null，不填 0 冒充。"
        ),
    )

    @model_validator(mode="after")
    def _check_unique_ids(self) -> Self:
        ids = [score.id for score in self.scores]
        if len(set(ids)) != len(ids):
            msg = "scores.id 不得重复"
            raise ValueError(msg)
        return self


__all__ = [
    "DEFAULT_RERANK_DEADLINE_MS",
    "DEFAULT_TOP_K",
    "MAX_CANDIDATES",
    "MAX_ID_CHARS",
    "MAX_QUERY_CHARS",
    "MAX_RERANK_DEADLINE_MS",
    "MAX_TEXT_CHARS",
    "MAX_TITLE_CHARS",
    "MAX_TOP_K",
    "MIN_CANDIDATES",
    "RerankCandidate",
    "RerankRequest",
    "RerankResponse",
    "RerankScore",
    "RerankUsage",
    "RerankUsageSource",
]
