"""禁入依赖守卫（设计 §6.2）。

P0 的边界是"零数据库、零对象存储、零任务队列、零模型 SDK、零本地推理"：
这些依赖既不能出现在 ``uv.lock`` 里，也不能被传递依赖带进运行环境。
本模块只用标准库（``importlib.metadata`` + ``tomllib``）自检，不联网、
不导入第三方包，也不读取锁文件内容以外的任何数据。

命中时失败信息只列出包名、所属家族与来源，不打印锁文件正文。
"""

from __future__ import annotations

import importlib.metadata
import re
import tomllib
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
LOCK_FILE = PROJECT_ROOT / "uv.lock"
PYPROJECT_FILE = PROJECT_ROOT / "pyproject.toml"

# PEP 508 要求串开头的分发名（``fastapi[standard]>=0.115`` → ``fastapi``）。
_REQUIREMENT_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")


def normalize(name: str) -> str:
    """包名归一：小写、``_`` 视为 ``-``（``pydantic_core`` → ``pydantic-core``）。"""

    return name.strip().lower().replace("_", "-")


@dataclass(frozen=True)
class ForbiddenRule:
    """一组禁入包：``exact`` 精确命中，``prefixes`` 按前缀命中。"""

    family: str
    exact: frozenset[str] = frozenset()
    prefixes: tuple[str, ...] = ()


# 设计 §6.2 的禁入清单。前缀用于覆盖同一家族的分包（``langchain-core``、
# ``boto3``/``botocore``、``psycopg2-binary`` …）；torch 只做精确名 + ``torch-``
# 前缀，避免宽前缀误伤无关包。
RULES: tuple[ForbiddenRule, ...] = (
    ForbiddenRule(
        family="Agent 框架",
        exact=frozenset({"llamaindex"}),
        prefixes=(
            "langchain",
            "langgraph",
            "llama-index",
            "crewai",
            "autogen",
            "openai-agents",
            "pydantic-ai",
        ),
    ),
    ForbiddenRule(
        family="数据库客户端",
        prefixes=("psycopg", "asyncpg", "sqlalchemy"),
    ),
    ForbiddenRule(
        family="缓存/对象存储",
        exact=frozenset({"redis", "minio"}),
        prefixes=("redis-", "boto", "minio-"),
    ),
    ForbiddenRule(
        family="任务队列/调度",
        exact=frozenset({"rq"}),
        prefixes=("celery", "dramatiq", "apscheduler", "rq-", "django-rq"),
    ),
    ForbiddenRule(
        family="模型 SDK",
        exact=frozenset({"openai", "anthropic"}),
        prefixes=("openai-", "anthropic-", "google-genai", "google-generativeai"),
    ),
    ForbiddenRule(
        family="本地推理",
        exact=frozenset({"torch", "transformers"}),
        prefixes=("torch-", "transformers-"),
    ),
)


def find_violations(names: Iterable[str]) -> list[tuple[str, str]]:
    """返回 ``(归一化包名, 所属家族)`` 命中列表，按包名排序去重。"""

    hits: set[tuple[str, str]] = set()
    for raw in names:
        normalized = normalize(raw)
        if not normalized:
            continue
        for rule in RULES:
            if normalized in rule.exact or normalized.startswith(rule.prefixes):
                hits.add((normalized, rule.family))
                break
    return sorted(hits)


def installed_distribution_names() -> list[str]:
    """当前解释器里所有已安装分发的名字（含传递依赖）。"""

    names: list[str] = []
    for distribution in importlib.metadata.distributions():
        raw = distribution.metadata["Name"]
        if raw:
            names.append(raw)
    return names


def lock_package_names() -> list[str]:
    """``uv.lock`` 里 ``[[package]]`` 的全部包名。"""

    with LOCK_FILE.open("rb") as handle:
        lock = tomllib.load(handle)
    packages = lock.get("package", [])
    if not isinstance(packages, list):
        return []
    names: list[str] = []
    for package in packages:
        if not isinstance(package, dict):
            continue
        name = package.get("name")
        if isinstance(name, str):
            names.append(name)
    return names


def requirement_names(specs: Iterable[str]) -> list[str]:
    """把 PEP 508 要求串截成裸包名，便于与禁入清单比对。"""

    names: list[str] = []
    for spec in specs:
        match = _REQUIREMENT_NAME.match(spec.strip())
        if match:
            names.append(match.group(0))
    return names


def declared_dependencies(project: object) -> list[str]:
    """``[project].dependencies`` 的包名。"""

    if not isinstance(project, dict):
        return []
    raw = project.get("dependencies", [])
    if not isinstance(raw, list):
        return []
    return requirement_names(item for item in raw if isinstance(item, str))


def declared_dependency_groups(groups: object) -> list[str]:
    """``[dependency-groups]`` 各组的包名（``include-group`` 字典项跳过）。"""

    if not isinstance(groups, dict):
        return []
    specs: list[str] = []
    for entries in groups.values():
        if not isinstance(entries, list):
            continue
        specs.extend(entry for entry in entries if isinstance(entry, str))
    return requirement_names(specs)


def pyproject_data() -> dict[str, object]:
    with PYPROJECT_FILE.open("rb") as handle:
        return tomllib.load(handle)


def format_failure(source: str, hits: list[tuple[str, str]]) -> str:
    detail = "、".join(f"{name}（{family}）" for name, family in hits)
    return f"检测到设计 §6.2 禁入依赖；来源：{source}；命中：{detail}"


def test_installed_distributions_have_no_forbidden_packages() -> None:
    hits = find_violations(installed_distribution_names())
    assert not hits, format_failure("已安装分发（importlib.metadata.distributions）", hits)


def test_uv_lock_has_no_forbidden_packages() -> None:
    hits = find_violations(lock_package_names())
    assert not hits, format_failure("uv.lock 的 [[package]]", hits)


def test_pyproject_dependencies_have_no_forbidden_packages() -> None:
    hits = find_violations(declared_dependencies(pyproject_data().get("project")))
    assert not hits, format_failure("pyproject.toml [project].dependencies", hits)


def test_pyproject_dependency_groups_have_no_forbidden_packages() -> None:
    hits = find_violations(declared_dependency_groups(pyproject_data().get("dependency-groups")))
    assert not hits, format_failure("pyproject.toml [dependency-groups]", hits)


def test_guard_itself_detects_representative_forbidden_packages() -> None:
    """守卫可证伪：代表性禁入包必须命中，当前合法依赖必须放行。"""

    forbidden = [
        "langchain-core",
        "llama-index-core",
        "psycopg2-binary",
        "botocore",
        "celery",
        "rq",
        "openai",
        "google-genai",
        "torch",
    ]
    hits = find_violations(forbidden)
    assert {name for name, _family in hits} == set(forbidden)
    allowed = ["fastapi", "pydantic", "pydantic_core", "httpx", "structlog", "uvicorn"]
    assert find_violations(allowed) == []
