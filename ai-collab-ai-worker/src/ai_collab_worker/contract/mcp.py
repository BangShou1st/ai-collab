"""内部 MCP Gateway 契约、规范化与 Hash（W6 P1，冻结文档 §2–§7）。

Python Host 只调用 Java 的 ``POST /internal/ai/v1/mcp``。本模块不持有令牌、不访问业务库、
不直连外部 MCP，也不做授权判断；它只负责内部 JSON-RPC 的形状、工具目录规范化与确定性
Hash。``toolSchemaHash`` 与 ``catalogVersion`` 必须与仓库根
``tests/fixtures/mcp/catalog-v1.json`` 逐字节一致。

规范化与 Hash 的关键点：

- 字符串先做 Unicode NFC；显示文本再去除控制字符、折叠空白并替换明确注入片段；
- canonical JSON 使用 UTF-8、无空白、对象键按 Unicode 码点排序、非 ASCII 原样输出；
- Hash 对象禁止浮点，数组按契约顺序，工具目录按 ``name`` 升序；
- Schema 限制深度、节点数、canonical 字节数与外部引用/脚本字段；
- 描述超限或 Schema 非法会拒绝整个工具，不做静默截断。

“未命中注入正则”不是安全证明。Host 仍应把工具描述、Schema 与调用结果视为不可信数据。
"""

from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from collections.abc import Mapping, Sequence
from enum import StrEnum
from typing import Any, Final, Literal, Self

from pydantic import Field, field_validator, model_validator

from ai_collab_worker.contract.common import SCHEMA_VERSION, ContractModel

#: Java MCP Gateway 的唯一内部路径。
MCP_GATEWAY_PATH: Final[str] = "/internal/ai/v1/mcp"

#: JSON-RPC 版本与内部协议版本。
JSON_RPC_VERSION: Final[Literal["2.0"]] = "2.0"
MCP_PROTOCOL_VERSION: Final[str] = "2025-03-26"

#: 工具名、描述与 Schema 的冻结边界。
MAX_REQUEST_ID_CHARS: Final[int] = 128
MAX_TOOL_COMPONENT_CHARS: Final[int] = 128
MAX_TOOL_NAME_CHARS: Final[int] = 160
MAX_TOOL_DESCRIPTION_CHARS: Final[int] = 512
MAX_SCHEMA_DEPTH: Final[int] = 16
MAX_SCHEMA_NODES: Final[int] = 2_000
MAX_SCHEMA_BYTES: Final[int] = 32 * 1024
MAX_ARGUMENTS_BYTES: Final[int] = 64 * 1024

#: 架构字段名与 Hash 格式。
TOOL_NAME_PATTERN: Final[str] = r"^mcp\.[a-z0-9_]+\.[a-z0-9_]+$"
TOOL_COMPONENT_PATTERN: Final[str] = r"^[a-z0-9_]+$"
SHA256_PATTERN: Final[str] = r"^sha256:[0-9a-f]{64}$"

#: 明确的注入片段只做有限替换；未命中的内容仍按不可信数据处理。
_INSTRUCTION_PATTERNS: Final[tuple[re.Pattern[str], ...]] = (
    re.compile(
        r"(?:ignore|disregard|forget|override)\s+(?:all\s+)?"
        r"(?:previous|prior|above|system|developer)\s+"
        r"(?:instructions?|messages?|prompts?|rules?)",
        re.IGNORECASE,
    ),
    re.compile(
        r"(?:system|developer)\s*(?:prompt|message|instructions?)\s*:",
        re.IGNORECASE,
    ),
    re.compile(r"you\s+are\s+now\s+(?:the\s+)?system", re.IGNORECASE),
    re.compile(
        r"忽略|无视|忘记|覆盖|绕过"
        r"(?:之前|以上|系统|开发者|安全|审批)?(?:的)?(?:全部)?"
        r"(?:指令|提示词|规则|审批|限制)"
    ),
    re.compile(r"系统提示词|开发者指令|越权执行"),
)

_INSTRUCTION_MARKER: Final[str] = "[REMOVED_INSTRUCTION]"
_CONTROL_CHARACTERS: Final[re.Pattern[str]] = re.compile(r"[\x00-\x1f\x7f-\x9f]")
_URI_SCHEME: Final[re.Pattern[str]] = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")
_FORBIDDEN_SCHEMA_FIELDS: Final[frozenset[str]] = frozenset(
    {
        "$script",
        "script",
        "scripts",
        "javascript",
        "js",
        "eval",
        "expression",
        "onload",
        "onclick",
        "onerror",
    }
)

