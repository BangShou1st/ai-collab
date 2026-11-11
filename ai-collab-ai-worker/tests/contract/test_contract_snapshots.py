"""契约快照的双向校验：重生成必须与入库文件逐字节一致。"""

from __future__ import annotations

import json

from ai_collab_worker.contract.schema import (
    SCHEMA_MODELS,
    check,
    render_all,
    render_schema,
    snapshot_path,
)


def test_snapshots_match_models() -> None:
    """入库快照 == 当前模型渲染结果（破坏性字段改动会在这里直接红）。"""

    assert check() == []


def test_every_registered_model_has_snapshot() -> None:
    for name in SCHEMA_MODELS:
        path = snapshot_path(name)
        assert path.is_file(), f"缺少快照 {path}"
        assert path.read_text(encoding="utf-8").strip(), f"快照为空 {path}"


def test_snapshot_directory_has_no_unregistered_files() -> None:
    names = {path.name for path in snapshot_path("prompt-manifest").parent.glob("*.schema.json")}
    assert names == {f"{name}.schema.json" for name in SCHEMA_MODELS}


def test_schema_rendering_is_stable() -> None:
    first = render_all()
    second = render_all()
    assert first == second
    for text in first.values():
        assert text.startswith("{")
        assert text.endswith("}\n")


def test_schemas_declare_schema_version_one() -> None:
    """跨语言响应模型必须自带 schemaVersion=1；错误信封按 §7.1 不带。"""

    for name in ("common-request", "prompt-document", "prompt-manifest"):
        schema = json.loads(render_schema(SCHEMA_MODELS[name]))
        assert schema["properties"]["schemaVersion"]["const"] == 1
        assert schema["properties"]["schemaVersion"]["default"] == 1


def test_schemas_forbid_extra_fields() -> None:
    for name in SCHEMA_MODELS:
        schema = json.loads(render_schema(SCHEMA_MODELS[name]))
        assert schema["additionalProperties"] is False, name


def test_schema_titles_are_model_names() -> None:
    expected = {
        "common-request": "CommonRequest",
        "error-envelope": "ErrorEnvelope",
        "prompt-document": "PromptDocument",
        "prompt-manifest": "PromptManifest",
    }
    for name, title in expected.items():
        assert json.loads(render_schema(SCHEMA_MODELS[name]))["title"] == title
