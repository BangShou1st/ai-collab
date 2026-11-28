"""``KnowledgeOutputSafetyGuard`` 的逐字节移植（P3 契约 §2.1 行 10，Q9=a）。

以 k-gram（``K_GRAM`` code point）窗口把 System Prompt 切成指纹集合，再对回答滑窗
比对；命中即认为回答复述了系统提示词片段。问答流式链路在**发送前**做预检
（``contains_any_fingerprint`` + ``(K_GRAM-1)`` 尾码点缓冲），完成后再做全量终检
（``inspect``）——两个动作都在 worker 内完成，Java 中继不缓冲。

指纹算法逐字节钉在 ``p3_safety_golden.v1.jsonl``：指纹集合规模 + sorted-join sha256、
inspect/contains 行为样本。
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from ai_collab_worker.qa.constants import K_GRAM


@dataclass(frozen=True, slots=True)
class GuardResult:
    """Java ``KnowledgeOutputSafetyGuard.GuardResult``。"""

    leaked: bool
    matched_sample: str

    @staticmethod
    def clean() -> GuardResult:
        return GuardResult(False, "")


def fingerprint_set(system_prompt: str | None) -> frozenset[str]:
    """把 System Prompt 切成 k-gram 指纹集合（空提示词 → 空集合）。"""

    if not system_prompt or not system_prompt.strip():
        return frozenset()
    return frozenset(_fingerprints(system_prompt))


def contains_any_fingerprint(text: str | None, fingerprints: frozenset[str]) -> bool:
    """判定文本中是否包含指纹集合中的任一 k-gram（流式预检入口）。"""

    if not text or not text.strip() or not fingerprints:
        return False
    total = len(text)  # Python 字符串长度 = code point 数，与 Java codePointCount 一致
    return any(text[start : start + K_GRAM] in fingerprints for start in range(total - K_GRAM + 1))


def inspect(system_prompt: str | None, answer: str | None) -> GuardResult:
    """全量终检：返回首个命中的 16 码点窗口。"""

    if not system_prompt or not system_prompt.strip() or not answer or not answer.strip():
        return GuardResult.clean()
    fingerprints = fingerprint_set(system_prompt)
    if not fingerprints:
        return GuardResult.clean()
    total = len(answer)
    for start in range(total - K_GRAM + 1):
        window = answer[start : start + K_GRAM]
        if window in fingerprints:
            return GuardResult(True, window)
    return GuardResult.clean()


def canonical_fingerprint_digest(fingerprints: frozenset[str]) -> str:
    """指纹集合的 sorted-join sha256（与导出器同口径，供夹具对账）。"""

    joined = "\n".join(sorted(fingerprints))
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()


def _fingerprints(text: str) -> set[str]:
    total = len(text)
    return {text[start : start + K_GRAM] for start in range(total - K_GRAM + 1)}


__all__ = [
    "K_GRAM",
    "GuardResult",
    "canonical_fingerprint_digest",
    "contains_any_fingerprint",
    "fingerprint_set",
    "inspect",
]
