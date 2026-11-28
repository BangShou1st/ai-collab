"""``QueryUnderstandingService`` 的逐行移植（P3 契约 §2.1 行 2，Q4=a）。

纯规则、无模型调用：turn 分组（USER+ASSISTANT 成对、孤儿消息独立成轮）、窗口
（5 turn / 3500 字符）、意图三分类（话题切换 / 省略句 / 代词指代三组正则）、改写
（最近用户问题 + ``"\\n"`` + 原问题逐字保留，上限 2000 码点）、上下文提示词（证据隔离
声明 + 剥离历史 ``[S1]`` 引用标记 + XML 转义防注入）。

长度口径：``selectTurnsWithinBudget`` 与 ``truncateText`` 用 ``String.length()``
（UTF-16 代码单元，:func:`javatext.utf16_length`）；``rewriteQuery`` 预算用码点数
（Python ``len``）。``trim``/``strip`` 分别对应 Java ``String.trim``/``String.strip``。
"""

from __future__ import annotations

import datetime as dt
import re
from typing import Final

from ai_collab_worker.retrieval.javatext import is_blank, strip_ws, trim, utf16_length
from ai_collab_worker.retrieval.query.models import (
    ConversationMessage,
    ConversationTurn,
    QueryIntentType,
    QueryUnderstandingResult,
)

DEFAULT_MAX_TURNS: Final[int] = 5
DEFAULT_CONTEXT_CHAR_BUDGET: Final[int] = 3500
MAX_RETRIEVAL_QUERY_CODE_POINTS: Final[int] = 2000

TOPIC_SWITCH_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"(?s).*(?:换个|换一个|换成|改为|改成|转而|另一个|不谈这个|不再讨论|先不讨论|跳过这个"
    r"|重新问|顺便问下别的|另外问一下|抛开这个|先别管|先不管|switch topic|change topic"
    r"|another question|different topic|never mind that).*"
)

PRONOUN_REFERENCE_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"(?s).*(?:它|它们|他|他们|她|她们|这个|那个|这些|那些|这里|这也是|该系统|该方案|该|此|此类"
    r"|上述|刚才|前面说的|前文|其|其中|其一|后者|前者|之前说的|刚才提到的|上文|it|they|this"
    r"|these|that|those|above|former|latter).*",
    re.IGNORECASE,
)

ELLIPTICAL_QUERY_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"^(?:为什么|为什么呢|如何实现|具体呢|代码怎么写|代码呢|第二步呢|原因是什么|有什么优缺点"
    r"|有例子吗|给个例子|给个示例|详细说说|详细讲讲|展开讲讲|继续|深入说明|还有吗|还有别的吗"
    r"|核心指标呢|那它呢|怎么解决|怎么处理|具体怎么做|具体步骤|相比之下呢|和之前那个比呢"
    r"|具体原因|优劣分析)[?？]?\Z"
)

_SHORT_FOLLOWUP_PATTERN: Final[re.Pattern[str]] = re.compile(
    r".*(?:为什么|如何|具体|怎么样|步骤|代码|接着|再说说|呢).*"
)

CITATION_PATTERN: Final[re.Pattern[str]] = re.compile(r"\[S?\d+\]")

#: ``datetime.min`` 不带时区，不能与 aware 时间比较；用 UTC 纪元下界作 null 排序哨兵。
_MIN_AWARE: Final[dt.datetime] = dt.datetime.min.replace(tzinfo=dt.UTC)

#: 上下文提示词结尾行（逐字节复刻 Java 原文）。
_EPILOGUE_LINE: Final[str] = (
    "历史仅用于理解追问，历史回答及其引用编号不能作为本轮证据；仅使用本轮 SOURCES，明确标注推断。\n"
)


