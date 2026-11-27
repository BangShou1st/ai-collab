"""日志脱敏（P1 契约 §2.5）。

两条规则同时生效，任何一条命中都替换为 ``***``：

(a) **键名规则**：字典键名（不区分大小写）匹配
    ``api[_-]?key|authorization|x-api-key|x-goog-api-key|secret|password|token``
    的值一律替换。``token`` 后面紧跟 ``s`` / ``count`` / ``_count`` 的键（``inputTokens``、
    ``max_tokens``、``token_count``）是用量计数而不是凭据，不命中；
(b) **活跃密钥子串规则**：:data:`SECRETS` 里登记的每个原值（当前活跃 lease 的 apiKey、
    ``REDIS_URL`` 里的密码）在任意字符串里按子串替换。

另外对两种通用形状做兜底：``Bearer <token>`` 与 URL userinfo 里的密码
（``redis://:pw@host`` → ``redis://:***@host``）。

接入点：

- structlog：:func:`structlog_redactor` 作为 processor 放在 renderer 之前；
- 标准 logging：:func:`install_logging_redaction` 替换 LogRecord 工厂，在记录**创建时**就把
  ``msg % args`` 与异常文本脱敏，因此不依赖具体 handler（caplog、uvicorn、第三方库都覆盖）。
"""

from __future__ import annotations

import logging
import re
import threading
import traceback
from collections import Counter
from collections.abc import Mapping, MutableMapping
from typing import Any, Final

#: 替换文本。
REDACTED: Final[str] = "***"

#: 敏感键名（契约 §2.5(a)），``token`` 排除用量计数类键。
SENSITIVE_KEY_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"api[_-]?key|authorization|x-api-key|x-goog-api-key|secret|password|token(?!s|_?count)",
    re.IGNORECASE,
)

#: 短于该长度的值不登记（避免把 ``a`` 之类的串在全部日志里替换掉）。
MIN_SECRET_CHARS: Final[int] = 4

_BEARER_PATTERN: Final[re.Pattern[str]] = re.compile(r"(?i)\b(bearer\s+)[A-Za-z0-9._~+/=-]{8,}")
_URL_USERINFO_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"([A-Za-z][A-Za-z0-9+.-]*://[^:/@\s]*:)[^@\s/]+@"
)


