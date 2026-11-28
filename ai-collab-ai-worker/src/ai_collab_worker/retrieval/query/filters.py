"""``MetadataFilterParser`` 的逐行移植（P3 契约 §2.1 行 3）。

Java 正则 → Python ``re`` 的对齐要点（全部金标准钉住）：

- Java ``\\s``（未开 ``UNICODE_CHARACTER_CLASS``）只匹配 6 个 ASCII 空白，本模块一律用
  :data:`_S`（``[ \\t\\n\\x0b\\f\\r]``），不用 Python 的 Unicode ``\\s``；
- Java ``\\b`` 按 ASCII ``\\w`` 判界，中文邻接处行为与 Python 不同，显式用环视复刻；
- ``Matcher.find`` → ``finditer``；``Matcher.matches`` → ``fullmatch``；
- ``String.trim``（≤ U+0020）用 :func:`javatext.trim`，``String.strip`` 用
  :func:`javatext.strip_ws`；非法字段抛 :data:`IllegalArgumentException`（类名参与
  路由回退 reason，必须与 Java 简单类名逐字节一致）。
"""

from __future__ import annotations

import re
from typing import Final

from ai_collab_worker.retrieval.javatext import is_blank, lower, trim
from ai_collab_worker.retrieval.query.models import (
    FilterOperator,
    IllegalArgumentException,
    MetadataFilter,
    StructuredQuery,
)

#: Java ``\s`` 的 ASCII 复刻。
_S: Final[str] = r"[ \t\n\x0b\f\r]"

#: Java ``\b``（ASCII 词界）的环视复刻。
_B: Final[str] = r"(?<![a-zA-Z0-9_])"
_B_END: Final[str] = r"(?![a-zA-Z0-9_])"

FIELD_ALIASES: Final[dict[str, str]] = {
    "file": "originalFilename",
    "filename": "originalFilename",
    "originalfilename": "originalFilename",
    "name": "displayName",
    "doc": "displayName",
    "displayname": "displayName",
    "type": "mimeType",
    "mimetype": "mimeType",
    "format": "mimeType",
    "status": "status",
    "tag": "tags",
    "tags": "tags",
    "heading": "sectionHeading",
    "section": "sectionHeading",
    "sectionheading": "sectionHeading",
    "created": "createdAt",
    "createdat": "createdAt",
    "since": "createdAt",
    "after": "createdAt",
    "before": "createdAt",
    "updated": "updatedAt",
    "updatedat": "updatedAt",
}

PROTOCOL_PREFIXES: Final[frozenset[str]] = frozenset({"http", "https", "ftp", "urn", "mailto"})

EXPLICIT_KV_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"(?:^|" + _S + r"+)([a-zA-Z_][a-zA-Z0-9_]*)" + _S + r"*[:=]" + _S + r"*"
    r"(?:\"([^\"]+)\"|'([^']+)'|([^" + _S[1:-1] + r"]+))"
)

BOOK_TITLE_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"(?:在|关于|查阅|参考|限定在|来自)?"
    + _S
    + r"*《([^《》]{1,200})》"
    + _S
    + r"*(?:中|里|文档|文件)?(?:的)?"
)

FILENAME_MENTION_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"(?:文件名为|在文件|查看文件)"
    + _S
    + r"*[“\"']([^“”\"']{1,200})[”\"']"
    + _S
    + r"*(?:中|里)?(?:的)?"
)

HEADING_MENTION_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"(?:在|关于)?"
    + _S
    + r"*[“\"']([^“”\"']{1,200})[”\"']"
    + _S
    + r"*(?:章节|小节|部分|标题)"
    + _S
    + r"*(?:中|里)?(?:的)?"
)

MIME_TYPE_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"(?i)" + _B + r"(PDF|Markdown|Word|DOCX|TXT)" + _S + r"*(?:格式|文件|文档)?(?:的)?"
)

TIME_AFTER_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"([0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2}(?:T[0-9]{2}:[0-9]{2}:[0-9]{2})?)"
    + _S
    + r"*(?:之后|以后|起)(?:创建)?"
)

TIME_BEFORE_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"([0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2}(?:T[0-9]{2}:[0-9]{2}:[0-9]{2})?)"
    + _S
    + r"*(?:之前|以前|前)(?:创建)?"
)

_LEADING_PUNCT: Final[re.Pattern[str]] = re.compile(r"^[，,。；;：:" + _S + r"]+")
_TRAILING_PUNCT: Final[re.Pattern[str]] = re.compile(r"[，,。；;：:" + _S + r"]+\Z")
_MULTI_SPACE: Final[re.Pattern[str]] = re.compile(_S + "+")


