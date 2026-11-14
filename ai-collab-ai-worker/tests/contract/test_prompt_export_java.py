"""``ai-prompts export-java`` 的契约测试：产物形状、多余文件清理与 ``--check`` 差异。

用例全部在 ``tmp_path`` 上跑，不触碰仓库内的 Java fallback 包；真实包的逐字节一致性
由 ``tests/contract/test_java_promptpack_sync.py`` 守护。
"""

from __future__ import annotations

import shutil
from pathlib import Path

import pytest

from ai_collab_worker.prompts import build

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
MANIFEST_NAME = build.MANIFEST_FILENAME
TEXTS = build.JAVA_TEXTS_DIRNAME


def _relative_files(root: Path) -> set[str]:
    return {path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file()}


def _export(target: Path, *extra: str) -> int:
    return build.main(["export-java", "--target-dir", str(target), *extra])


def test_export_writes_manifest_and_texts_byte_identical(tmp_path: Path) -> None:
    target = tmp_path / "promptpack"

    assert _export(target) == 0

    assert (target / MANIFEST_NAME).read_bytes() == (PROMPTS_DIR / MANIFEST_NAME).read_bytes()
    templates = sorted(PROMPTS_DIR.glob("*/*.md"))
    assert templates, "prompts/ 下没有任何模板"
    for template in templates:
        exported = target / TEXTS / template.parent.name / template.name
        assert exported.is_file(), f"缺少导出文件：{exported}"
        assert exported.read_bytes() == template.read_bytes(), f"字节不同：{exported}"
    assert len(_relative_files(target)) == len(templates) + 1


def test_export_removes_stale_files_and_prunes_empty_dirs(tmp_path: Path) -> None:
    target = tmp_path / "promptpack"
    stale_version = target / TEXTS / "knowledge.system" / "2026-11-11.1.md"
    orphan = target / TEXTS / "scenario.retired.instruction" / "2026-01-01.1.md"
    for path in (stale_version, orphan):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"retired body\n")
    (target / "README.md").write_bytes(b"# orphan\n")

    assert _export(target) == 0

    assert not stale_version.exists(), "manifest 未登记的历史版本必须删除"
    assert not orphan.exists(), "manifest 未登记的条目必须删除"
    assert not (target / TEXTS / "scenario.retired.instruction").exists(), "空目录应被清理"
    assert not (target / "README.md").exists(), "目标目录根部多余文件必须删除"
    assert (target / TEXTS / "knowledge.system").is_dir(), "仍在 manifest 里的目录不得删除"


def test_export_is_idempotent(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    target = tmp_path / "promptpack"

    assert _export(target) == 0
    first = {name: (target / name).read_bytes() for name in sorted(_relative_files(target))}
    capsys.readouterr()

    assert _export(target) == 0
    second = {name: (target / name).read_bytes() for name in sorted(_relative_files(target))}

    assert first == second
    assert "删除 0 个多余文件" in capsys.readouterr().out


def test_export_check_passes_on_fresh_export(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    target = tmp_path / "promptpack"
    assert _export(target) == 0
    capsys.readouterr()

    assert _export(target, "--check") == 0

    captured = capsys.readouterr()
    assert "java promptpack OK" in captured.out
    assert captured.err == ""


def test_export_check_reports_missing_file(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    target = tmp_path / "promptpack"
    assert _export(target) == 0
    capsys.readouterr()
    (target / TEXTS / "knowledge.user" / "2026-11-11.1.md").unlink()

    assert _export(target, "--check") == 1

    message = capsys.readouterr().err
    assert "缺少文件：texts/knowledge.user/2026-11-11.1.md" in message
    assert "sha256" in message
    assert "运行： uv run ai-prompts export-java" in message


def test_export_check_reports_extra_file(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    target = tmp_path / "promptpack"
    assert _export(target) == 0
    capsys.readouterr()
    stray = target / TEXTS / "knowledge.system" / "2026-11-11.1.md"
    stray.write_bytes(b"stale body\n")

    assert _export(target, "--check") == 1

    message = capsys.readouterr().err
    assert "多余文件：texts/knowledge.system/2026-11-11.1.md" in message


def test_export_check_reports_modified_bytes_without_leaking_body(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    target = tmp_path / "promptpack"
    assert _export(target) == 0
    capsys.readouterr()
    template = target / TEXTS / "scenario.weekly_report.instruction" / "2026-11-11.1.md"
    template.write_bytes(template.read_bytes().replace(b"\n", b"\n\n", 1))

    assert _export(target, "--check") == 1

    captured = capsys.readouterr()
    assert "内容不一致：texts/scenario.weekly_report.instruction/2026-11-11.1.md" in captured.err
    # 只露 sha256 前 12 位；既不打印正文，也不打印完整 64 位指纹。
    body = (PROMPTS_DIR / "scenario.weekly_report.instruction" / "2026-11-11.1.md").read_text(
        encoding="utf-8"
    )
    first_line = body.splitlines()[0]
    assert first_line not in captured.err
    assert first_line not in captured.out
    assert not any(len(token) == 64 for token in captured.err.replace("/", " ").split())


def test_export_check_reports_manifest_difference(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    target = tmp_path / "promptpack"
    assert _export(target) == 0
    capsys.readouterr()
    (target / MANIFEST_NAME).write_bytes(b"{}\n")

    assert _export(target, "--check") == 1

    assert "内容不一致：manifest.json" in capsys.readouterr().err


def test_export_refuses_when_prompt_pack_itself_drifted(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    pack = tmp_path / "prompts"
    shutil.copytree(PROMPTS_DIR, pack)
    template = pack / "knowledge.user" / "2026-11-11.1.md"
    template.write_bytes(template.read_bytes() + b"\n")
    target = tmp_path / "promptpack"

    result = build.main(
        ["export-java", "--check", "--prompts-dir", str(pack), "--target-dir", str(target)]
    )

    assert result == 1
    assert "PromptPack 自身不一致" in capsys.readouterr().err
    assert not target.exists(), "PromptPack 自身不一致时不得写入目标目录"


def test_export_reports_unresolvable_repo_root(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setattr(build, "find_repo_root", lambda: None)

    assert build.main(["export-java", "--check"]) == 1

    assert "无法定位仓库根" in capsys.readouterr().err


def test_check_flag_is_rejected_outside_export_java() -> None:
    with pytest.raises(SystemExit) as excinfo:
        build.main(["check", "--check"])

    assert excinfo.value.code == 2


def test_target_dir_is_rejected_outside_export_java(tmp_path: Path) -> None:
    with pytest.raises(SystemExit) as excinfo:
        build.main(["write", "--target-dir", str(tmp_path)])

    assert excinfo.value.code == 2


def test_default_target_dir_points_into_the_java_backend() -> None:
    target = build.default_java_promptpack_dir()

    assert build.find_repo_root() == PROJECT_ROOT.parent
    assert build.JAVA_PROMPTPACK_RELATIVE.as_posix() == (
        "ai-collab-backend/src/main/resources/promptpack"
    )
    assert target == PROJECT_ROOT.parent / build.JAVA_PROMPTPACK_RELATIVE
    assert target.is_dir(), "仓库内必须存在 Java fallback 包目录"