type McpRequestId = str | int


class McpErrorKind(StrEnum):
    """冻结契约 §6 的错误码；Host 不新增、不改写 Java 返回的 kind。"""

    UNAUTHORIZED = "UNAUTHORIZED"
    TOOL_DISABLED = "TOOL_DISABLED"
    SCHEMA_DRIFT = "SCHEMA_DRIFT"
    PARAM_INVALID = "PARAM_INVALID"
    NOT_EXECUTED = "NOT_EXECUTED"
    DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"
    RATE_LIMITED = "RATE_LIMITED"
    PARTIAL = "PARTIAL"
    TRUNCATED = "TRUNCATED"
    TOOL_REVOKED = "TOOL_REVOKED"
    OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
    INTERNAL = "INTERNAL"


MCP_ERROR_KINDS: Final[frozenset[McpErrorKind]] = frozenset(McpErrorKind)

MCP_ERROR_RETRYABLE: Final[frozenset[McpErrorKind]] = frozenset(
    {
        McpErrorKind.NOT_EXECUTED,
        McpErrorKind.RATE_LIMITED,
        McpErrorKind.INTERNAL,
    }
)

MCP_ERROR_MESSAGES: Final[dict[McpErrorKind, str]] = {
    McpErrorKind.UNAUTHORIZED: "MCP Gateway 身份无效",
    McpErrorKind.TOOL_DISABLED: "MCP 工具未启用",
    McpErrorKind.SCHEMA_DRIFT: "MCP 工具目录已变化",
    McpErrorKind.PARAM_INVALID: "MCP 参数不符合契约",
    McpErrorKind.NOT_EXECUTED: "MCP 工具未执行",
    McpErrorKind.DEADLINE_EXCEEDED: "MCP 工具调用超时",
    McpErrorKind.RATE_LIMITED: "MCP Gateway 限流",
    McpErrorKind.PARTIAL: "MCP 工具仅返回部分结果",
    McpErrorKind.TRUNCATED: "MCP 工具结果被截断",
    McpErrorKind.TOOL_REVOKED: "MCP 工具已撤权",
    McpErrorKind.OUTCOME_UNKNOWN: "MCP 工具结果未知",
    McpErrorKind.INTERNAL: "MCP Gateway 内部错误",
}


class McpOutcome(StrEnum):
    """``tools/call`` 的显式结果状态；不得与失败 kind 合并。"""

    SUCCEEDED = "SUCCEEDED"
    PARTIAL = "PARTIAL"
    TRUNCATED = "TRUNCATED"


class McpHostError(Exception):
    """Host 对 Java 错误 kind 的类型化透传。

    消息固定映射，绝不拼接 Java ``error.message``、远端正文、Token 或参数。``retryable``
    仅描述契约属性；本客户端自身不实现任何重试。
    """

    def __init__(self, kind: McpErrorKind, *, rpc_code: int | None = None) -> None:
        super().__init__(MCP_ERROR_MESSAGES[kind])
        self.kind: Final[McpErrorKind] = kind
        self.rpc_code: Final[int | None] = rpc_code

    @property
    def retryable(self) -> bool:
        return self.kind in MCP_ERROR_RETRYABLE


class McpCatalogError(ValueError):
    """工具目录/Schema 规范化失败；不暴露原始文本。"""

    def __init__(self, kind: McpErrorKind, reason: str) -> None:
        super().__init__(reason)
        self.kind: Final[McpErrorKind] = kind
        self.reason: Final[str] = reason


def _validate_request_id(value: object) -> object:
    if isinstance(value, bool):
        raise ValueError("JSON-RPC id 不得为布尔值")
    if isinstance(value, int):
        if len(str(value)) > MAX_REQUEST_ID_CHARS:
            raise ValueError("JSON-RPC id 超过 128 个字符")
        return value
    if isinstance(value, str):
        if not 1 <= len(value) <= MAX_REQUEST_ID_CHARS:
            raise ValueError("JSON-RPC id 必须为 1–128 个字符")
        return value
    raise ValueError("JSON-RPC id 必须为字符串或整数")


