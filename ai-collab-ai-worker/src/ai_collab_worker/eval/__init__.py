"""``ai-eval`` 离线评测：冻结集读取、canonical JSON 与 SHA-256 指纹。

模块入口是 :mod:`ai_collab_worker.eval.canonical`；CLI 由 console script
``ai-eval`` 与 ``python -m ai_collab_worker.eval.cli`` 共用同一个 ``main``。
"""

from __future__ import annotations

from ai_collab_worker.eval.canonical import (
    DatasetError,
    canonicalize_cases,
    canonicalize_path,
    digest,
    load_dataset,
)

__all__ = ["DatasetError", "canonicalize_cases", "canonicalize_path", "digest", "load_dataset"]