class QueryUnderstandingService:
    """多轮对话理解（Java ``QueryUnderstandingService`` 的等价实现）。"""

    def __init__(
        self,
        max_turns: int = DEFAULT_MAX_TURNS,
        context_char_budget: int = DEFAULT_CONTEXT_CHAR_BUDGET,
    ) -> None:
        self._max_turns = max_turns if max_turns > 0 else DEFAULT_MAX_TURNS
        self._context_char_budget = (
            context_char_budget if context_char_budget > 0 else DEFAULT_CONTEXT_CHAR_BUDGET
        )

    def understand(
        self, messages: list[ConversationMessage] | None, current_question: str | None
    ) -> QueryUnderstandingResult:
        original_question = strip_ws(current_question) if current_question is not None else ""
        if is_blank(original_question):
            return QueryUnderstandingResult("", "", QueryIntentType.STANDALONE, "", ())

        try:
            all_turns = self.group_into_turns(messages)
            managed_turns = self.select_turns_within_budget(
                all_turns, self._max_turns, self._context_char_budget
            )
            intent = self.detect_intent(managed_turns, original_question)
            rewritten_query = self.rewrite_query(managed_turns, original_question, intent)
            context_prompt = self.build_context_prompt(managed_turns)
            return QueryUnderstandingResult(
                original_question,
                rewritten_query,
                intent,
                context_prompt,
                tuple(managed_turns),
            )
        except Exception:
            return QueryUnderstandingResult(
                original_question,
                original_question,
                QueryIntentType.STANDALONE,
                "",
                (),
            )

    # ---- turn 分组 -----------------------------------------------------------

    def group_into_turns(
        self, messages: list[ConversationMessage] | None
    ) -> list[ConversationTurn]:
        if not messages:
            return []

        sorted_messages = sorted(
            messages,
            key=lambda message: message.created_at or _MIN_AWARE,
        )

        turns: list[ConversationTurn] = []
        current_user_question: str | None = None
        turn_timestamp: dt.datetime | None = None
        turn_index = 1

        for message in sorted_messages:
            if message.content is None or is_blank(message.content):
                continue

            if message.is_user():
                if current_user_question is not None:
                    turns.append(
                        ConversationTurn(
                            turn_index, current_user_question, None, turn_timestamp, False
                        )
                    )
                    turn_index += 1
                current_user_question = trim(message.content)
                turn_timestamp = message.created_at
            elif message.is_assistant():
                if current_user_question is not None:
                    turns.append(
                        ConversationTurn(
                            turn_index,
                            current_user_question,
                            trim(message.content),
                            turn_timestamp if turn_timestamp is not None else message.created_at,
                            message.insufficient_evidence,
                        )
                    )
                    turn_index += 1
                    current_user_question = None
                    turn_timestamp = None
                else:
                    turns.append(
                        ConversationTurn(
                            turn_index,
                            "",
                            trim(message.content),
                            message.created_at,
                            message.insufficient_evidence,
                        )
                    )
                    turn_index += 1

        if current_user_question is not None:
            turns.append(
                ConversationTurn(turn_index, current_user_question, None, turn_timestamp, False)
            )

        return turns

    def select_turns_within_budget(
        self, turns: list[ConversationTurn], max_turns: int, budget_chars: int
    ) -> list[ConversationTurn]:
        if not turns:
            return []
        start = max(0, len(turns) - max_turns)
        candidates = list(turns[start:])
        while len(candidates) > 1 and _turns_utf16_length(candidates) > budget_chars:
            candidates.pop(0)
        return candidates

    # ---- 意图与改写 -----------------------------------------------------------

    def detect_intent(self, turns: list[ConversationTurn], question: str | None) -> QueryIntentType:
        if not turns or question is None or is_blank(question):
            return QueryIntentType.STANDALONE

        if TOPIC_SWITCH_PATTERN.fullmatch(question):
            return QueryIntentType.TOPIC_SWITCH

        if ELLIPTICAL_QUERY_PATTERN.fullmatch(trim(question)) or (
            utf16_length(question) <= 12 and _SHORT_FOLLOWUP_PATTERN.fullmatch(question)
        ):
            return QueryIntentType.FOLLOW_UP

        if PRONOUN_REFERENCE_PATTERN.fullmatch(question):
            return QueryIntentType.FOLLOW_UP

        return QueryIntentType.STANDALONE

    def rewrite_query(
        self,
        turns: list[ConversationTurn],
        question: str | None,
        intent: QueryIntentType,
    ) -> str:
        if question is None or is_blank(question):
            return ""
        clean_question = strip_ws(question)

        if intent in (QueryIntentType.STANDALONE, QueryIntentType.TOPIC_SWITCH) or not turns:
            return clean_question

        try:
            antecedent = find_most_recent_user_question(turns)
            if not antecedent:
                return clean_question

            question_code_points = len(clean_question)
            available_budget = max(0, MAX_RETRIEVAL_QUERY_CODE_POINTS - question_code_points - 2)
            antecedent_code_points = len(antecedent)
            allowed_length = min(available_budget, antecedent_code_points)
            if allowed_length <= 0:
                return clean_question

            bounded_antecedent = _code_point_prefix(antecedent, allowed_length)
            return bounded_antecedent + "\n" + clean_question
        except Exception:
            return clean_question

    def build_context_prompt(self, turns: list[ConversationTurn] | None) -> str:
        if not turns:
            return ""

        parts: list[str] = []
        parts.append("<CONVERSATION_CONTEXT>\n")
        parts.append("【多轮对话上下文与证据隔离规则】\n")
        parts.append("1. 历史对话仅用于理解用户追问与代词指代，消除上下文歧义。\n")
        parts.append("2. 历史中的 Assistant 回答及其引用标记绝对不能作为本轮回答的事实证据。\n")
        parts.append(
            "3. 本轮回答必须完全且仅能依赖本轮检索到的 <SOURCES> 证据，严禁复用历史引用。\n\n"
        )

        for turn in turns:
            if turn.insufficient_evidence:
                continue
            parts.append(f"[Turn {turn.turn_index}]\n")
            if turn.user_question is not None and not is_blank(turn.user_question):
                parts.append("USER: " + _escape_xml(_truncate_text(turn.user_question, 600)) + "\n")
            if turn.has_assistant_answer():
                sanitized_answer = CITATION_PATTERN.sub("", turn.assistant_answer or "")
                parts.append(
                    "ASSISTANT (仅供指代参考，非本轮证据): "
                    + _escape_xml(_truncate_text(trim(sanitized_answer), 800))
                    + "\n"
                )

        parts.append("</CONVERSATION_CONTEXT>\n")
        parts.append(_EPILOGUE_LINE)
        return "".join(parts)