def parse(raw_query: str | None) -> StructuredQuery:
    """解析自然语言查询，抽取受限元数据过滤并返回清洗后的语义查询。

    与 Java ``MetadataFilterParser.parse`` 逐行一致：显式键值 → 书名号 → 文件名提及 →
    章节提及 → MIME → 时间前/后；每段命中后从文本中删除对应 span。未知字段抛
    :data:`IllegalArgumentException`（路由层捕获后回退 HYBRID）。
    """

    if raw_query is None or is_blank(raw_query):
        return StructuredQuery("", ())

    query_text = raw_query
    filters: list[MetadataFilter] = []

    # 1. 显式 key:value 语法
    matched_spans: list[tuple[int, int]] = []
    for match in EXPLICIT_KV_PATTERN.finditer(query_text):
        key = lower(match.group(1))
        if key in PROTOCOL_PREFIXES:
            continue
        value = match.group(2)
        if value is None:
            value = match.group(3)
        if value is None:
            value = match.group(4)
        if value is None or is_blank(value):
            msg = f"Filter value cannot be empty for field '{key}'"
            raise IllegalArgumentException(msg)
        if key not in FIELD_ALIASES:
            # 报错文案不进契约面（路由回退 reason 只取异常类名），但异常类型必须同名。
            msg = f"Disallowed metadata filter field: '{key}'"
            raise IllegalArgumentException(msg)
        target_field = FIELD_ALIASES[key]
        operator = _determine_operator_for_key(key)
        normalized_value = _normalize_filter_value(target_field, value)
        filters.append(MetadataFilter(target_field, operator, normalized_value))
        matched_spans.append((match.start(), match.end()))
    query_text = _remove_spans(query_text, matched_spans)

    # 2. 书名号《...》
    matched_spans = []
    for match in BOOK_TITLE_PATTERN.finditer(query_text):
        title = trim(match.group(1))
        if title:
            filters.append(MetadataFilter.contains("originalFilename", title))
            matched_spans.append((match.start(), match.end()))
    query_text = _remove_spans(query_text, matched_spans)

    # 3. 显式文件名提及：文件名为 "..."
    matched_spans = []
    for match in FILENAME_MENTION_PATTERN.finditer(query_text):
        filename = trim(match.group(1))
        if filename:
            filters.append(MetadataFilter.contains("originalFilename", filename))
            matched_spans.append((match.start(), match.end()))
    query_text = _remove_spans(query_text, matched_spans)

    # 4. 章节提及："..." 章节
    matched_spans = []
    for match in HEADING_MENTION_PATTERN.finditer(query_text):
        heading = trim(match.group(1))
        if heading:
            filters.append(MetadataFilter.contains("sectionHeading", heading))
            matched_spans.append((match.start(), match.end()))
    query_text = _remove_spans(query_text, matched_spans)

    # 5. MIME 类型提及（PDF、Markdown 等）
    matched_spans = []
    for match in MIME_TYPE_PATTERN.finditer(query_text):
        mime_format = lower(match.group(1))
        mime_format = mime_format.upper()  # Java toUpperCase(Locale.ROOT)
        mime_type = _map_format_to_mime_type(mime_format)
        if mime_type is not None:
            filters.append(MetadataFilter.eq("mimeType", mime_type))
            matched_spans.append((match.start(), match.end()))
    query_text = _remove_spans(query_text, matched_spans)

    # 6. 时间提及
    matched_spans = []
    for match in TIME_AFTER_PATTERN.finditer(query_text):
        time_str = trim(match.group(1)).replace("/", "-")
        filters.append(MetadataFilter.gte("createdAt", time_str))
        matched_spans.append((match.start(), match.end()))
    query_text = _remove_spans(query_text, matched_spans)

    matched_spans = []
    for match in TIME_BEFORE_PATTERN.finditer(query_text):
        time_str = trim(match.group(1)).replace("/", "-")
        filters.append(MetadataFilter.lte("createdAt", time_str))
        matched_spans.append((match.start(), match.end()))
    query_text = _remove_spans(query_text, matched_spans)

    cleaned_semantic = _clean_semantic_query(query_text)
    if not cleaned_semantic and filters:
        cleaned_semantic = ""
    return StructuredQuery(cleaned_semantic, tuple(filters))


def _determine_operator_for_key(key: str) -> FilterOperator:
    if key in ("since", "after"):
        return FilterOperator.GTE
    if key == "before":
        return FilterOperator.LTE
    if key in ("file", "filename", "name", "doc", "heading", "section"):
        return FilterOperator.CONTAINS
    return FilterOperator.EQ


def _normalize_filter_value(field: str, value: str) -> str:
    if value is None:
        return ""
    trimmed = trim(value)
    quoted = len(trimmed) >= 2 and (
        (trimmed.startswith('"') and trimmed.endswith('"'))
        or (trimmed.startswith("'") and trimmed.endswith("'"))
    )
    if quoted:
        trimmed = trim(trimmed[1:-1])
    if field == "mimeType":
        mapped = _map_format_to_mime_type(trimmed.upper())
        if mapped is not None:
            return mapped
    return trimmed


def _map_format_to_mime_type(mime_format: str | None) -> str | None:
    if mime_format is None:
        return None
    mapping = {
        "PDF": "application/pdf",
        "MARKDOWN": "text/markdown",
        "MD": "text/markdown",
        "WORD": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "DOCX": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "TXT": "text/plain",
        "TEXT": "text/plain",
    }
    return mapping.get(mime_format.upper(), mime_format.lower())


def _remove_spans(text: str, spans: list[tuple[int, int]]) -> str:
    if not spans:
        return text
    result = text
    for start, end in sorted(spans, key=lambda span: span[0], reverse=True):
        result = result[:start] + result[end:]
    return result


def _clean_semantic_query(text: str | None) -> str:
    if text is None:
        return ""
    cleaned = _MULTI_SPACE.sub(" ", _TRAILING_PUNCT.sub("", _LEADING_PUNCT.sub("", text)))
    return trim(cleaned)


__all__ = ["parse"]
