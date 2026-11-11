"""pytest 全局夹具。

P0 的测试分三层：``tests/unit``（纯函数）、``tests/contract``（契约与快照）、
``tests/integration``（TestClient 打真实 app）。三层都不连接任何外部依赖。
"""

from __future__ import annotations

from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture(scope="session")
def project_root() -> Path:
    """``ai-collab-ai-worker/`` 目录。"""

    return PROJECT_ROOT


@pytest.fixture(scope="session")
def prompts_dir(project_root: Path) -> Path:
    """仓库内的 PromptPack 目录。"""

    return project_root / "prompts"