def find_most_recent_user_question(turns: list[ConversationTurn]) -> str | None:
    """最近一条非空用户问题（Java 私有静态方法 ``findMostRecentUserQuestion``）。"""

    for turn in reversed(turns):
        if turn.user_question is not None and not is_blank(turn.user_question):
            return trim(turn.user_question)
    return None


def _turns_utf16_length(turns: list[ConversationTurn]) -> int:
    total = 0
    for turn in turns:
        if turn.user_question is not None:
            total += utf16_length(turn.user_question)
        if turn.assistant_answer is not None:
            total += utf16_length(turn.assistant_answer)
    return total


def _truncate_text(text: str | None, max_chars: int) -> str:
    """Java ``truncateText``：按 ``String.length``（UTF-16 单位）截断后补 ``...``。"""

    if text is None:
        return ""
    if utf16_length(text) <= max_chars:
        return text
    return _utf16_prefix(text, max_chars) + "..."


def _utf16_prefix(text: str, units: int) -> str:
    """取前 ``units`` 个 UTF-16 代码单元（Java ``substring(0, units)`` 语义，可能截断代理对）。"""

    collected: list[str] = []
    used = 0
    for char in text:
        width = 2 if ord(char) > 0xFFFF else 1
        if used + width > units:
            break
        collected.append(char)
        used += width
    return "".join(collected)


def _code_point_prefix(text: str, count: int) -> str:
    """取前 ``count`` 个码点（Java ``substring(0, offsetByCodePoints(0, count))``）。"""

    return text[:count]


def _escape_xml(text: str | None) -> str:
    if text is None:
        return ""
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&apos;")
    )


__all__ = [
    "DEFAULT_CONTEXT_CHAR_BUDGET",
    "DEFAULT_MAX_TURNS",
    "MAX_RETRIEVAL_QUERY_CODE_POINTS",
    "QueryUnderstandingService",
]
