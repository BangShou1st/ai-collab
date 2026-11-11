"""Java 只读 fallback 包与 PromptPack 的逐字节同步（跨语言漂移守护）。

``prompt.registry.enabled=false``（默认）时 Java 从 classpath 的
``ai-collab-backend/src/main/resources/promptpack/`` 读 Prompt，那份包**由本仓库的
``ai-prompts export-java`` 生成**。因此它与 ``ai-collab-ai-worker/prompts/`` 必须逐字节相同：
- ``promptpack/manifest.json`` == ``prompts/manifest.json``（字节相同，不重新序列化）；
- ``promptpack/texts/<key>/<version>.md`` == ``prompts/<key>/<version>.md``（字节相同）；
- 目标目录不得有 manifest 里不存在的多余文件。

后端目录不存在时**直接失败而不是 skip**：跳过会让守护在单检出/CI 分片里静默失效，
而 Java 侧会安静地回退到一份过期正文。
"""

from __future__ import annotations

from pathlib import Path

import pytest

from ai_collab_worker.prompts import build

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
MANIFEST_PATH = PROMPTS_DIR / "manifest.json"

_TEMPLATE_GLOB = "*/*.md"


def _java_promptpack_dir() -> Path:
    """Java fallback 包目录；仓库根定位失败时直接失败（不 skip）。"""

    try:
        target = build.default_java_promptpack_dir()
    except build.PromptBuildError as exc:
        pytest.fail(f"必须与 Java 侧同一仓库检出：{exc}")
    if not target.is_dir():
        pytest.fail(f"必须与 Java 侧同一仓库检出：缺少 Java fallback 包目录 {target}")
    return target


def _expected_plan() -> dict[Path, bytes]:
    return build.export_plan(PROMPTS_DIR)


def _relative_files(root: Path) -> set[Path]:
    return {path.relative_to(root) for path in root.rglob("*") if path.is_file()}


def test_java_promptpack_lives_in_the_same_checkout() -> None:
    target = _java_promptpack_dir()

    assert target == PROJECT_ROOT.parent / build.JAVA_PROMPTPACK_RELATIVE
    assert build.find_repo_root() == PROJECT_ROOT.parent


def test_java_manifest_is_byte_identical() -> None:
    target = _java_promptpack_dir()

    assert MANIFEST_PATH.is_file(), f"缺少 Python 侧 manifest：{MANIFEST_PATH}"
    assert (target / build.MANIFEST_FILENAME).read_bytes() == MANIFEST_PATH.read_bytes(), (
        "Java fallback 的 manifest.json 与 prompts/manifest.json 字节不同；"
        "运行 uv run ai-prompts export-java 重新导出"
    )


def test_java_texts_are_byte_identical() -> None:
    target = _java_promptpack_dir()

    templates = sorted(PROMPTS_DIR.glob(_TEMPLATE_GLOB))
    assert templates, "prompts/ 下没有任何模板"
    for template in templates:
        relative = Path(build.JAVA_TEXTS_DIRNAME) / template.parent.name / template.name
        path = target / relative
        assert path.is_file(), f"Java fallback 缺少文件：{relative.as_posix()}"
        assert path.read_bytes() == template.read_bytes(), (
            f"Java fallback 与 PromptPack 字节不同：{relative.as_posix()}；"
            "运行 uv run ai-prompts export-java 重新导出"
        )


def test_java_promptpack_has_no_extra_files() -> None:
    target = _java_promptpack_dir()

    expected = set(_expected_plan())
    on_disk = _relative_files(target)
    extra = sorted(path.as_posix() for path in on_disk - expected)
    missing = sorted(path.as_posix() for path in expected - on_disk)

    assert extra == [], f"Java fallback 存在多余文件（manifest 未登记）：{extra}"
    assert missing == [], f"Java fallback 缺少文件：{missing}"


def test_ai_prompts_export_java_check_exits_zero(capsys: pytest.CaptureFixture[str]) -> None:
    """CLI 的逐字节比对必须通过（与上面的断言同源，但走真实命令行入口）。"""

    assert build.main(["export-java", "--check"]) == 0
    captured = capsys.readouterr()
    assert "java promptpack OK" in captured.out
    assert captured.err == ""
