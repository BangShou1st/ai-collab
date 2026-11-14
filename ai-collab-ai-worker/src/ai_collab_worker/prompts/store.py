"""PromptPack 资产的加载、校验与取用（设计 §7.2 的 Python 侧实现）。

口径与 ``ai_collab_worker.contract.prompts`` 完全一致：

- ``prompts/manifest.json`` 是 manifest 的**唯一来源**，条目含
  ``key/version/sha256/chars/variables``；
- 模板正文 = 文件内容去掉末尾唯一一个换行；文件必须 UTF-8 无 BOM、LF 换行、以换行结尾；
  正文自身允许以换行结尾（Java 文本块正文天然带尾换行），只去掉末尾那一个换行；
- ``chars`` 是正文的 Unicode 码点数量，``sha256`` 是正文 UTF-8 字节的 SHA-256；
- ``variables`` 必须恰好等于模板中的占位符集合（排序去重）。

失败语义（设计 §7.2 / §10.1）：资产缺失或校验失败一律 ``PROMPT_UNAVAILABLE``（503），
不回退、不返回降级内容；未知 ``key/version`` 用 404（仍是 ``PROMPT_UNAVAILABLE``，
不可重试，Java 侧应回退"上次成功版本"）；格式非法用 400。对外可见的失败原因只能是
固定短语或异常类型名，**绝不包含文件路径与 Prompt 正文**。
"""

from __future__ import annotations

import json
import re
import threading
from pathlib import Path
from typing import Final

from pydantic import ValidationError

from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.contract.prompts import (
    PROMPT_KEY_PATTERN,
    PROMPT_VERSION_PATTERN,
    PromptDocument,
    PromptManifest,
    PromptManifestEntry,
    PromptPackEntry,
    PromptPackManifest,
    extract_variables,
    prompt_chars,
    prompt_sha256,
    prompt_text_from_bytes,
)

#: manifest 文件名（位于 PromptPack 目录根部，不是模板）。
MANIFEST_FILENAME: Final[str] = "manifest.json"

#: 与 ``contract.prompts`` 的 ``Annotated`` 长度约束保持一致。
KEY_MAX_LENGTH: Final[int] = 128
VERSION_MAX_LENGTH: Final[int] = 32

_KEY_PATTERN: Final[re.Pattern[str]] = re.compile(PROMPT_KEY_PATTERN)
_VERSION_PATTERN: Final[re.Pattern[str]] = re.compile(PROMPT_VERSION_PATTERN)

# ---- 对外可见的固定短语（不含路径与正文） --------------------------------------
_REASON_NOT_READY: Final[str] = "prompt pack not loaded"
_REASON_MANIFEST_MISSING: Final[str] = "manifest missing"
_REASON_MANIFEST_UNREADABLE: Final[str] = "manifest unreadable"
_REASON_MANIFEST_NOT_JSON: Final[str] = "manifest not valid JSON"
_REASON_MANIFEST_INVALID: Final[str] = "manifest schema invalid"
_REASON_TEMPLATE_MISSING: Final[str] = "template missing"
_REASON_TEMPLATE_UNREADABLE: Final[str] = "template unreadable"
_REASON_TEMPLATE_ENCODING: Final[str] = "template encoding invalid"
_REASON_TEMPLATE_SHA256: Final[str] = "template sha256 mismatch"
_REASON_TEMPLATE_CHARS: Final[str] = "template chars mismatch"
_REASON_TEMPLATE_VARIABLES: Final[str] = "template variables mismatch"
_REASON_TEMPLATE_DOCUMENT: Final[str] = "template document invalid"


class _AssetError(Exception):
    """内部资产校验失败；``reason`` 是可安全对外暴露的完整短语。"""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def _manifest_failure(reason: str) -> _AssetError:
    return _AssetError(f"manifest missing or invalid: {reason}")


def _template_failure(entry: PromptPackEntry, reason: str) -> _AssetError:
    return _AssetError(f"template invalid: {entry.key}@{entry.version}: {reason}")


def _unavailable(message: str, reason: str) -> AppError:
    """构造 ``PROMPT_UNAVAILABLE``（默认 503、不可重试），details 只放安全短语。"""

    return AppError(
        ErrorCode.PROMPT_UNAVAILABLE,
        message,
        status_code=503,
        details={"reason": reason},
    )


def _is_valid_key(value: str) -> bool:
    return len(value) <= KEY_MAX_LENGTH and _KEY_PATTERN.fullmatch(value) is not None


def _is_valid_version(value: str) -> bool:
    return len(value) <= VERSION_MAX_LENGTH and _VERSION_PATTERN.fullmatch(value) is not None