class McpClientInfo(ContractModel):
    """``initialize.params.client``。"""

    name: str = Field(min_length=1, max_length=128)
    version: str = Field(min_length=1, max_length=128)


class McpInitializeParams(ContractModel):
    schema_version: Literal[1] = SCHEMA_VERSION
    protocol_version: str = Field(min_length=1, max_length=64)
    client: McpClientInfo


class McpServerInfo(ContractModel):
    name: str = Field(min_length=1, max_length=128)
    version: str = Field(min_length=1, max_length=128)


class McpToolsCapability(ContractModel):
    list_changed: Literal[False]


class McpServerCapabilities(ContractModel):
    tools: McpToolsCapability


class McpInitializeResult(ContractModel):
    schema_version: Literal[1] = SCHEMA_VERSION
    protocol_version: str = Field(min_length=1, max_length=64)
    server_info: McpServerInfo
    capabilities: McpServerCapabilities


class McpPingParams(ContractModel):
    schema_version: Literal[1] = SCHEMA_VERSION


class McpPingResult(ContractModel):
    schema_version: Literal[1] = SCHEMA_VERSION
    status: Literal["ok"]


class McpToolsListParams(ContractModel):
    schema_version: Literal[1] = SCHEMA_VERSION


class _McpRequestBase(ContractModel):
    json_rpc: Literal["2.0"] = Field(default=JSON_RPC_VERSION, alias="jsonrpc")
    id: McpRequestId

    @field_validator("id", mode="before")
    @classmethod
    def _check_id(cls, value: object) -> object:
        return _validate_request_id(value)


class McpInitializeRequest(_McpRequestBase):
    method: Literal["initialize"] = "initialize"
    params: McpInitializeParams


class McpPingRequest(_McpRequestBase):
    method: Literal["ping"] = "ping"
    params: McpPingParams


class McpToolsListRequest(_McpRequestBase):
    method: Literal["tools/list"] = "tools/list"
    params: McpToolsListParams


class McpToolAnnotations(ContractModel):
    """Gateway 暴露的工具必须由远端明确声明只读。"""

    read_only_hint: Literal[True]


class McpDiscoveredAnnotations(ContractModel):
    """发现结果里的原始只读声明；``False`` 会在规范化时拒绝。"""

    read_only_hint: bool


class McpDiscoveredTool(ContractModel):
    """Java/远端发现结果的输入形状，只用于规范化与 fixture 验证。"""

    name: str = Field(min_length=1, max_length=MAX_TOOL_COMPONENT_CHARS)
    description: str
    input_schema: dict[str, Any]
    annotations: McpDiscoveredAnnotations


type McpDiscoveredToolInput = McpDiscoveredTool | Mapping[str, Any]


class McpTool(ContractModel):
    """``tools/list.tools[]`` 的规范化工具定义。"""

    name: str = Field(
        min_length=1,
        max_length=MAX_TOOL_NAME_CHARS,
        pattern=TOOL_NAME_PATTERN,
    )
    description: str = Field(max_length=MAX_TOOL_DESCRIPTION_CHARS)
    input_schema: dict[str, Any]
    tool_schema_hash: str = Field(pattern=SHA256_PATTERN)
    annotations: McpToolAnnotations


class McpToolsListResult(ContractModel):
    """``tools/list`` 成功结果；工具数组按 name 升序。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    catalog_version: str = Field(pattern=SHA256_PATTERN)
    tools: tuple[McpTool, ...]


class McpToolsCallParams(ContractModel):
    """``tools/call`` 参数；不接受请求体自报的 project/run 身份。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    name: str = Field(
        min_length=1,
        max_length=MAX_TOOL_NAME_CHARS,
        pattern=TOOL_NAME_PATTERN,
    )
    catalog_version: str = Field(pattern=SHA256_PATTERN)
    tool_schema_hash: str = Field(pattern=SHA256_PATTERN)
    call_id: str = Field(min_length=1)
    arguments: dict[str, Any]

    @field_validator("arguments")
    @classmethod
    def _check_arguments(cls, value: dict[str, Any]) -> dict[str, Any]:
        forbidden = {
            "projectId",
            "project_id",
            "projectRef",
            "project_ref",
            "runId",
            "run_id",
            "runRef",
            "run_ref",
            "userId",
            "user_id",
            "role",
            "approvalId",
            "approval_id",
        }
        if forbidden.intersection(value):
            raise ValueError("工具参数不得包含身份/授权字段")
        try:
            encoded = json.dumps(
                value,
                allow_nan=False,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            ).encode("utf-8")
        except (TypeError, ValueError) as exc:
            raise ValueError("工具参数必须是可序列化的 JSON object") from exc
        if len(encoded) > MAX_ARGUMENTS_BYTES:
            raise ValueError("工具参数超过 64 KiB")
        return value


