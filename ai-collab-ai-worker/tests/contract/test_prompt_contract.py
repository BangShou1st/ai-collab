"""PromptPack 契约测试：占位符、编码口径、sha256/chars 与 manifest 排序。"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from ai_collab_worker.contract.prompts import (
    PromptDocument,
    PromptManifest,
    PromptManifestEntry,
    PromptPackEntry,
    PromptPackManifest,
    extract_variables,
    prompt_chars,
    prompt_sha256,
    prompt_text_from_bytes,
)

TEMPLATE = "你是助手。\n语言：{{ locale }}\n范围：{{ docScope }}\n"


def _document(**overrides: object) -> PromptDocument:
    text = str(overrides.pop("text", TEMPLATE.rstrip("\n")))
    payload: dict[str, object] = {
        "key": "knowledge.system",
        "version": "2026-11-11.1",
        "sha256": prompt_sha256(text),
        "text": text,
        "variables": ("docScope", "locale"),
    }
    payload.update(overrides)
    return PromptDocument.model_validate(payload)


def test_extract_variables_sorted_and_unique() -> None:
    assert extract_variables("{{ b }} {{a}} {{ a }} {single} {{not a name}}") == ["a", "b"]
    assert extract_variables("没有变量") == []


def test_prompt_text_from_bytes_normalizes_crlf() -> None:
    assert prompt_text_from_bytes(b"a\r\nb\r\n") == "a\nb"


def test_prompt_text_from_bytes_rejects_bom() -> None:
    with pytest.raises(ValueError, match="BOM"):
        prompt_text_from_bytes("\ufeffhello\n".encode())


def test_prompt_text_from_bytes_requires_single_trailing_newline() -> None:
    with pytest.raises(ValueError, match="换行结尾"):
        prompt_text_from_bytes(b"hello")
    with pytest.raises(ValueError, match="恰好一个"):
        prompt_text_from_bytes(b"hello\n\n")
    with pytest.raises(ValueError, match="不能为空"):
        prompt_text_from_bytes(b"\n")


def test_prompt_text_from_bytes_rejects_non_utf8() -> None:
    with pytest.raises(ValueError, match="UTF-8"):
        prompt_text_from_bytes(b"\xff\xfe")


def test_prompt_chars_counts_code_points() -> None:
    assert prompt_chars("中文{{x}}") == 7
    assert prompt_chars("👍") == 1


def test_prompt_document_accepts_consistent_payload() -> None:
    document = _document()
    assert document.schema_version == 1
    assert document.variables == ("docScope", "locale")
    dumped = document.model_dump(by_alias=True, mode="json", exclude_none=True)
    assert dumped["schemaVersion"] == 1
    assert dumped["sha256"] == prompt_sha256(TEMPLATE.rstrip("\n"))


def test_prompt_document_rejects_sha_mismatch() -> None:
    with pytest.raises(ValidationError, match="sha256"):
        _document(sha256="0" * 64)


def test_prompt_document_rejects_variable_mismatch() -> None:
    with pytest.raises(ValidationError, match="variables"):
        _document(variables=("docScope",))
    with pytest.raises(ValidationError, match="variables"):
        _document(variables=("docScope", "locale", "extra"))


def test_prompt_document_rejects_unsorted_variables() -> None:
    with pytest.raises(ValidationError, match="variables"):
        _document(variables=("locale", "docScope"))


def test_prompt_manifest_requires_sorted_unique_entries() -> None:
    first = PromptManifestEntry(
        key="knowledge.system", version="2026-11-11.1", sha256="a" * 64, chars=10
    )
    second = PromptManifestEntry(
        key="scenario.project_research", version="2026-11-11.1", sha256="b" * 64, chars=20
    )
    assert PromptManifest(prompts=(first, second)).prompts == (first, second)
    with pytest.raises(ValidationError, match="升序"):
        PromptManifest(prompts=(second, first))
    with pytest.raises(ValidationError, match="重复"):
        PromptManifest(prompts=(first, first))


def test_prompt_pack_manifest_requires_sorted_variables() -> None:
    entry = PromptPackEntry(
        key="knowledge.system",
        version="2026-11-11.1",
        sha256="a" * 64,
        chars=10,
        variables=("docScope", "locale"),
    )
    assert PromptPackManifest(prompts=(entry,)).prompts[0].variables == ("docScope", "locale")
    with pytest.raises(ValidationError, match="variables"):
        PromptPackEntry(
            key="knowledge.system",
            version="2026-11-11.1",
            sha256="a" * 64,
            chars=10,
            variables=("locale", "docScope"),
        )


def test_prompt_key_and_version_patterns() -> None:
    with pytest.raises(ValidationError):
        PromptManifestEntry(
            key="Knowledge.System", version="2026-11-11.1", sha256="a" * 64, chars=1
        )
    with pytest.raises(ValidationError):
        PromptManifestEntry(key="knowledge", version="2026-11-11.1", sha256="a" * 64, chars=1)
    with pytest.raises(ValidationError):
        PromptManifestEntry(key="knowledge.system", version="v1", sha256="a" * 64, chars=1)
    with pytest.raises(ValidationError):
        PromptManifestEntry(
            key="knowledge.system", version="2026-11-11.1", sha256="A" * 64, chars=0
        )


def test_manifest_wire_shape_has_exactly_four_fields() -> None:
    entry = PromptManifestEntry(
        key="knowledge.system", version="2026-11-11.1", sha256="a" * 64, chars=10
    )
    dumped = PromptManifest(prompts=(entry,)).model_dump(by_alias=True, mode="json")
    assert set(dumped) == {"schemaVersion", "prompts"}
    assert set(dumped["prompts"][0]) == {"key", "version", "sha256", "chars"}
