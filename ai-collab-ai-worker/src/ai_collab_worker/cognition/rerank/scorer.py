"""把解析出的 ``{index: score}`` 映射为输入 id，并显式记录缺失分数。

三条硬规则（冻结文档 §1）：

1. **不得伪造**：模型漏掉的 id 用 0.0 补齐，但必须出现在 ``missing_ids`` 里，供端点
   记 ``partial`` 指标与日志；"真实 0 分"和"缺失补 0"在响应里无法区分，只能靠这里。
2. **夹取到 [0,1]**：超出区间的有限值夹到边界，视为有效分数（不是缺失）。
3. **非有限数丢弃**：``NaN``/``±Inf`` 视为不可用分数，按缺失处理。

输出顺序严格等于输入顺序，``scores`` 覆盖全部输入 id。
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Final

from ai_collab_worker.contract.rerank import RerankCandidate, RerankScore

#: 缺失分数的补齐值（与 Java ``scores.getOrDefault(i, 0.0)`` 一致）。
MISSING_SCORE: Final[float] = 0.0


@dataclass(frozen=True)
class RerankScoring:
    """一次映射的结果：全量分数 + 缺失 id（输入顺序）。"""

    scores: tuple[RerankScore, ...]
    missing_ids: tuple[str, ...]

    @property
    def is_partial(self) -> bool:
        """是否发生过"模型漏 id / 非有限数"补齐。"""

        return bool(self.missing_ids)


def clamp_score(value: float) -> float:
    """把有限值夹到 ``[0,1]``；调用方必须先排除非有限数。"""

    return max(0.0, min(1.0, value))


def score_candidates(
    candidates: Sequence[RerankCandidate],
    raw_scores: Mapping[int, float],
) -> RerankScoring:
    """按输入顺序生成全量分数；缺失/非有限数补 0.0 并记录 id。"""

    scores: list[RerankScore] = []
    missing_ids: list[str] = []
    for index, candidate in enumerate(candidates):
        value = raw_scores.get(index)
        if value is None or not math.isfinite(value):
            missing_ids.append(candidate.id)
            scores.append(RerankScore(id=candidate.id, score=MISSING_SCORE))
            continue
        scores.append(RerankScore(id=candidate.id, score=clamp_score(value)))
    return RerankScoring(scores=tuple(scores), missing_ids=tuple(missing_ids))


__all__ = ["MISSING_SCORE", "RerankScoring", "clamp_score", "score_candidates"]
