"""AI Collab Python 认知面（P0 骨架 + P1 rerank）。

本包包含：跨语言契约（``contract``）、PromptPack 资产与服务、rerank 认知层与 Java
内部模型网关客户端、健康检查与 Prometheus 指标、离线评测 CLI（``ai-eval``）。它不连接
数据库/Redis/MinIO，不持有任何 Provider 密钥；出站只在 rerank 时调用 Java 内部模型网关。
"""

from __future__ import annotations

from typing import Final

__version__: Final[str] = "0.1.0"
SERVICE_NAME: Final[str] = "ai-collab-ai-worker"

__all__ = ["SERVICE_NAME", "__version__"]
