"""P2 Java 内部客户端扩展（文档正文分页与索引任务快照）的单元测试。"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import uuid
from typing import Any

import httpx
import pytest

from ai_collab_worker.clients.java_internal import (
    CODE_INDEX_SNAPSHOT_STALE,
    REASON_CONFLICT,
    REASON_CONTRACT,
    REASON_NOT_FOUND,
    DeleteDocumentIndexSnapshot,
    DocumentBodyPage,
    IndexDocumentSnapshot,
    JavaInternalClient,
    JavaInternalError,
    ReindexGenerationSnapshot,
)
from ai_collab_worker.contract.knowledge_search import ChunkType

BASE = "http://java.test"
TOKEN = "eyJ.p2-unit-token.sig"
TASK_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
PROJECT_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
DOC_ID = uuid.UUID("44444444-4444-4444-8444-444444444444")
SNAPSHOT_ID = uuid.UUID("55555555-5555-4555-8555-555555555555")
GEN_ID = uuid.UUID("66666666-6666-4666-8666-666666666666")


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _client(transport: httpx.MockTransport) -> JavaInternalClient:
    return JavaInternalClient(base_url=BASE, transport=transport)


def test_get_document_body_page_success_and_query_params() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(
            200,
            json={
                "documentId": str(DOC_ID),
                "projectId": str(PROJECT_ID),
                "bodySnapshotId": str(SNAPSHOT_ID),
                "parseVersion": "tika-page-clean-v2/chunk-offset-v2",
                "totalChunks": 2,
                "chunks": [
                    {
                        "chunkNo": 0,
                        "chunkType": "PARENT",
                        "parentChunkNo": None,
                        "heading": "概览",
                        "content": "父块全文内容",
                        "contentHash": _sha("父块全文内容"),
                        "metadata": {"charFrom": 0, "charThrough": 6, "pageNumber": 1},
                    },
                    {
                        "chunkNo": 1,
                        "chunkType": "CHILD",
                        "parentChunkNo": 0,
                        "heading": "概览",
                        "content": "子块正文片段",
                        "contentHash": _sha("子块正文片段"),
                        "metadata": {"charFrom": 0, "charThrough": 6, "isTable": False},
                    },
                ],
                "nextCursor": "cur-next",
            },
        )

    async def main() -> DocumentBodyPage:
        client = _client(httpx.MockTransport(handler))
        try:
            return await client.get_document_body_page(
                DOC_ID, cursor="cur-1", limit=50, bearer_token=TOKEN
            )
        finally:
            await client.aclose()

    page = asyncio.run(main())
    assert len(seen) == 1
    req = seen[0]
    assert req.method == "GET"
    assert req.url.path == f"/internal/ai/documents/{DOC_ID}/body"
    assert dict(req.url.params) == {"limit": "50", "cursor": "cur-1"}
    assert req.headers["authorization"] == f"Bearer {TOKEN}"
    assert page.document_id == DOC_ID
    assert page.body_snapshot_id == SNAPSHOT_ID
    assert page.total_chunks == 2
    assert page.next_cursor == "cur-next"
    assert page.chunks[0].chunk_type is ChunkType.PARENT
    assert page.chunks[1].chunk_type is ChunkType.CHILD
    assert page.chunks[1].parent_chunk_no == 0


def test_get_document_body_page_409_stale_and_404_not_retryable(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.DEBUG)
    responses = iter(
        [
            httpx.Response(409, json={"code": CODE_INDEX_SNAPSHOT_STALE, "message": "stale"}),
            httpx.Response(404, json={"code": "NOT_FOUND", "message": "secret body text"}),
        ]
    )

    def handler(_request: httpx.Request) -> httpx.Response:
        return next(responses)

    async def main() -> tuple[JavaInternalError, JavaInternalError]:
        client = _client(httpx.MockTransport(handler))
        errors: list[JavaInternalError] = []
        try:
            for _ in range(2):
                with pytest.raises(JavaInternalError) as exc_info:
                    await client.get_document_body_page(DOC_ID, bearer_token=TOKEN)
                errors.append(exc_info.value)
        finally:
            await client.aclose()
        return errors[0], errors[1]

    stale_err, not_found_err = asyncio.run(main())
    assert stale_err.status == 409
    assert stale_err.reason == REASON_CONFLICT
    assert stale_err.code == CODE_INDEX_SNAPSHOT_STALE
    assert stale_err.retryable is False
    assert stale_err.is_snapshot_stale is True

    assert not_found_err.status == 404
    assert not_found_err.reason == REASON_NOT_FOUND
    assert not_found_err.retryable is False
    assert not_found_err.is_snapshot_stale is False
    assert TOKEN not in caplog.text
    assert "secret body text" not in caplog.text


def test_get_document_body_page_rejects_bad_args_and_contract_mismatch() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "documentId": str(uuid.uuid4()),  # 文档 ID 不一致
                "projectId": str(PROJECT_ID),
                "bodySnapshotId": str(SNAPSHOT_ID),
                "totalChunks": 0,
                "chunks": [],
            },
        )

    async def main() -> None:
        client = _client(httpx.MockTransport(handler))
        try:
            with pytest.raises(ValueError, match="limit"):
                await client.get_document_body_page(DOC_ID, limit=0, bearer_token=TOKEN)
            with pytest.raises(ValueError, match="limit"):
                await client.get_document_body_page(DOC_ID, limit=201, bearer_token=TOKEN)
            with pytest.raises(ValueError, match="doc_id"):
                await client.get_document_body_page("bad/id", bearer_token=TOKEN)
            with pytest.raises(JavaInternalError) as exc_info:
                await client.get_document_body_page(DOC_ID, bearer_token=TOKEN)
            assert exc_info.value.reason == REASON_CONTRACT
            assert exc_info.value.retryable is False
        finally:
            await client.aclose()

    asyncio.run(main())


@pytest.mark.parametrize(
    ("task_type", "payload", "expected_cls"),
    [
        (
            "INDEX_DOCUMENT",
            {
                "schemaVersion": 1,
                "taskId": str(TASK_ID),
                "taskType": "INDEX_DOCUMENT",
                "projectId": str(PROJECT_ID),
                "documentId": str(DOC_ID),
                "generationId": None,
                "bodySnapshotId": str(SNAPSHOT_ID),
                "parseVersion": "tika-page-clean-v2/chunk-offset-v2",
                "bodyChunkCount": 0,
            },
            IndexDocumentSnapshot,
        ),
        (
            "DELETE_DOCUMENT_INDEX",
            {
                "projectId": str(PROJECT_ID),
                "documentId": str(DOC_ID),
            },
            DeleteDocumentIndexSnapshot,
        ),
        (
            "REINDEX_GENERATION",
            {
                "taskId": str(TASK_ID),
                "taskType": "REINDEX_GENERATION",
            },
            ReindexGenerationSnapshot,
        ),
    ],
)
def test_snapshot_supports_all_index_task_shapes(
    task_type: str,
    payload: dict[str, Any],
    expected_cls: type[object],
) -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=payload)

    async def main() -> object:
        client = _client(httpx.MockTransport(handler))
        try:
            return await client.snapshot(str(TASK_ID), bearer_token=TOKEN, task_type=task_type)
        finally:
            await client.aclose()

    snap = asyncio.run(main())
    assert isinstance(snap, expected_cls)


def test_snapshot_without_task_type_preserves_dict_and_rejects_mismatched_task_id() -> None:
    responses = iter(
        [
            httpx.Response(200, json={"schemaVersion": 1, "jobId": str(TASK_ID), "facts": []}),
            httpx.Response(
                200,
                json={
                    "schemaVersion": 1,
                    "taskId": str(uuid.uuid4()),
                    "taskType": "REINDEX_GENERATION",
                },
            ),
        ]
    )

    def handler(_request: httpx.Request) -> httpx.Response:
        return next(responses)

    async def main() -> dict[str, Any]:
        client = _client(httpx.MockTransport(handler))
        try:
            raw = await client.snapshot(str(TASK_ID), bearer_token=TOKEN)
            with pytest.raises(JavaInternalError) as exc_info:
                await client.snapshot(
                    str(TASK_ID), bearer_token=TOKEN, task_type="REINDEX_GENERATION"
                )
            assert exc_info.value.reason == REASON_CONTRACT
            assert exc_info.value.retryable is False
            return raw
        finally:
            await client.aclose()

    raw = asyncio.run(main())
    assert raw["jobId"] == str(TASK_ID)
