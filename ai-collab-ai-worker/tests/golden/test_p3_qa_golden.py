"""P3 问答安全与装配金标准对拍（契约 §8.5 全文转义等价 / 装配等价）。

覆盖 ``p3_safety_golden.v1.jsonl``（escapeXmlText / k-gram 指纹 / 时间行 / 引用校验）
与 ``p3_context_golden.v1.jsonl``（TokenEstimator / 证据装配 / user payload 复渲染）。
"""

from __future__ import annotations

import datetime as dt
import uuid
from pathlib import Path

from p3_golden import iter_context, iter_safety

from ai_collab_worker.prompts.render import render_template
from ai_collab_worker.prompts.store import PromptStore
from ai_collab_worker.qa.assembly import (
    CandidateHit,
    KnowledgeContextBuilder,
    KnowledgeSource,
    estimate_tokens,
)
from ai_collab_worker.qa.citation import KnowledgeCitationValidator
from ai_collab_worker.qa.prompt_text import escape_xml_text, escape_xml_text_bounded
from ai_collab_worker.qa.safety import (
    canonical_fingerprint_digest,
    contains_any_fingerprint,
    fingerprint_set,
    inspect,
)


def test_escape_xml_golden_matches_java() -> None:
    for record in iter_safety("escape_xml"):
        escaped = escape_xml_text_bounded(record["input"], 2**31 - 1)
        assert escaped.escaped == record["escaped"], f"escape golden {record['id']}"
        assert escaped.original == record["original"], f"escape golden {record['id']}"


def test_escape_xml_bounded_golden_matches_java() -> None:
    for record in iter_safety("escape_xml_bounded"):
        escaped = escape_xml_text_bounded(record["input"], record["limit"])
        assert escaped.escaped == record["escaped"], f"escape bounded golden {record['id']}"
        assert escaped.original == record["original"], f"escape bounded golden {record['id']}"


def test_kgram_constants_match_java() -> None:
    from ai_collab_worker.qa.constants import BLOCKED_ANSWER, INSUFFICIENT_ANSWER, K_GRAM

    for record in iter_safety("constants"):
        assert K_GRAM == record["kGram"]
        assert BLOCKED_ANSWER == record["blockedAnswer"]
        assert INSUFFICIENT_ANSWER == record["insufficientAnswer"]
        assert 20 == record["minSourceContentLength"]


def test_kgram_fingerprint_digest_matches_java() -> None:
    for record in iter_safety("kgram_fingerprint"):
        fingerprints = fingerprint_set(record["systemPrompt"])
        assert len(fingerprints) == record["fingerprintCount"], f"fp golden {record['id']}"
        assert canonical_fingerprint_digest(fingerprints) == record["fingerprintSha256"], (
            f"fp golden {record['id']}"
        )


def test_kgram_inspect_and_contains_match_java() -> None:
    system_prompt = next(iter_safety("kgram_fingerprint"))["systemPrompt"]
    fingerprints = fingerprint_set(system_prompt)
    for record in iter_safety("kgram_inspect"):
        result = inspect(system_prompt, record["answer"])
        assert result.leaked == record["leaked"], f"inspect golden {record['id']}"
        assert result.matched_sample == record["matchedSample"], f"inspect golden {record['id']}"
    for record in iter_safety("kgram_contains"):
        actual = contains_any_fingerprint(record["text"], fingerprints)
        assert actual == record["contains"], f"contains golden {record['id']}"


def test_time_row_format_matches_java() -> None:
    for record in iter_safety("time_row"):
        at = dt.datetime.fromisoformat(record["iso"])
        actual = (
            "当前北京时间：" + at.strftime("%Y-%m-%d %H:%M")
            + "（UTC+8），日期计算必须基于此时区。\n"
        )
        assert actual == record["expected"], f"time golden {record['id']}"


def test_citation_validation_golden_matches_java() -> None:
    validator = KnowledgeCitationValidator()
    for record in iter_safety("citation"):
        sources = [_source_of(item) for item in record["sources"]]
        validated = validator.validate(record["answer"], sources)
        expected = record["expected"]
        context = f"citation golden {record['id']}"
        assert validated.answer == expected["answer"], context
        assert validated.insufficient_evidence == expected["insufficientEvidence"], context
        assert [source.rank for source in validated.cited_sources] == expected["citedRanks"], context
        assert validated.invalid_citation_count == expected["invalidCitationCount"], context
        assert validated.invalid_output == expected["invalidOutput"], context
        assert list(validated.unsupported_claims) == expected["unsupportedClaims"], context


