"""AI Collab Python 认知面（P0 骨架）。

本包在 P0 只包含：跨语言契约（``contract``）、PromptPack 资产与服务、健康检查与
Prometheus 指标、离线评测 CLI（``ai-eval``）。它不连接数据库/Redis/MinIO，不持有任何
Provider 密钥，也不发起任何出站调用。
"""

from __future__ import annotations

from typing import Final

__version__: Final[str] = "0.1.0"
SERVICE_NAME: Final[str] = "ai-collab-ai-worker"

__all__ = ["SERVICE_NAME", "__version__"]
