"""解析模型返回的打分 JSON（设计 §7.3 / 冻结文档 §1）。

兼容两形态：

- object：``{"0": 0.95, "1": "0.40"}``（键为候选下标，值可为数字或字符串数字）；
- array：``[{"index":0,"score":0.9}, {"index":"1","score":"0.2"}]`` 或 ``[0.9, "0.1"]``
  （对象缺 ``index`` 时用数组位置，数字项直接按下标）。

同时兼容 Java ``NeuralModelReranker.parseScores`` 的围栏剥离顺序：先剥 `` ```json ``，
再剥裸 `` ``` ``，最后剥末尾 `` ``` ``。**非法或空输出一律 ``SCHEMA_VIOLATION``(422)**；
错误信封里只放固定短语，绝不回显模型原文（可能含候选正文或注入内容）。

解析层不负责夹取区间，也不丢弃非有限数：那是 :mod:`ai_collab_worker.cognition.rerank.scorer`
的职责（"真实 0 分"与"缺失/不可用"必须区分记录）。越界下标原样保留，映射到输入 id 时
由 scorer 忽略。
"""

from __future__ import annotations

import json
import math
from typing import Final

from ai_collab_worker.contract.common import AppError, ErrorCode

#: 对外可见的失败原因（固定短语，不含模型输出）。
REASON_EMPTY_OUTPUT: Final[str] = "模型输出为空"
REASON_NOT_JSON: Final[str] = "模型输出不是合法 JSON"
REASON_UNSUPPORTED_SHAPE: Final[str] = "模型输出不是对象或数组"
REASON_NO_SCORES: Final[str] = "模型输出不含可解析的分数"


def parse_scores(content: str) -> dict[int, float]:
    """把模型输出解析成 ``{index: score}``；解析不出任何分数时抛 ``SCHEMA_VIOLATION``。"""

    cleaned = _strip_code_fence(content)
    if not cleaned:
        raise _schema_violation(REASON_EMPTY_OUTPUT)
    try:
        root: object = json.loads(cleaned)
    except json.JSONDecodeError as exc:
        raise _schema_violation(REASON_NOT_JSON) from exc

    scores: dict[int, float] = {}
    if isinstance(root, dict):
        _collect_object(root, scores)
    elif isinstance(root, list):
        _collect_array(root, scores)
    else:
        raise _schema_violation(REASON_UNSUPPORTED_SHAPE)

    if not scores:
        raise _schema_violation(REASON_NO_SCORES)
    return scores


def _strip_code_fence(content: str) -> str:
    """按 Java 的顺序去掉 ```json / ``` 围栏，返回去空白后的文本。"""

    cleaned = content.strip()
    if cleaned.startswith("```json"):
        cleaned = cleaned[len("```json") :]
    elif cleaned.startswith("```"):
        cleaned = cleaned[len("```") :]
    if cleaned.endswith("```"):
        cleaned = cleaned[: -len("```")]
    return cleaned.strip()


def _collect_object(root: dict[object, object], scores: dict[int, float]) -> None:
    for raw_key, raw_value in root.items():
        if not isinstance(raw_key, str):
            continue
        index = _parse_index(raw_key)
        value = _parse_score(raw_value)
        if index is not None and value is not None:
            scores[index] = value


def _collect_array(root: list[object], scores: dict[int, float]) -> None:
    for position, item in enumerate(root):
        if isinstance(item, dict):
            raw_index = item.get("index", position)
            index = _parse_index(raw_index, allow_raw_int=True)
            value = _parse_score(item.get("score"))
            if index is not None and value is not None:
                scores[index] = value
        else:
            value = _parse_score(item)
            if value is not None:
                scores[position] = value


def _parse_index(raw: object, *, allow_raw_int: bool = False) -> int | None:
    """把下标解析成非负之外的整数；非法返回 ``None``（由调用方忽略该项）。"""

    if allow_raw_int and isinstance(raw, int) and not isinstance(raw, bool):
        return raw
    if isinstance(raw, str):
        try:
            return int(raw.strip())
        except ValueError:
            return None
    if allow_raw_int and isinstance(raw, float) and math.isfinite(raw) and raw.is_integer():
        return int(raw)
    return None


def _parse_score(raw: object) -> float | None:
    """数字或字符串数字 → float；布尔与其它类型视为不可解析。"""

    if isinstance(raw, bool):
        return None
    if isinstance(raw, int | float):
        return float(raw)
    if isinstance(raw, str):
        try:
            return float(raw.strip())
        except ValueError:
            return None
    return None


def _schema_violation(reason: str) -> AppError:
    """固定的 422 错误；``details`` 只含 reason 短语。"""

    return AppError(
        ErrorCode.SCHEMA_VIOLATION,
        "模型打分输出不符合契约",
        status_code=422,
        details={"reason": reason},
    )


__all__ = ["parse_scores"]
