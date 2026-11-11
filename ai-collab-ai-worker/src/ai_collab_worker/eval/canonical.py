"""Canonical JSON 编解码与 SHA-256 指纹。

口径的唯一来源是 ``ai-collab-ai-worker/README.md`` §7.2（Java 侧必须逐条对齐）：

1. 形状：``{"cases":[...],"schemaVersion":1}``，``cases`` 按 ``id`` 的 Unicode 码点升序；
2. 键序：所有对象键按 Unicode 码点升序（递归）；
3. 字符串：UTF-8 原样输出，只转义 ``"``、``\\`` 与 ``< 0x20`` 的控制字符
   （``\\b \\f \\n \\r \\t``，其余 ``\\u00xx`` 小写十六进制）；
4. 整数：十进制、无前导零，``-0`` 归一为 ``0``；
5. 小数：按十进制精确值解析（不是 binary64），输出无指数、至少一位小数；
6. 字面量：``true`` / ``false`` / ``null`` 小写，拒绝 ``NaN`` / ``Infinity``；
7. 空白：``,`` 与 ``:`` 分隔，无缩进、无前后空白、无尾随换行；
8. 摘要：canonical UTF-8 字节的 SHA-256，十六进制小写。

输入解析一律 fail-closed：UTF-8 无 BOM、拒绝重复键与非有限数、每个 case 必须是带
唯一非空字符串 ``id`` 的对象；任何不合法输入抛 :class:`DatasetError`。
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping, Sequence
from decimal import Decimal
from pathlib import Path
from types import MappingProxyType
from typing import Any, Final, NoReturn

from ai_collab_worker.contract.common import SCHEMA_VERSION

#: 冻结集必须是 UTF-8 无 BOM；BOM 会让"同一份文本"在跨语言侧出现字节差异。
_BOM: Final[bytes] = b"\xef\xbb\xbf"

#: 只接受显式后缀，避免把任意文件当数据集静默解析。
_SUPPORTED_SUFFIXES: Final[frozenset[str]] = frozenset({".jsonl", ".json"})

#: JSON 规定的最小转义集；非 ASCII 一律原样输出（README §7.2 第 3 条）。
_ESCAPES: Final[Mapping[str, str]] = MappingProxyType(
    {
        '"': '\\"',
        "\\": "\\\\",
        "\b": "\\b",
        "\f": "\\f",
        "\n": "\\n",
        "\r": "\\r",
        "\t": "\\t",
    }
)


class DatasetError(ValueError):
    """冻结评测集不合法（编码、JSON 语法、case 结构或值类型）。"""


class _DuplicateKeyError(ValueError):
    """``json`` 解析到重复对象键；由 :func:`load_dataset` 转成 ``DatasetError``。"""

    def __init__(self, key: str) -> None:
        super().__init__(key)
        self.key = key


class _NonFiniteConstantError(ValueError):
    """``json`` 解析到 ``NaN`` / ``Infinity`` / ``-Infinity``。"""

    def __init__(self, name: str) -> None:
        super().__init__(name)
        self.name = name


def load_dataset(path: Path) -> list[dict[str, Any]]:
    """读取冻结集，返回按文件顺序排列的 case 对象列表。

    - ``.jsonl``：每行一个 case 对象，空行（含纯空白行）忽略；
    - ``.json``：case 对象数组，或 ``{"schemaVersion":1,"cases":[...]}``
      （后者用于把 canonical 输出再喂回）。

    任何不合法输入都抛 :class:`DatasetError`；读文件本身的 ``OSError`` 原样向上抛，
    由调用方决定如何报告（CLI 统一映射为退出码 2）。
    """

    suffix = path.suffix.lower()
    if suffix not in _SUPPORTED_SUFFIXES:
        raise DatasetError(f"不支持的输入扩展名 {path.suffix!r}；只接受 .jsonl 或 .json")

    raw = path.read_bytes()
    if raw.startswith(_BOM):
        raise DatasetError("输入以 UTF-8 BOM 开头；冻结集必须是无 BOM 的 UTF-8")

    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise DatasetError(f"输入不是合法 UTF-8（第 {exc.start} 字节）") from None

    located: list[tuple[str, object]] = []
    if suffix == ".jsonl":
        for line_number, raw_line in enumerate(text.split("\n"), start=1):
            line = raw_line.removesuffix("\r")
            if not line.strip():
                continue
            position = f"第 {line_number} 行"
            located.append((position, _loads_strict(line, position)))
    else:
        document = _loads_strict(text, "")
        if isinstance(document, list):
            located = [
                (f"第 {index} 个 case", item) for index, item in enumerate(document, start=1)
            ]
        elif isinstance(document, Mapping):
            located = _document_cases(document)
        else:
            raise DatasetError("输入顶层必须是 case 数组，或 {schemaVersion, cases} 对象")

    return _collect_cases(located)


def canonicalize_cases(cases: Sequence[Mapping[str, Any]]) -> bytes:
    """把 case 集合编码为 canonical JSON 字节（单行、无尾随换行）。

    case 按 ``id`` 的 Unicode 码点升序；case 内部数组保持原顺序；对象键递归排序。
    输入不是合法 JSON 数据模型时抛 :class:`DatasetError`。
    """

    entries: list[tuple[str, dict[str, Any]]] = []
    first_index: dict[str, int] = {}
    for index, case in enumerate(cases, start=1):
        position = f"第 {index} 个 case"
        case_id, materialized = _require_case(case, position)
        if case_id in first_index:
            raise DatasetError(
                f"id 重复：{case_id!r}"
                f"（首次出现在第 {first_index[case_id]} 个 case，这里在第 {index} 个 case）"
            )
        first_index[case_id] = index
        entries.append((case_id, materialized))

    entries.sort(key=lambda entry: entry[0])
    document: dict[str, Any] = {
        "cases": [case for _, case in entries],
        "schemaVersion": SCHEMA_VERSION,
    }

    chunks: list[str] = []
    _encode(document, chunks)
    text = "".join(chunks)
    try:
        return text.encode("utf-8")
    except UnicodeEncodeError as exc:
        code = ord(exc.object[exc.start])
        raise DatasetError(f"字符串含无法编码为 UTF-8 的码点（U+{code:04X}）") from None


def canonicalize_path(path: Path) -> bytes:
    """``load_dataset`` + ``canonicalize_cases`` 的便捷入口。"""

    return canonicalize_cases(load_dataset(path))


def digest(data: bytes) -> str:
    """返回 ``data`` 的 SHA-256 十六进制小写摘要（不追加任何字节）。"""

    return hashlib.sha256(data).hexdigest()


def _document_cases(document: Mapping[Any, Any]) -> list[tuple[str, object]]:
    """校验 ``{"schemaVersion":1,"cases":[...]}`` 包装，返回带位置的 case 列表。"""

    unexpected = sorted(str(key) for key in document if key not in {"schemaVersion", "cases"})
    if unexpected:
        raise DatasetError(f"输入顶层对象含未定义字段：{', '.join(unexpected)}")

    version = document.get("schemaVersion")
    if not isinstance(version, int) or isinstance(version, bool) or version != SCHEMA_VERSION:
        raise DatasetError(f"输入顶层 schemaVersion 必须为 {SCHEMA_VERSION}")

    cases = document.get("cases")
    if not isinstance(cases, list):
        raise DatasetError("输入顶层 cases 必须是数组")

    return [(f"第 {index} 个 case", item) for index, item in enumerate(cases, start=1)]


def _collect_cases(located: Sequence[tuple[str, object]]) -> list[dict[str, Any]]:
    """逐条校验 case 结构，并拒绝重复 ``id``。"""

    cases: list[dict[str, Any]] = []
    seen: dict[str, str] = {}
    for position, value in located:
        case_id, case = _require_case(value, position)
        if case_id in seen:
            raise DatasetError(
                f"id 重复：{case_id!r}（首次出现在{seen[case_id]}，这里在{position}）"
            )
        seen[case_id] = position
        cases.append(case)
    return cases


def _require_case(value: object, position: str) -> tuple[str, dict[str, Any]]:
    """校验单个 case：必须是对象，且含非空字符串 ``id``。"""

    if not isinstance(value, Mapping):
        raise DatasetError(f"{position} 不是 JSON 对象（case 必须是对象）")

    case: dict[str, Any] = {}
    for key, item in value.items():
        if not isinstance(key, str):
            raise DatasetError(f"{position} 含非字符串键")
        case[key] = item

    case_id = case.get("id")
    if not isinstance(case_id, str) or not case_id:
        raise DatasetError(f"{position} 缺少非空字符串 id")
    return case_id, case


def _loads_strict(text: str, location: str) -> object:
    """严格 ``json.loads``：精确小数、拒绝重复键与 ``NaN`` / ``Infinity``。"""

    try:
        return json.loads(
            text,
            parse_float=Decimal,
            parse_int=int,
            parse_constant=_reject_constant,
            object_pairs_hook=_pairs_to_mapping,
        )
    except _DuplicateKeyError as exc:
        raise DatasetError(f"{location}存在重复键 {exc.key!r}") from None
    except _NonFiniteConstantError as exc:
        raise DatasetError(f"{location}出现非有限常量 {exc.name}") from None
    except json.JSONDecodeError as exc:
        raise DatasetError(
            f"{location}不是合法 JSON：{exc.msg}（第 {exc.lineno} 行第 {exc.colno} 列）"
        ) from None
    except RecursionError:
        raise DatasetError(f"{location}嵌套层级过深，无法解析") from None
    except ValueError as exc:
        # 例如 ``parse_int`` 命中 Python 的超长整数字符串上限。
        raise DatasetError(f"{location}不是合法 JSON：{exc}") from None


def _reject_constant(name: str) -> NoReturn:
    raise _NonFiniteConstantError(name)


def _pairs_to_mapping(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    mapping: dict[str, Any] = {}
    for key, value in pairs:
        if key in mapping:
            raise _DuplicateKeyError(key)
        mapping[key] = value
    return mapping


def _encode(value: object, chunks: list[str]) -> None:
    """把 JSON 数据模型的值递归编码进 ``chunks``（不排序调用方数组）。"""

    if value is None:
        chunks.append("null")
    elif isinstance(value, bool):
        chunks.append("true" if value else "false")
    elif isinstance(value, str):
        chunks.append(_encode_string(value))
    elif isinstance(value, int):
        # Python 的 int 无 ``-0``、无前导零，直接十进制输出即可。
        chunks.append(str(value))
    elif isinstance(value, (Decimal, float)):
        chunks.append(_encode_number(value))
    elif isinstance(value, Mapping):
        _encode_mapping(value, chunks)
    elif isinstance(value, (list, tuple)):
        _encode_sequence(value, chunks)
    else:
        raise DatasetError(f"不支持的 JSON 值类型：{type(value).__name__}")


def _encode_mapping(value: Mapping[object, object], chunks: list[str]) -> None:
    items: list[tuple[str, object]] = []
    for key, item in value.items():
        if not isinstance(key, str):
            raise DatasetError(f"JSON 对象键必须是字符串，实际为 {type(key).__name__}")
        items.append((key, item))
    items.sort(key=lambda pair: pair[0])

    chunks.append("{")
    for index, (key, item) in enumerate(items):
        if index:
            chunks.append(",")
        chunks.append(_encode_string(key))
        chunks.append(":")
        _encode(item, chunks)
    chunks.append("}")


def _encode_sequence(value: Sequence[object], chunks: list[str]) -> None:
    chunks.append("[")
    for index, item in enumerate(value):
        if index:
            chunks.append(",")
        _encode(item, chunks)
    chunks.append("]")


def _encode_string(value: str) -> str:
    """最小转义：只处理 ``"``、``\\`` 与 ``< 0x20``，其余原样（含非 ASCII）。"""

    chunks: list[str] = ['"']
    for character in value:
        escaped = _ESCAPES.get(character)
        if escaped is not None:
            chunks.append(escaped)
        elif character < "\x20":
            chunks.append(f"\\u00{ord(character):02x}")
        else:
            chunks.append(character)
    chunks.append('"')
    return "".join(chunks)


def _encode_number(value: Decimal | float) -> str:
    """无指数、无正号、至少一位小数的规范十进制形式。

    ``float`` 输入先按 ``Decimal(repr(value))`` 转成十进制精确值（README §7.2 第 5 条）。
    """

    decimal_value = value if isinstance(value, Decimal) else Decimal(repr(value))
    if not decimal_value.is_finite():
        raise DatasetError(f"拒绝非有限数值：{value!r}")

    # ``-0`` / ``-0.0`` / ``0.000`` 一律归一为 ``0.0``。
    if decimal_value.is_zero():
        decimal_value = Decimal(0)

    text = format(decimal_value, "f")
    if "." not in text:
        return f"{text}.0"
    integer_part, _, fraction_part = text.partition(".")
    fraction = fraction_part.rstrip("0") or "0"
    return f"{integer_part}.{fraction}"