def test_token_estimator_golden_matches_java() -> None:
    for record in iter_context("token_estimate"):
        assert estimate_tokens(record["text"]) == record["count"], f"token golden {record['id']}"


def test_assembly_golden_matches_java() -> None:
    builder = KnowledgeContextBuilder()
    for record in iter_context("assembly"):
        candidates = [
            CandidateHit(
                id=uuid.UUID(item["id"]),
                document_id=uuid.UUID(item["documentId"]),
                original_filename=item["originalFilename"],
                heading=item["heading"],
                content=item["content"],
                content_hash=item["contentHash"],
                similarity=item["similarity"],
                metadata=dict(item["metadata"]),
            )
            for item in record["candidates"]
        ]
        result = builder.build_with_token_budget(
            candidates, record["maxSources"], record["tokenBudget"]
        )
        expected = record["expected"]
        context = f"assembly golden {record['id']}"
        assert result.context.prompt_sources == expected["prompt"], context
        wanted_sources = expected["sources"]
        assert len(result.context.sources) == len(wanted_sources), context
        for source, wanted in zip(result.context.sources, wanted_sources, strict=True):
            assert source.chunk_id == uuid.UUID(wanted["chunkId"]), context
            assert source.document_id == uuid.UUID(wanted["documentId"]), context
            assert source.original_filename == wanted["originalFilename"], context
            assert source.heading == wanted["heading"], context
            assert source.content == wanted["content"], context
            assert source.content_hash == wanted["contentHash"], context
            assert source.rank == wanted["rank"], context
        actual_dropped = {str(key): reason.value for key, reason in result.drop_reasons.items()}
        assert actual_dropped == expected["dropped"], context


def test_user_payload_golden_matches_java() -> None:
    for record in iter_context("user_payload"):
        inputs = record["inputs"]
        variants = record["variants"]
        actual = {
            "plainQuestion": render_user_payload(inputs["plainQuestion"], inputs["sourcesBlock"], None),
            "injectedQuestion": render_user_payload(inputs["injectedQuestion"], inputs["sourcesBlock"], None),
            "placeholderQuestion": render_user_payload(
                inputs["placeholderQuestion"], inputs["sourcesBlock"], None
            ),
            "withContext": render_user_payload(
                inputs["withContextQuestion"], inputs["sourcesBlock"], inputs["withContext"]
            ),
        }
        for name, expected in variants.items():
            assert actual[name] == expected, f"payload golden {record['id']} variant={name}"


def render_user_payload(question: str, sources: str, context: str | None) -> str:
    """复刻 ``KnowledgePromptAssembler.userPayload``（模板 + 转义 + 上下文前缀）。"""

    store = PromptStore(Path(__file__).resolve().parents[2] / "prompts")
    document = store.get("knowledge.user", _current_version(store, "knowledge.user"))
    body = render_template(
        document.text,
        {"question": escape_xml_text(question), "sources": sources if sources is not None else ""},
    )
    if context is None or not context.strip():
        return body
    return context.strip() + "\n\n" + body


def _current_version(store: PromptStore, key: str) -> str:
    versions = [entry.version for entry in store.manifest().prompts if entry.key == key]
    return max(versions)


def _source_of(item: dict) -> KnowledgeSource:
    return KnowledgeSource(
        chunk_id=uuid.UUID(item["chunkId"]),
        document_id=uuid.UUID(item["documentId"]),
        original_filename=item["originalFilename"],
        heading=item["heading"],
        content=item["content"],
        content_hash=item["contentHash"],
        similarity=item["similarity"],
        rank=item["rank"],
        metadata=_metadata_of(item["metadata"]),
    )


def _metadata_of(raw: dict) -> dict:
    """按导出器的 ``__type`` 标记还原 metadata（UUID 值还原成 UUID 实例）。"""

    result: dict = {}
    for key, value in raw.items():
        if isinstance(value, dict) and value.get("__type") == "uuid":
            result[key] = uuid.UUID(value["value"])
        else:
            result[key] = value
    return result


def _is_uuid(value: str) -> bool:
    try:
        uuid.UUID(value)
        return True
    except ValueError:
        return False