class McpToolsCallRequest(_McpRequestBase):
    method: Literal["tools/call"] = "tools/call"
    params: McpToolsCallParams


type McpRequest = McpInitializeRequest | McpPingRequest | McpToolsListRequest | McpToolsCallRequest


class McpToolCallResult(ContractModel):
    """``tools/call`` 成功结果；显式保留 outcome/untrusted/truncated。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    catalog_version: str = Field(pattern=SHA256_PATTERN)
    outcome: McpOutcome
    untrusted: bool
    data: Any
    truncated: bool
    latency_ms: int | None = Field(default=None, ge=0)
    warnings: tuple[str, ...] = ()

    @field_validator("latency_ms", mode="before")
    @classmethod
    def _reject_boolean_latency(cls, value: object) -> object:
        if isinstance(value, bool):
            raise ValueError("latencyMs 必须为整数或 null")
        return value

    @model_validator(mode="after")
    def _check_outcome_consistency(self) -> Self:
        if self.outcome is McpOutcome.SUCCEEDED and self.truncated:
            raise ValueError("SUCCEEDED 不得同时标记 truncated=true")
        if self.outcome in {McpOutcome.PARTIAL, McpOutcome.TRUNCATED} and not self.truncated:
            raise ValueError("PARTIAL/TRUNCATED 必须标记 truncated=true")
        return self


class McpJsonRpcSuccess(ContractModel):
    """无泛型的成功信封；result 由具体方法模型二次校验。"""

    json_rpc: Literal["2.0"] = Field(default=JSON_RPC_VERSION, alias="jsonrpc")
    id: McpRequestId
    result: dict[str, Any]

    @field_validator("id", mode="before")
    @classmethod
    def _check_id(cls, value: object) -> object:
        return _validate_request_id(value)


class McpJsonRpcErrorData(ContractModel):
    kind: McpErrorKind
    retryable: bool | None = None


class McpJsonRpcErrorObject(ContractModel):
    code: int
    message: str
    data: McpJsonRpcErrorData


class McpJsonRpcErrorResponse(ContractModel):
    json_rpc: Literal["2.0"] = Field(default=JSON_RPC_VERSION, alias="jsonrpc")
    id: McpRequestId | None
    error: McpJsonRpcErrorObject

    @field_validator("id", mode="before")
    @classmethod
    def _check_id(cls, value: object) -> object:
        if value is None:
            return None
        return _validate_request_id(value)


def normalize_display_text(value: str) -> str:
    """按冻结 §4.1 规范化描述等显示文本。

    NFC → 去除除换行/制表外的控制字符 → 换行/制表折叠为空格 → 连续空白折叠 → 去首尾空白。
    明确的指令注入片段替换为固定 marker；未命中仍不代表可信。
    """

    normalized = unicodedata.normalize("NFC", value)
    characters: list[str] = []
    for character in normalized:
        if character in {"\n", "\t"}:
            characters.append(" ")
        elif unicodedata.category(character) in {"Cc", "Cf"}:
            continue
        else:
            characters.append(character)
    collapsed = " ".join("".join(characters).split())
    for pattern in _INSTRUCTION_PATTERNS:
        collapsed = pattern.sub(_INSTRUCTION_MARKER, collapsed)
    return collapsed


def normalize_tool_component(value: str, *, field: str) -> str:
    """把工具名/连接 code 规范为小写 ``[a-z0-9_]`` 并拒绝空值。"""

    normalized = unicodedata.normalize("NFC", value).lower()
    characters: list[str] = []
    previous_underscore = False
    for character in normalized:
        allowed = "a" <= character <= "z" or "0" <= character <= "9" or character == "_"
        if allowed:
            output = character
        elif character in {"-", ".", " "}:
            output = "_"
        else:
            raise McpCatalogError(McpErrorKind.PARAM_INVALID, f"{field}_invalid_character")
        if output == "_":
            if previous_underscore:
                continue
            previous_underscore = True
        else:
            previous_underscore = False
        characters.append(output)
    result = "".join(characters).strip("_")
    if not result:
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, f"{field}_empty")
    if len(result) > MAX_TOOL_COMPONENT_CHARS:
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, f"{field}_too_long")
    return result


def _normalize_schema_string(value: str) -> str:
    return normalize_display_text(value)


def _normalize_schema(
    value: object,
    *,
    depth: int,
    node_count: list[int],
) -> object:
    node_count[0] += 1
    if node_count[0] > MAX_SCHEMA_NODES:
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, "schema_too_many_nodes")
    if depth > MAX_SCHEMA_DEPTH:
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, "schema_too_deep")

    if isinstance(value, Mapping):
        normalized_mapping: dict[str, Any] = {}
        for raw_key, raw_item in value.items():
            if not isinstance(raw_key, str):
                raise McpCatalogError(McpErrorKind.PARAM_INVALID, "schema_key_not_string")
            key = unicodedata.normalize("NFC", raw_key)
            if _CONTROL_CHARACTERS.search(key):
                raise McpCatalogError(McpErrorKind.PARAM_INVALID, "schema_key_control")
            if key.lower() in _FORBIDDEN_SCHEMA_FIELDS:
                raise McpCatalogError(McpErrorKind.PARAM_INVALID, "schema_script_field")
            if key in {"$ref", "$dynamicRef", "$recursiveRef"} and (
                not isinstance(raw_item, str) or not raw_item.startswith("#")
            ):
                raise McpCatalogError(McpErrorKind.PARAM_INVALID, "schema_external_ref")
            if (
                key == "$id"
                and isinstance(raw_item, str)
                and (raw_item.startswith("//") or _URI_SCHEME.match(raw_item) is not None)
            ):
                raise McpCatalogError(McpErrorKind.PARAM_INVALID, "schema_remote_id")
            if key in normalized_mapping:
                raise McpCatalogError(McpErrorKind.PARAM_INVALID, "schema_key_collision")
            normalized_mapping[key] = _normalize_schema(
                raw_item,
                depth=depth + 1,
                node_count=node_count,
            )
        return normalized_mapping

    if isinstance(value, (list, tuple)):
        return [_normalize_schema(item, depth=depth + 1, node_count=node_count) for item in value]

    if isinstance(value, str):
        return _normalize_schema_string(value)

    if value is None or isinstance(value, bool):
        return value

    if isinstance(value, int):
        return value

    if isinstance(value, float):
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, "schema_float_not_allowed")

    raise McpCatalogError(McpErrorKind.PARAM_INVALID, "schema_value_not_json")


def normalize_input_schema(value: Mapping[str, Any]) -> dict[str, Any]:
    """规范化 inputSchema，并执行深度/节点/大小/引用/脚本字段限制。"""

    normalized = _normalize_schema(value, depth=1, node_count=[0])
    if not isinstance(normalized, dict):
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, "schema_not_object")

    try:
        encoded = canonical_json_bytes(normalized)
    except McpCatalogError:
        raise
    if len(encoded) > MAX_SCHEMA_BYTES:
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, "schema_too_large")
    return normalized


def _normalize_hash_value(value: object, *, depth: int) -> object:
    if depth > MAX_SCHEMA_DEPTH + 2:
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, "hash_value_too_deep")
    if isinstance(value, Mapping):
        normalized: dict[str, Any] = {}
        for raw_key, raw_item in value.items():
            if not isinstance(raw_key, str):
                raise McpCatalogError(McpErrorKind.PARAM_INVALID, "hash_key_not_string")
            key = unicodedata.normalize("NFC", raw_key)
            if key in normalized:
                raise McpCatalogError(McpErrorKind.PARAM_INVALID, "hash_key_collision")
            normalized[key] = _normalize_hash_value(raw_item, depth=depth + 1)
        return normalized
    if isinstance(value, (list, tuple)):
        return [_normalize_hash_value(item, depth=depth + 1) for item in value]
    if isinstance(value, str):
        return unicodedata.normalize("NFC", value)
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, "hash_float_not_allowed")
    raise McpCatalogError(McpErrorKind.PARAM_INVALID, "hash_value_not_json")


def canonical_json_bytes(value: object) -> bytes:
    """冻结 §4.3 的 canonical JSON：NFC、UTF-8、无空白、键按码点升序。"""

    normalized = _normalize_hash_value(value, depth=1)
    return json.dumps(
        normalized,
        allow_nan=False,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def _sha256_prefixed(data: bytes) -> str:
    return f"sha256:{hashlib.sha256(data).hexdigest()}"


def normalize_tool(
    *,
    connection_code: str,
    tool: McpDiscoveredToolInput,
) -> McpTool:
    """规范化单个发现工具；任一限制失败即拒绝整个工具。"""

    discovered = (
        tool if isinstance(tool, McpDiscoveredTool) else McpDiscoveredTool.model_validate(tool)
    )
    code = normalize_tool_component(connection_code, field="connection_code")
    if not discovered.annotations.read_only_hint:
        raise McpCatalogError(McpErrorKind.TOOL_DISABLED, "tool_not_read_only")

    server_name = normalize_tool_component(discovered.name, field="tool_name")
    exposed_name = f"mcp.{code}.{server_name}"
    if len(exposed_name) > MAX_TOOL_NAME_CHARS:
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, "exposed_name_too_long")

    description = normalize_display_text(discovered.description)
    if len(description) > MAX_TOOL_DESCRIPTION_CHARS:
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, "description_too_long")

    input_schema = normalize_input_schema(discovered.input_schema)
    schema_hash = _compute_tool_schema_hash(
        name=exposed_name,
        description=description,
        input_schema=input_schema,
    )
    return McpTool(
        name=exposed_name,
        description=description,
        input_schema=input_schema,
        tool_schema_hash=schema_hash,
        annotations=McpToolAnnotations(read_only_hint=True),
    )


def _tool_hash_input(
    *,
    name: str,
    description: str,
    input_schema: Mapping[str, Any],
) -> dict[str, Any]:
    return {
        "annotations": {"readOnlyHint": True},
        "description": description,
        "inputSchema": dict(input_schema),
        "name": name,
    }


def _compute_tool_schema_hash(
    *,
    name: str,
    description: str,
    input_schema: Mapping[str, Any],
) -> str:
    return _sha256_prefixed(
        canonical_json_bytes(
            _tool_hash_input(
                name=name,
                description=description,
                input_schema=input_schema,
            )
        )
    )


def compute_tool_schema_hash(
    *,
    name: str,
    description: str,
    input_schema: Mapping[str, Any],
) -> str:
    """按冻结 §5.1 计算 ``sha256:<hex>``；输入会先规范化。"""

    normalized_description = normalize_display_text(description)
    if len(normalized_description) > MAX_TOOL_DESCRIPTION_CHARS:
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, "description_too_long")
    normalized_schema = normalize_input_schema(input_schema)
    if re.fullmatch(TOOL_NAME_PATTERN, name) is None or len(name) > MAX_TOOL_NAME_CHARS:
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, "tool_name_invalid")
    return _compute_tool_schema_hash(
        name=unicodedata.normalize("NFC", name),
        description=normalized_description,
        input_schema=normalized_schema,
    )


def compute_catalog_version(
    tools: Sequence[McpTool],
) -> str:
    """按冻结 §5.2 计算目录版本；输入顺序可乱，Hash 前按 name 升序。"""

    entries = sorted(
        (
            {
                "name": tool.name,
                "toolSchemaHash": tool.tool_schema_hash,
            }
            for tool in tools
        ),
        key=lambda entry: entry["name"],
    )
    return _sha256_prefixed(
        canonical_json_bytes(
            {
                "schemaVersion": SCHEMA_VERSION,
                "tools": entries,
            }
        )
    )


def normalize_catalog(
    *,
    connection_code: str,
    tools: Sequence[McpDiscoveredToolInput],
) -> McpToolsListResult:
    """规范化原始工具目录，排序并计算逐工具 Hash 与 catalogVersion。"""

    normalized = [normalize_tool(connection_code=connection_code, tool=tool) for tool in tools]
    names = [tool.name for tool in normalized]
    if len(names) != len(set(names)):
        raise McpCatalogError(McpErrorKind.PARAM_INVALID, "tool_name_collision")
    normalized.sort(key=lambda tool: tool.name)
    return McpToolsListResult(
        catalog_version=compute_catalog_version(normalized),
        tools=tuple(normalized),
    )


def normalize_tools_list_result(result: McpToolsListResult) -> McpToolsListResult:
    """二次校验并规范化 Gateway 返回的目录，不自动确认任何新 Schema。

    Gateway 已给出 Hash；Host 只重算并比对。Hash 或目录版本不一致统一视为
    ``SCHEMA_DRIFT``，不自动迁移、不自动放行。
    """

    names = [tool.name for tool in result.tools]
    if names != sorted(names):
        raise McpCatalogError(McpErrorKind.SCHEMA_DRIFT, "tools_not_sorted")
    if len(names) != len(set(names)):
        raise McpCatalogError(McpErrorKind.SCHEMA_DRIFT, "tool_name_collision")

    normalized_tools: list[McpTool] = []
    for tool in result.tools:
        try:
            computed = compute_tool_schema_hash(
                name=tool.name,
                description=tool.description,
                input_schema=tool.input_schema,
            )
        except McpCatalogError as exc:
            raise McpCatalogError(McpErrorKind.SCHEMA_DRIFT, exc.reason) from exc
        if computed != tool.tool_schema_hash:
            raise McpCatalogError(McpErrorKind.SCHEMA_DRIFT, "tool_schema_hash_mismatch")
        normalized_tools.append(
            McpTool(
                name=tool.name,
                description=normalize_display_text(tool.description),
                input_schema=normalize_input_schema(tool.input_schema),
                tool_schema_hash=computed,
                annotations=McpToolAnnotations(read_only_hint=True),
            )
        )

    computed_catalog = compute_catalog_version(normalized_tools)
    if computed_catalog != result.catalog_version:
        raise McpCatalogError(McpErrorKind.SCHEMA_DRIFT, "catalog_version_mismatch")
    return McpToolsListResult(
        catalog_version=computed_catalog,
        tools=tuple(normalized_tools),
    )


def validate_tool_call_result(result: McpToolCallResult) -> McpToolCallResult:
    """保留 result 的全部语义字段，不做成功/失败布尔化或自动修补。"""

    return result


__all__ = [
    "JSON_RPC_VERSION",
    "MAX_ARGUMENTS_BYTES",
    "MAX_REQUEST_ID_CHARS",
    "MAX_SCHEMA_BYTES",
    "MAX_SCHEMA_DEPTH",
    "MAX_SCHEMA_NODES",
    "MAX_TOOL_DESCRIPTION_CHARS",
    "MAX_TOOL_NAME_CHARS",
    "MCP_ERROR_KINDS",
    "MCP_ERROR_MESSAGES",
    "MCP_ERROR_RETRYABLE",
    "MCP_GATEWAY_PATH",
    "MCP_PROTOCOL_VERSION",
    "SHA256_PATTERN",
    "TOOL_NAME_PATTERN",
    "McpCatalogError",
    "McpClientInfo",
    "McpDiscoveredAnnotations",
    "McpDiscoveredTool",
    "McpErrorKind",
    "McpHostError",
    "McpInitializeParams",
    "McpInitializeRequest",
    "McpInitializeResult",
    "McpJsonRpcErrorData",
    "McpJsonRpcErrorObject",
    "McpJsonRpcErrorResponse",
    "McpJsonRpcSuccess",
    "McpOutcome",
    "McpPingParams",
    "McpPingRequest",
    "McpPingResult",
    "McpRequest",
    "McpRequestId",
    "McpServerCapabilities",
    "McpServerInfo",
    "McpTool",
    "McpToolAnnotations",
    "McpToolCallResult",
    "McpToolsCallParams",
    "McpToolsCallRequest",
    "McpToolsCapability",
    "McpToolsListParams",
    "McpToolsListRequest",
    "McpToolsListResult",
    "canonical_json_bytes",
    "compute_catalog_version",
    "compute_tool_schema_hash",
    "normalize_catalog",
    "normalize_display_text",
    "normalize_input_schema",
    "normalize_tool",
    "normalize_tool_component",
    "normalize_tools_list_result",
    "validate_tool_call_result",
]