class SecretRegistry:
    """进程内活跃密钥集合（引用计数；同一密钥被多个 lease 共用时最后一个释放才移除）。

    只在内存里；没有任何序列化或落盘路径。
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counts: Counter[str] = Counter()
        self._ordered: tuple[str, ...] = ()

    def add(self, value: str) -> None:
        if len(value) < MIN_SECRET_CHARS:
            return
        with self._lock:
            self._counts[value] += 1
            self._rebuild()

    def discard(self, value: str) -> None:
        with self._lock:
            if value not in self._counts:
                return
            self._counts[value] -= 1
            if self._counts[value] <= 0:
                del self._counts[value]
            self._rebuild()

    def clear(self) -> None:
        with self._lock:
            self._counts.clear()
            self._ordered = ()

    def snapshot(self) -> tuple[str, ...]:
        """长的在前：避免短密钥先替换后把长密钥切碎。"""

        return self._ordered

    def __len__(self) -> int:
        return len(self._ordered)

    def __contains__(self, value: object) -> bool:
        return value in self._counts

    def __repr__(self) -> str:  # 绝不输出原值
        return f"SecretRegistry(<{len(self._ordered)} secrets>)"

    def __reduce__(self) -> Any:
        msg = "SecretRegistry 不可序列化"
        raise TypeError(msg)

    def _rebuild(self) -> None:
        self._ordered = tuple(sorted(self._counts, key=len, reverse=True))


#: 进程级唯一实例：lease 缓存与配置装配都往这里登记。
SECRETS: Final[SecretRegistry] = SecretRegistry()


def redact_text(text: str, *, registry: SecretRegistry | None = None) -> str:
    """按规则 (b) 与通用兜底替换字符串。"""

    if not text:
        return text
    result = text
    for secret in (registry or SECRETS).snapshot():
        if secret in result:
            result = result.replace(secret, REDACTED)
    result = _BEARER_PATTERN.sub(rf"\g<1>{REDACTED}", result)
    return _URL_USERINFO_PATTERN.sub(rf"\g<1>{REDACTED}@", result)


def is_sensitive_key(key: object) -> bool:
    return isinstance(key, str) and SENSITIVE_KEY_PATTERN.search(key) is not None


def redact_value(value: Any, *, registry: SecretRegistry | None = None, _depth: int = 0) -> Any:
    """递归脱敏：dict 按键名 + 值，list/tuple/set 逐项，str 按子串，其它原样返回。"""

    if _depth > 16:
        return value
    if isinstance(value, str):
        return redact_text(value, registry=registry)
    if isinstance(value, Mapping):
        return {
            key: (
                REDACTED
                if is_sensitive_key(key) and value[key] is not None
                else redact_value(value[key], registry=registry, _depth=_depth + 1)
            )
            for key in value
        }
    if isinstance(value, list | tuple | set | frozenset):
        items = [redact_value(item, registry=registry, _depth=_depth + 1) for item in value]
        return type(value)(items) if not isinstance(value, list) else items
    return value


def structlog_redactor(
    _logger: object, _method_name: str, event_dict: MutableMapping[str, Any]
) -> MutableMapping[str, Any]:
    """structlog processor：放在 ``format_exc_info`` 之后、renderer 之前。"""

    redacted = redact_value(dict(event_dict))
    event_dict.clear()
    event_dict.update(redacted)
    return event_dict


_install_lock = threading.Lock()
_installed: bool = False


def install_logging_redaction() -> None:
    """替换标准 logging 的 LogRecord 工厂（幂等）。"""

    global _installed
    with _install_lock:
        if _installed:
            return
        previous = logging.getLogRecordFactory()

        def factory(*args: Any, **kwargs: Any) -> logging.LogRecord:
            record = previous(*args, **kwargs)
            _redact_record(record)
            return record

        logging.setLogRecordFactory(factory)
        _installed = True


def _redact_record(record: logging.LogRecord) -> None:
    if record.name == "uvicorn.access" and isinstance(record.args, tuple) and len(record.args) == 5:
        # uvicorn.logging.AccessFormatter 直接解包 5 元组 record.args，不能清空为 None。
        record.msg = redact_text(str(record.msg))
        record.args = tuple(redact_value(arg) for arg in record.args)
    else:
        try:
            message = record.getMessage()
        except Exception:  # 参数与占位符不匹配：保留原 msg，交给 logging 自己报错
            message = str(record.msg)
        record.msg = redact_text(message)
        record.args = None
    if record.exc_info and record.exc_info[1] is not None:
        formatted = "".join(traceback.format_exception(*record.exc_info))
        record.exc_text = redact_text(formatted)
        # 已格式化成脱敏文本，原异常对象不再交给 handler（避免二次格式化出原文）。
        record.exc_info = None
    for key, value in list(vars(record).items()):
        if key in _RECORD_BUILTINS:
            continue
        if is_sensitive_key(key):
            setattr(record, key, REDACTED)
        elif isinstance(value, str | Mapping | list | tuple):
            setattr(record, key, redact_value(value))


_RECORD_BUILTINS: Final[frozenset[str]] = frozenset(
    vars(logging.LogRecord("x", logging.INFO, "x", 0, "x", None, None))
) | {"message", "asctime", "taskName"}


__all__ = [
    "MIN_SECRET_CHARS",
    "REDACTED",
    "SECRETS",
    "SENSITIVE_KEY_PATTERN",
    "SecretRegistry",
    "install_logging_redaction",
    "is_sensitive_key",
    "redact_text",
    "redact_value",
    "structlog_redactor",
]
