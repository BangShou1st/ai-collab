"""PromptPack 资产硬校验：仓库 ``prompts/`` 与 ``manifest.json`` 必须逐字段一致。

项目根用 ``Path(__file__).resolve().parents[2]`` 定位，因此不依赖工作目录；用例同时校验
``ai-prompts check`` 返回 0，保证 CI 与本地跑的是同一套口径。
"""

from __future__ import annotations

import shutil
from pathlib import Path

import pytest

from ai_collab_worker.contract.prompts import (
    PromptPackManifest,
    extract_variables,
    prompt_chars,
    prompt_sha256,
    prompt_text_from_bytes,
)
from ai_collab_worker.prompts import build

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
MANIFEST_PATH = PROMPTS_DIR / "manifest.json"

KEY = "knowledge.system"
VERSION = "2026-11-11.2"
EXPECTED_VARIABLES = ("timeContext",)


def _manifest_bytes() -> bytes:
    assert MANIFEST_PATH.is_file(), f"缺少 manifest：{MANIFEST_PATH}"
    return MANIFEST_PATH.read_bytes()


def _manifest() -> PromptPackManifest:
    return PromptPackManifest.model_validate_json(_manifest_bytes())


def _template_text(key: str, version: str) -> str:
    path = PROMPTS_DIR / key / f"{version}.md"
    assert path.is_file(), f"模板文件缺失：{key}/{version}.md"
    return prompt_text_from_bytes(path.read_bytes())


def test_manifest_file_is_utf8_lf_with_single_trailing_newline() -> None:
    data = _manifest_bytes()

    assert not data.startswith(b"\xef\xbb\xbf"), "manifest.json 不得带 UTF-8 BOM"
    text = data.decode("utf-8")
    assert "\r" not in text, "manifest.json 必须用 LF 换行"
    assert text.endswith("\n"), "manifest.json 必须以换行结尾"
    assert not text.endswith("\n\n"), "manifest.json 只能以恰好一个换行结尾"


def test_every_manifest_entry_matches_its_template() -> None:
    manifest = _manifest()

    assert manifest.schema_version == 1
    assert manifest.prompts, "manifest 不得为空"
    for entry in manifest.prompts:
        label = f"{entry.key}@{entry.version}"
        text = _template_text(entry.key, entry.version)
        assert prompt_sha256(text) == entry.sha256, f"sha256 不一致：{label}"
        assert prompt_chars(text) == entry.chars, f"chars 不一致：{label}"
        assert extract_variables(text) == list(entry.variables), f"variables 不一致：{label}"


def test_manifest_and_disk_templates_are_one_to_one() -> None:
    registered = [(entry.key, entry.version) for entry in _manifest().prompts]
    on_disk = {(path.parent.name, path.stem) for path in PROMPTS_DIR.glob("*/*.md")}

    assert len(registered) == len(set(registered)), "manifest 存在重复的 (key, version)"
    assert set(registered) == on_disk, "manifest 条目与磁盘模板必须一一对应"
    for key, version in on_disk:
        assert (PROMPTS_DIR / key / f"{version}.md").is_file(), f"路径与条目不一致：{key}"


def test_manifest_entries_are_sorted_by_key_and_version() -> None:
    references = [(entry.key, entry.version) for entry in _manifest().prompts]

    assert references == sorted(references)


def test_knowledge_system_prompt_has_exactly_one_variable() -> None:
    entries = [entry for entry in _manifest().prompts if entry.key == KEY]
    target = [entry for entry in entries if entry.version == VERSION]

    assert target, f"缺少 {KEY}@{VERSION}"
    assert target[0].variables == EXPECTED_VARIABLES
    assert extract_variables(_template_text(KEY, VERSION)) == list(EXPECTED_VARIABLES)


def test_manifest_file_equals_cli_rebuild() -> None:
    expected = build.render_manifest(build.build_manifest(PROMPTS_DIR))

    assert MANIFEST_PATH.read_text(encoding="utf-8") == expected


def test_ai_prompts_check_exits_zero(capsys: pytest.CaptureFixture[str]) -> None:
    assert build.main(["check"]) == 0
    assert "prompts OK" in capsys.readouterr().out


def test_ai_prompts_check_is_independent_of_working_directory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)

    assert build.main(["check"]) == 0


def test_ai_prompts_check_reports_missing_pack(tmp_path: Path) -> None:
    assert build.main(["check", "--prompts-dir", str(tmp_path / "absent")]) == 1


def test_ai_prompts_detects_drift_and_write_repairs_it(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    pack = tmp_path / "prompts"
    shutil.copytree(PROMPTS_DIR, pack)
    template = pack / KEY / f"{VERSION}.md"
    body = template.read_bytes().decode("utf-8").rstrip("\n")
    template.write_text(body + "。\n", encoding="utf-8", newline="\n")

    assert build.main(["check", "--prompts-dir", str(pack)]) == 1
    assert "sha256 不一致" in capsys.readouterr().err

    assert build.main(["write", "--prompts-dir", str(pack)]) == 0
    assert build.main(["check", "--prompts-dir", str(pack)]) == 0


def test_ai_prompts_write_ignores_manifest_and_orphan_files(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    pack = tmp_path / "prompts"
    shutil.copytree(PROMPTS_DIR, pack)
    (pack / "README.md").write_text("# 说明\n", encoding="utf-8", newline="\n")
    (pack / MANIFEST_PATH.name).write_text("{}\n", encoding="utf-8", newline="\n")

    assert build.main(["write", "--prompts-dir", str(pack)]) == 0
    assert build.main(["check", "--prompts-dir", str(pack)]) == 0
    capsys.readouterr()
    assert (pack / MANIFEST_PATH.name).read_text(encoding="utf-8") == build.render_manifest(
        build.build_manifest(pack)
    )
