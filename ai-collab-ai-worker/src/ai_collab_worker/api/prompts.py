"""PromptPack 查询端点（设计 §7.2）：manifest 与单条 Prompt 文档。

两个端点都是只读、无副作用、可缓存；错误一律 ``raise AppError``，由
``ai_collab_worker.api.errors.install_error_handlers`` 渲染成统一信封。
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Request

from ai_collab_worker.contract.prompts import PromptDocument, PromptManifest
from ai_collab_worker.prompts.store import PromptStore

router = APIRouter(prefix="/v1/prompts", tags=["prompts"])


def get_prompt_store(request: Request) -> PromptStore:
    """从 ``app.state.prompt_store`` 取存储；未配置属于装配错误（500 INTERNAL）。"""

    store = getattr(request.app.state, "prompt_store", None)
    if not isinstance(store, PromptStore):
        msg = "prompt_store 未配置"
        raise RuntimeError(msg)
    return store


PromptStoreDep = Annotated[PromptStore, Depends(get_prompt_store)]


@router.get(
    "/manifest",
    response_model=PromptManifest,
    summary="PromptPack manifest（每项 key/version/sha256/chars，按 (key,version) 升序）",
)
def read_prompt_manifest(store: PromptStoreDep) -> PromptManifest:
    """返回 manifest：只含设计 §7.2 规定的四个字段，不含 ``variables``。"""

    return store.manifest()


@router.get(
    "/{key}/{version}",
    response_model=PromptDocument,
    summary="按 (key,version) 取用 Prompt 模板正文（取用时复算 sha256/variables）",
)
def read_prompt_document(key: str, version: str, store: PromptStoreDep) -> PromptDocument:
    """返回模板正文与校验字段；资产损坏/不存在/格式非法分别由 store 抛 503/404/400。"""

    return store.get(key, version)


__all__ = ["get_prompt_store", "router"]