class PromptStore:
    """本地 PromptPack 的只读存储：manifest 缓存 + 模板每次取用重读校验。

    - 构造即 ``refresh()``；构造过程**不抛异常**，失败记录为"未就绪"（``readiness_failure``）；
    - ``manifest()`` 返回线上 manifest（每项只有 ``key/version/sha256/chars``，升序）；
    - ``get()`` **每次都从磁盘重读模板**并复算 ``sha256``/``chars``/``variables``，
      避免运行期热替换或磁盘损坏后继续发出与 manifest 不符的正文；
    - 线程安全：进程内只读，用一把锁保护 ``refresh`` 与缓存读取。
    """

    def __init__(self, prompts_dir: Path) -> None:
        self._dir: Final[Path] = Path(prompts_dir)
        self._lock: Final[threading.Lock] = threading.Lock()
        self._manifest: PromptPackManifest | None = None
        self._failure: str | None = None
        self.refresh()

    def refresh(self) -> None:
        """重新读取 manifest 与全部模板；成功则清空失败状态并缓存 manifest。"""

        with self._lock:
            try:
                pack = self._load_manifest()
                for entry in pack.prompts:
                    self._load_template(entry)
            except _AssetError as exc:
                self._manifest = None
                self._failure = exc.reason
                return
            self._manifest = pack
            self._failure = None

    def manifest(self) -> PromptManifest:
        """返回线上 manifest：每项只有 ``key/version/sha256/chars``。"""

        with self._lock:
            pack = self._require_ready("Prompt manifest 不可用")
        return PromptManifest(
            prompts=tuple(
                PromptManifestEntry(
                    key=entry.key,
                    version=entry.version,
                    sha256=entry.sha256,
                    chars=entry.chars,
                )
                for entry in pack.prompts
            )
        )

    def get(self, key: str, version: str) -> PromptDocument:
        """按 ``(key, version)`` 取用模板：每次从磁盘重读并校验后才返回。

        格式非法 → 400；不存在 → 404；资产损坏/存储未就绪 → 503；均由统一错误处理器
        渲染成错误信封，这里只 ``raise AppError``。
        """

        if not _is_valid_key(key) or not _is_valid_version(version):
            raise AppError(
                ErrorCode.INVALID_REQUEST,
                "Prompt key 或 version 格式非法",
                status_code=400,
                details={"key": key, "version": version},
            )

        with self._lock:
            pack = self._require_ready("Prompt 不可用")
            entry = next(
                (item for item in pack.prompts if item.key == key and item.version == version),
                None,
            )
            if entry is None:
                raise AppError(
                    ErrorCode.PROMPT_UNAVAILABLE,
                    "Prompt 不存在",
                    status_code=404,
                    details={"key": key, "version": version},
                )
            try:
                text = self._load_template(entry)
                document = PromptDocument(
                    key=entry.key,
                    version=entry.version,
                    sha256=entry.sha256,
                    text=text,
                    variables=entry.variables,
                )
            except _AssetError as exc:
                raise _unavailable("Prompt 不可用", exc.reason) from exc
            except ValidationError as exc:
                raise _unavailable("Prompt 不可用", _REASON_TEMPLATE_DOCUMENT) from exc
            return document

    def readiness_failure(self) -> str | None:
        """就绪返回 ``None``；否则返回一句不含路径与正文的失败原因。"""

        with self._lock:
            return self._failure

    # ---- 内部实现 ------------------------------------------------------------

    def _require_ready(self, message: str) -> PromptPackManifest:
        if self._manifest is None:
            raise _unavailable(message, self._failure or _REASON_NOT_READY)
        return self._manifest

    def _load_manifest(self) -> PromptPackManifest:
        path = self._dir / MANIFEST_FILENAME
        try:
            data = path.read_bytes()
        except FileNotFoundError as exc:
            raise _manifest_failure(_REASON_MANIFEST_MISSING) from exc
        except OSError as exc:
            reason = f"{_REASON_MANIFEST_UNREADABLE} ({type(exc).__name__})"
            raise _manifest_failure(reason) from exc
        try:
            payload = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise _manifest_failure(_REASON_MANIFEST_NOT_JSON) from exc
        try:
            return PromptPackManifest.model_validate(payload)
        except ValidationError as exc:
            raise _manifest_failure(_REASON_MANIFEST_INVALID) from exc

    def _load_template(self, entry: PromptPackEntry) -> str:
        # key/version 已由契约正则约束（不含分隔符），因此这里的路径不可能跳出 prompts_dir。
        path = self._dir / entry.key / f"{entry.version}.md"
        try:
            data = path.read_bytes()
        except FileNotFoundError as exc:
            raise _template_failure(entry, _REASON_TEMPLATE_MISSING) from exc
        except OSError as exc:
            reason = f"{_REASON_TEMPLATE_UNREADABLE} ({type(exc).__name__})"
            raise _template_failure(entry, reason) from exc
        try:
            text = prompt_text_from_bytes(data)
        except ValueError as exc:
            raise _template_failure(entry, _REASON_TEMPLATE_ENCODING) from exc
        if prompt_sha256(text) != entry.sha256:
            raise _template_failure(entry, _REASON_TEMPLATE_SHA256)
        if prompt_chars(text) != entry.chars:
            raise _template_failure(entry, _REASON_TEMPLATE_CHARS)
        if extract_variables(text) != list(entry.variables):
            raise _template_failure(entry, _REASON_TEMPLATE_VARIABLES)
        return text


__all__ = ["MANIFEST_FILENAME", "PromptStore"]
