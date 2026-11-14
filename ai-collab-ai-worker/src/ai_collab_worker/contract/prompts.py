"""PromptPack 契约：manifest（仓库文件 + GET 响应）与单条 Prompt 文档。

设计依据：``docs/design/python-ai-capability-plane-2026-11-11.md`` §7.2。

固定口径（Java J2 必须按此对齐）：

1. 仓库文件 ``prompts/manifest.json`` 是 manifest 的<b>唯一来源</b>，每个条目含
   ``key/version/sha256/chars/variables``；``GET /v1/prompts/manifest`` 只返回
   §7.2 规定的四个字段（``key/version/sha256/chars``），``variables`` 只用于服务端与
   契约测试校验，不进 manifest 响应。
2. 模板正文 = 文件内容去掉末尾唯一一个换行。文件必须是 UTF-8 无 BOM、LF 换行、
   以 ``\\n`` 结尾；**正文自身允许以换行结尾**（此时文件以两个换行结尾），因为 Java 文本块
   的正文天然带尾换行。文件末尾超过一个换行也不算错：只去掉最后一个，剩下的属于正文
   （见 ``docs/archive/handoffs/j2-prompt-pack-freeze-20261111.md`` §1.1/F2）。
3. ``chars`` = 正文的 Unicode 码点数量（Python ``len`` / Java ``codePointCount``），
   不是 UTF-16 code unit，也不是字节数。
4. ``sha256`` = 对正文 UTF-8 字节计算的 SHA-256，十六进制小写；正文自身的尾换行参与计算
   （只去掉文件末尾那一个换行）。
5. 占位符语法 ``{{ name }}``（允许花括号内空白），``variables`` 必须<b>恰好等于</b>
   模板中出现的占位符名字集合（排序、去重）；多一个或少一个都是硬错误。
"""

from __future__ import annotations

import hashlib
import re
from typing import Annotated, Final, Literal, Self

from pydantic import Field, model_validator

from ai_collab_worker.contract.common import SCHEMA_VERSION, ContractModel

#: Prompt key：点分层级，允许小写字母/数字/下划线，例如 ``knowledge.system``、
#: ``scenario.project_research``。
PROMPT_KEY_PATTERN: Final[str] = r"^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$"

#: Prompt 版本：``YYYY-MM-DD.N``。
PROMPT_VERSION_PATTERN: Final[str] = r"^\d{4}-\d{2}-\d{2}\.\d+$"

#: SHA-256 十六进制小写。
SHA256_PATTERN: Final[str] = r"^[0-9a-f]{64}$"

#: 占位符：``{{ name }}``；名字为 ASCII 标识符，允许花括号内空白。
PLACEHOLDER_PATTERN: Final[re.Pattern[str]] = re.compile(r"\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}")

#: 变量名：小写字母开头，后接字母/数字/下划线（``locale``、``docScope``）。
VARIABLE_NAME_PATTERN: Final[str] = r"^[a-z][A-Za-z0-9_]*$"

PromptKey = Annotated[
    str,
    Field(pattern=PROMPT_KEY_PATTERN, examples=["knowledge.system"], max_length=128),
]
PromptVersion = Annotated[
    str,
    Field(pattern=PROMPT_VERSION_PATTERN, examples=["2026-11-11.1"], max_length=32),
]
Sha256Hex = Annotated[str, Field(pattern=SHA256_PATTERN)]
VariableName = Annotated[str, Field(pattern=VARIABLE_NAME_PATTERN, max_length=64)]


def extract_variables(text: str) -> list[str]:
    """返回模板中出现的占位符名字，已排序去重。"""

    return sorted({match.group(1) for match in PLACEHOLDER_PATTERN.finditer(text)})


def prompt_sha256(text: str) -> str:
    """对模板正文（UTF-8）计算 SHA-256；正文自身的尾换行参与计算。"""

    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def prompt_chars(text: str) -> int:
    """模板正文字符数（Unicode 码点）。"""

    return len(text)


