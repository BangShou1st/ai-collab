"""``PromptStore`` 单元测试：manifest 形状、每次取用重读校验、错误码与就绪原因。

全部用例用 ``tmp_path`` 构造合法/损坏的 PromptPack，不读仓库资产、不碰网络。
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ai_collab_worker.contract.common import AppError, ErrorCode
from ai_collab_worker.contract.prompts import (
    extract_variables,
    prompt_chars,
    prompt_sha256,
)
from ai_collab_worker.prompts.store import PromptStore

KEY = "knowledge.system"
VERSION = "2026-11-11.1"
BODY = "你是助手。\n语言：{{ locale }}\n范围：{{ docScope }}"
SCENARIO_KEY = "scenario.project_research"


def _entry(key: str, version: str, body: str, **overrides: object) -> dict[str, object]:
    entry: dict[str, object] = {
        "key": key,
        "version": version,
        "sha256": prompt_sha256(body),
        "chars": prompt_chars(body),
        "variables": extract_variables(body),
    }
    entry.update(overrides)
    return entry


def _write_template(root: Path, key: str, version: str, body: str) -> Path:
    path = root / key / f"{version}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((body + "\n").encode("utf-8"))
    return path


def _write_manifest(root: Path, entries: list[dict[str, object]]) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    path = root / "manifest.json"
    payload = {"schemaVersion": 1, "prompts": entries}
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8", newline="\n")
    return path


def _make_store(
    root: Path, *, body: str = BODY, entry: dict[str, object] | None = None
) -> PromptStore:
    _write_template(root, KEY, VERSION, body)
    _write_manifest(root, [entry if entry is not None else _entry(KEY, VERSION, body)])
    return PromptStore(root)


def _reason(error: AppError) -> str:
    assert error.details is not None
    assert "reason" in error.details
    return str(error.details["reason"])


def _assert_unavailable(error: AppError) -> None:
    assert error.code is ErrorCode.PROMPT_UNAVAILABLE
    assert error.status_code == 503
    assert error.retryable is False


def test_manifest_exposes_four_fields_in_sorted_order(tmp_path: Path) -> None:
    scenario_body = "研究：{{ locale }}"
    _write_template(tmp_path, SCENARIO_KEY, VERSION, scenario_body)
    _write_template(tmp_path, KEY, VERSION, BODY)
    _write_manifest(
        tmp_path,
        [_entry(KEY, VERSION, BODY), _entry(SCENARIO_KEY, VERSION, scenario_body)],
    )

    manifest = PromptStore(tmp_path).manifest()

    assert manifest.schema_version == 1
    assert [(entry.key, entry.version) for entry in manifest.prompts] == [
        (KEY, VERSION),
        (SCENARIO_KEY, VERSION),
    ]
    payload = manifest.model_dump(by_alias=True, mode="json")
    assert set(payload) == {"schemaVersion", "prompts"}
    assert set(payload["prompts"][0]) == {"key", "version", "sha256", "chars"}
    assert payload["prompts"][0]["sha256"] == prompt_sha256(BODY)
    assert payload["prompts"][0]["chars"] == prompt_chars(BODY)


def test_get_returns_document_with_matching_sha_and_variables(tmp_path: Path) -> None:
    document = _make_store(tmp_path).get(KEY, VERSION)

    assert document.schema_version == 1
    assert document.key == KEY
    assert document.version == VERSION
    assert document.text == BODY
    assert document.sha256 == prompt_sha256(BODY)
    assert document.variables == ("docScope", "locale")


def test_get_rereads_template_from_disk_on_every_call(tmp_path: Path) -> None:
    store = _make_store(tmp_path)
    store.get(KEY, VERSION)
    (tmp_path / KEY / f"{VERSION}.md").unlink()

    with pytest.raises(AppError) as excinfo:
        store.get(KEY, VERSION)

    _assert_unavailable(excinfo.value)
    assert "missing" in _reason(excinfo.value)


def test_get_rejects_tampered_template(tmp_path: Path) -> None:
    store = _make_store(tmp_path)
    (tmp_path / KEY / f"{VERSION}.md").write_bytes((BODY + "\n补充一行\n").encode("utf-8"))

    with pytest.raises(AppError) as excinfo:
        store.get(KEY, VERSION)

    _assert_unavailable(excinfo.value)
    assert "sha256" in _reason(excinfo.value)


def test_manifest_chars_mismatch_is_unavailable(tmp_path: Path) -> None:
    entry = _entry(KEY, VERSION, BODY, chars=prompt_chars(BODY) + 1)
    store = _make_store(tmp_path, entry=entry)

    with pytest.raises(AppError) as excinfo:
        store.get(KEY, VERSION)

    _assert_unavailable(excinfo.value)
    assert "chars" in _reason(excinfo.value)


def test_manifest_variables_mismatch_is_unavailable(tmp_path: Path) -> None:
    store = _make_store(tmp_path, entry=_entry(KEY, VERSION, BODY, variables=["docScope"]))

    with pytest.raises(AppError) as excinfo:
        store.get(KEY, VERSION)

    _assert_unavailable(excinfo.value)
    assert "variables" in _reason(excinfo.value)


def test_bom_template_is_rejected(tmp_path: Path) -> None:
    store = _make_store(tmp_path)
    (tmp_path / KEY / f"{VERSION}.md").write_bytes(b"\xef\xbb\xbf" + (BODY + "\n").encode())

    with pytest.raises(AppError) as excinfo:
        store.get(KEY, VERSION)

    _assert_unavailable(excinfo.value)
    assert "encoding" in _reason(excinfo.value)


def test_crlf_template_is_normalized_and_accepted(tmp_path: Path) -> None:
    store = _make_store(tmp_path)
    crlf = (BODY + "\n").replace("\n", "\r\n").encode("utf-8")
    (tmp_path / KEY / f"{VERSION}.md").write_bytes(crlf)

    document = store.get(KEY, VERSION)

    assert document.text == BODY


def test_missing_template_keeps_store_unready(tmp_path: Path) -> None:
    _write_manifest(tmp_path, [_entry(KEY, VERSION, BODY)])
    store = PromptStore(tmp_path)

    failure = store.readiness_failure()
    assert failure is not None
    assert "missing" in failure
    assert str(tmp_path) not in failure
    assert "你是助手" not in failure

    with pytest.raises(AppError) as excinfo:
        store.manifest()

    _assert_unavailable(excinfo.value)
    assert str(tmp_path) not in _reason(excinfo.value)


def test_missing_manifest_keeps_store_unready(tmp_path: Path) -> None:
    store = PromptStore(tmp_path)

    failure = store.readiness_failure()
    assert failure is not None
    assert "manifest" in failure
    assert str(tmp_path) not in failure


def test_invalid_manifest_json_keeps_store_unready(tmp_path: Path) -> None:
    (tmp_path / "manifest.json").write_text("{not json", encoding="utf-8")
    store = PromptStore(tmp_path)

    failure = store.readiness_failure()
    assert failure is not None
    assert "JSON" in failure


def test_manifest_schema_violation_keeps_store_unready(tmp_path: Path) -> None:
    _write_manifest(tmp_path, [{"key": KEY, "version": VERSION, "chars": 1}])
    store = PromptStore(tmp_path)

    failure = store.readiness_failure()
    assert failure is not None
    assert "schema" in failure


def test_get_on_unready_store_is_unavailable(tmp_path: Path) -> None:
    store = PromptStore(tmp_path)

    with pytest.raises(AppError) as excinfo:
        store.get(KEY, VERSION)

    _assert_unavailable(excinfo.value)


def test_format_error_wins_over_unready_store(tmp_path: Path) -> None:
    store = PromptStore(tmp_path)

    with pytest.raises(AppError) as excinfo:
        store.get(KEY, "v1")

    assert excinfo.value.code is ErrorCode.INVALID_REQUEST
    assert excinfo.value.status_code == 400


@pytest.mark.parametrize(
    ("key", "version"),
    [
        (KEY, "v1"),
        ("Knowledge.System", VERSION),
        ("knowledge", VERSION),
        (KEY, "2026-11-11"),
        (KEY, "2026-11-11.1.1"),
        (f"{KEY}x" * 40, VERSION),
    ],
)
def test_invalid_key_or_version_format_returns_400(tmp_path: Path, key: str, version: str) -> None:
    store = _make_store(tmp_path)

    with pytest.raises(AppError) as excinfo:
        store.get(key, version)

    assert excinfo.value.code is ErrorCode.INVALID_REQUEST
    assert excinfo.value.status_code == 400


@pytest.mark.parametrize(
    ("key", "version"),
    [("knowledge.unknown", VERSION), (KEY, "2026-11-12.1")],
)
def test_unknown_prompt_returns_404_without_paths(tmp_path: Path, key: str, version: str) -> None:
    store = _make_store(tmp_path)

    with pytest.raises(AppError) as excinfo:
        store.get(key, version)

    error = excinfo.value
    assert error.code is ErrorCode.PROMPT_UNAVAILABLE
    assert error.status_code == 404
    assert error.retryable is False
    assert error.details == {"key": key, "version": version}
    assert str(tmp_path) not in json.dumps(error.details, ensure_ascii=False)


def test_readiness_failure_is_none_when_ready(tmp_path: Path) -> None:
    assert _make_store(tmp_path).readiness_failure() is None


def test_refresh_recovers_after_assets_are_fixed(tmp_path: Path) -> None:
    _write_manifest(tmp_path, [_entry(KEY, VERSION, BODY)])
    store = PromptStore(tmp_path)
    assert store.readiness_failure() is not None

    _write_template(tmp_path, KEY, VERSION, BODY)
    store.refresh()

    assert store.readiness_failure() is None
    assert store.get(KEY, VERSION).text == BODY


def test_refresh_after_corruption_drops_cached_manifest(tmp_path: Path) -> None:
    store = _make_store(tmp_path)
    assert store.manifest().prompts

    (tmp_path / "manifest.json").write_text("{not json", encoding="utf-8")
    store.refresh()

    assert store.readiness_failure() is not None
    with pytest.raises(AppError) as excinfo:
        store.manifest()

    _assert_unavailable(excinfo.value)
