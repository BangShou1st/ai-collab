"""AI Collab Python 认知面。

本包包含：跨语言契约（``contract``）、PromptPack 资产与服务、query plan 与 memory job 认知层、
Java 内部模型网关/Job 客户端、内部 MCP Host、健康检查与 Prometheus 指标、离线评测 CLI
（``ai-eval``）。它不连接数据库/Redis/MinIO，不持有任何 Provider 密钥；内部端点只接受 Java
签发的 Ed25519 短令牌，出站只调用 Java 内部端点。
"""

from __future__ import annotations

from typing import Final

__version__: Final[str] = "0.1.0"
SERVICE_NAME: Final[str] = "ai-collab-ai-worker"

__all__ = ["SERVICE_NAME", "__version__"]