def prompt_text_from_bytes(data: bytes) -> str:
    """按固定口径把 Prompt 文件解码成模板正文。

    拒绝 BOM、非 UTF-8、空正文与"不以换行结尾"；CRLF/CR 归一为 LF。
    **只去掉末尾唯一一个换行**，因此正文允许自身以换行结尾（文件以两个换行结尾），
    返回值原样保留这些换行。
    """

    try:
        raw = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        msg = "Prompt 文件必须是 UTF-8 编码"
        raise ValueError(msg) from exc
    if raw.startswith("\ufeff"):
        msg = "Prompt 文件不得包含 UTF-8 BOM"
        raise ValueError(msg)
    text = raw.replace("\r\n", "\n").replace("\r", "\n")
    if not text.endswith("\n"):
        msg = "Prompt 文件必须以换行结尾"
        raise ValueError(msg)
    body = text[:-1]
    if not body:
        msg = "Prompt 文件正文不能为空"
        raise ValueError(msg)
    return body


def _sorted_unique(values: tuple[str, ...], *, field_name: str) -> None:
    if list(values) != sorted(values):
        msg = f"{field_name} 必须按 Unicode 码点升序排列"
        raise ValueError(msg)
    if len(set(values)) != len(values):
        msg = f"{field_name} 不得包含重复项"
        raise ValueError(msg)


def _validate_prompt_order(prompts: tuple[PromptManifestEntry, ...]) -> None:
    keys = [(entry.key, entry.version) for entry in prompts]
    if len(set(keys)) != len(keys):
        msg = "manifest.prompts 中存在重复的 (key, version)"
        raise ValueError(msg)
    if keys != sorted(keys):
        msg = "manifest.prompts 必须按 (key, version) 升序排列"
        raise ValueError(msg)


class PromptManifestEntry(ContractModel):
    """``GET /v1/prompts/manifest`` 的条目（设计 §7.2 的四个字段）。"""

    key: PromptKey
    version: PromptVersion
    sha256: Sha256Hex
    chars: int = Field(
        ge=1, description="模板正文的 Unicode 码点数量（只去掉文件末尾唯一一个换行）"
    )


class PromptManifest(ContractModel):
    """``GET /v1/prompts/manifest`` 响应。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    prompts: tuple[PromptManifestEntry, ...] = ()

    @model_validator(mode="after")
    def _check_order(self) -> Self:
        _validate_prompt_order(self.prompts)
        return self


class PromptPackEntry(PromptManifestEntry):
    """仓库 ``prompts/manifest.json`` 的条目：在四个字段上追加 ``variables``。"""

    variables: tuple[VariableName, ...] = Field(
        default=(),
        description="模板占位符名字集合，必须与模板实际占位符完全一致（排序去重）。",
    )

    @model_validator(mode="after")
    def _check_variables(self) -> Self:
        _sorted_unique(tuple(self.variables), field_name="variables")
        return self


class PromptPackManifest(ContractModel):
    """仓库 ``prompts/manifest.json`` 的文件形状（内部资产契约，不是 HTTP 响应）。"""

    schema_version: Literal[1] = SCHEMA_VERSION
    prompts: tuple[PromptPackEntry, ...] = ()

    @model_validator(mode="after")
    def _check_order(self) -> Self:
        _validate_prompt_order(self.prompts)
        return self


class PromptDocument(ContractModel):
    """``GET /v1/prompts/{key}/{version}`` 响应。

    模型自身做两道自检：``sha256`` 必须与 ``text`` 匹配；``variables`` 必须等于
    ``text`` 中的占位符集合。任何一项不满足都说明资产已损坏，构造即失败。
    """

    schema_version: Literal[1] = SCHEMA_VERSION
    key: PromptKey
    version: PromptVersion
    sha256: Sha256Hex
    text: str = Field(min_length=1)
    variables: tuple[VariableName, ...] = ()

    @model_validator(mode="after")
    def _check_integrity(self) -> Self:
        expected = extract_variables(self.text)
        if list(self.variables) != expected:
            msg = (
                "variables 与模板占位符不一致："
                f"variables={list(self.variables)} template={expected}"
            )
            raise ValueError(msg)
        actual = prompt_sha256(self.text)
        if actual != self.sha256:
            msg = f"sha256 与 text 不一致：manifest={self.sha256} computed={actual}"
            raise ValueError(msg)
        return self
