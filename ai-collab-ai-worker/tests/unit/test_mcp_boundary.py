"""MCP Host 边界静态检查：无外部 MCP/业务库依赖、无生产路由、无密钥变量。"""

from __future__ import annotations

import ast
import inspect
from pathlib import Path

from ai_collab_worker.clients.mcp_host import McpHostClient

PROJECT_ROOT = Path(__file__).resolve().parents[2]
MAIN_SOURCE = PROJECT_ROOT / "src" / "ai_collab_worker" / "main.py"
MCP_SOURCES = (
    PROJECT_ROOT / "src" / "ai_collab_worker" / "clients" / "mcp_host.py",
    PROJECT_ROOT / "src" / "ai_collab_worker" / "contract" / "mcp.py",
)

FORBIDDEN_TOP_LEVEL_IMPORTS = frozenset(
    {
        "anthropic",
        "asyncpg",
        "boto3",
        "fastapi",
        "minio",
        "mcp",
        "openai",
        "psycopg",
        "psycopg2",
        "redis",
        "sqlalchemy",
        "starlette",
    }
)


def _imported_top_level_modules(path: Path) -> set[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    modules: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            modules.update(alias.name.split(".", 1)[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            modules.add(node.module.split(".", 1)[0])
    return modules


def test_mcp_modules_have_no_external_mcp_business_or_provider_imports() -> None:
    for path in MCP_SOURCES:
        imported = _imported_top_level_modules(path)
        assert imported.isdisjoint(FORBIDDEN_TOP_LEVEL_IMPORTS), path


def test_mcp_modules_contain_no_secret_configuration_names() -> None:
    for path in MCP_SOURCES:
        source = path.read_text(encoding="utf-8")
        for marker in ("JWT_SECRET", "POSTGRES_", "_API_KEY"):
            assert marker not in source, f"{path} 不得引用 {marker}"


def test_mcp_client_has_no_service_token_or_arbitrary_url_parameter() -> None:
    parameters = inspect.signature(McpHostClient).parameters

    assert "base_url" in parameters
    assert "transport" in parameters
    assert "service_token" not in parameters
    assert "url" not in parameters
    assert "endpoint" not in parameters


def test_worker_app_does_not_expose_mcp_route_by_default() -> None:
    source = MAIN_SOURCE.read_text(encoding="utf-8")

    assert "mcp_host" not in source
    assert "api.mcp" not in source
    assert "mcp" not in source.lower()
