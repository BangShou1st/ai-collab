"""``ai-prompts`` CLI：按 ``prompts/<key>/<version>.md`` 生成并校验 ``manifest.json``。

设计 §7.2：``prompts/manifest.json`` 是 manifest 的唯一来源，**不做在线编辑**；因此 manifest
只能由本 CLI 从模板文件重建，禁止手写 ``sha256``/``chars``/``variables`` 数字。

```bash
uv run ai-prompts check              # 默认命令：逐字段比对，返回 0/1
uv run ai-prompts write              # 按模板重建 manifest.json
uv run ai-prompts check --prompts-dir /app/prompts
uv run ai-prompts export-java        # 生成/覆盖 Java 侧 fallback 包（逐字节复制）
uv run ai-prompts export-java --check  # 逐字节比对 Java fallback 包，不一致返回 1
```

Java fallback 包（冻结文档 §5）是 ``prompt.registry.enabled=false`` 时的只读回退来源，
**只能由本 CLI 生成，禁止手改**：``promptpack/manifest.json`` 与 ``prompts/manifest.json``
字节相同，``promptpack/texts/<key>/<version>.md`` 与 ``prompts/<key>/<version>.md`` 字节相同。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Final, NamedTuple

from pydantic import ValidationError

from ai_collab_worker.contract.prompts import (
    PromptPackEntry,
    PromptPackManifest,
    extract_variables,
    prompt_chars,
    prompt_sha256,
    prompt_text_from_bytes,
)

#: 子命令：``check`` 只比对，``write`` 重建 manifest，``export-java`` 生成 Java fallback 包。
COMMANDS: Final[tuple[str, ...]] = ("check", "write", "export-java")

MANIFEST_FILENAME: Final[str] = "manifest.json"

_TEMPLATE_GLOB: Final[str] = "*/*.md"

#: Java 侧只读 fallback 包的目录名（与 ``ai-collab-ai-worker`` 平级的子项目内）。
_BACKEND_DIRNAME: Final[str] = "ai-collab-backend"
_WORKER_DIRNAME: Final[str] = "ai-collab-ai-worker"

#: fallback 包内模板子目录名；布局已与 Java 侧冻结，不得改名。
JAVA_TEXTS_DIRNAME: Final[str] = "texts"

#: fallback 包相对仓库根的路径。
JAVA_PROMPTPACK_RELATIVE: Final[Path] = (
    Path(_BACKEND_DIRNAME) / "src" / "main" / "resources" / "promptpack"
)

#: 差异输出里 sha256 的截断长度（只露指纹，不露正文）。
_SHORT_SHA256_LENGTH: Final[int] = 12


class PromptBuildError(Exception):
    """构建/校验失败：CLI 打印原因并返回 1，不向调用方抛栈。"""


def default_prompts_dir() -> Path:
    """默认 PromptPack 目录：项目根下的 ``prompts/``。

    以 ``pyproject.toml`` 与 ``src/ai_collab_worker`` 双重锚定向上查找，保证在任意工作目录
    下运行 CLI 都指向同一份资产；容器里应显式传 ``--prompts-dir /app/prompts``。
    """

    anchor = Path(__file__).resolve().parent
    for candidate in (anchor, *anchor.parents):
        if (candidate / "pyproject.toml").is_file() and (
            candidate / "src" / "ai_collab_worker"
        ).is_dir():
            return candidate / "prompts"
    return Path.cwd() / "prompts"


def find_repo_root() -> Path | None:
    """向上查找同时含 ``ai-collab-ai-worker/`` 与 ``ai-collab-backend/`` 的仓库根。

    与 :func:`default_prompts_dir` 同一套锚定方式（从本文件所在目录逐级向上），
    但要求两个子项目并存——单检出 ``ai-collab-ai-worker`` 时返回 ``None``。
    """

    anchor = Path(__file__).resolve().parent
    for candidate in (anchor, *anchor.parents):
        if (candidate / _WORKER_DIRNAME).is_dir() and (candidate / _BACKEND_DIRNAME).is_dir():
            return candidate
    return None


def default_java_promptpack_dir() -> Path:
    """默认 Java fallback 包目录：``<repo-root>/ai-collab-backend/.../promptpack``。

    找不到仓库根时抛 :class:`PromptBuildError`（而不是回退到当前工作目录），
    避免在错误的磁盘位置生成/覆盖回退包。
    """

    repo_root = find_repo_root()
    if repo_root is None:
        msg = (
            "无法定位仓库根：需同时存在 "
            f"{_WORKER_DIRNAME}/ 与 {_BACKEND_DIRNAME}/；请显式传 --target-dir"
        )
        raise PromptBuildError(msg)
    return repo_root / JAVA_PROMPTPACK_RELATIVE


def _describe_validation_error(exc: ValidationError) -> str:
    first = exc.errors()[0]
    location = ".".join(str(part) for part in first.get("loc", ()))
    message = str(first.get("msg", ""))
    return f"{location}: {message}" if location else message


def _entry_from_template(key: str, version: str, path: Path) -> PromptPackEntry:
    try:
        text = prompt_text_from_bytes(path.read_bytes())
    except OSError as exc:
        raise PromptBuildError(f"读取模板失败：{path}（{type(exc).__name__}）") from exc
    except ValueError as exc:
        raise PromptBuildError(f"模板编码不合规：{path}（{exc}）") from exc
    try:
        return PromptPackEntry(
            key=key,
            version=version,
            sha256=prompt_sha256(text),
            chars=prompt_chars(text),
            variables=tuple(extract_variables(text)),
        )
    except ValidationError as exc:
        detail = _describe_validation_error(exc)
        raise PromptBuildError(f"模板文件名或内容不符合契约：{path}（{detail}）") from exc


def build_manifest(prompts_dir: Path) -> PromptPackManifest:
    """扫描 ``prompts/*/*.md`` 生成 manifest（按 ``(key, version)`` 升序）。

    ``manifest.json`` 位于目录根部，不匹配 ``*/*.md``，因此不会被当成模板。
    """

    if not prompts_dir.is_dir():
        raise PromptBuildError(f"PromptPack 目录不存在：{prompts_dir}")

    entries = [
        _entry_from_template(path.parent.name, path.stem, path)
        for path in sorted(prompts_dir.glob(_TEMPLATE_GLOB))
        if path.name != MANIFEST_FILENAME
    ]
    entries.sort(key=lambda entry: (entry.key, entry.version))
    try:
        return PromptPackManifest(prompts=tuple(entries))
    except ValidationError as exc:
        detail = _describe_validation_error(exc)
        raise PromptBuildError(f"Prompt 资产不符合契约：{detail}") from exc


def render_manifest(manifest: PromptPackManifest) -> str:
    """渲染 ``manifest.json``：2 空格缩进、UTF-8、LF、末尾一个换行、中文不转义。"""

    payload = manifest.model_dump(by_alias=True, mode="json")
    return json.dumps(payload, ensure_ascii=False, indent=2) + "\n"


def write_manifest(prompts_dir: Path, manifest: PromptPackManifest) -> Path:
    """把 ``manifest`` 写到 ``prompts_dir/manifest.json``，返回写入路径。"""

    prompts_dir.mkdir(parents=True, exist_ok=True)
    path = prompts_dir / MANIFEST_FILENAME
    path.write_text(render_manifest(manifest), encoding="utf-8", newline="\n")
    return path


def _load_written_manifest(prompts_dir: Path) -> tuple[PromptPackManifest | None, list[str]]:
    path = prompts_dir / MANIFEST_FILENAME
    if not path.is_file():
        return None, [f"缺少 {MANIFEST_FILENAME}（先运行 uv run ai-prompts write）"]
    try:
        payload = json.loads(path.read_bytes().decode("utf-8"))
        return PromptPackManifest.model_validate(payload), []
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        return None, [f"{MANIFEST_FILENAME} 不是合法 JSON（{type(exc).__name__}）"]
    except ValidationError as exc:
        detail = _describe_validation_error(exc)
        return None, [f"{MANIFEST_FILENAME} 不符合契约（{detail}）"]


def _diff_entry(
    reference: tuple[str, str], expected: PromptPackEntry, actual: PromptPackEntry
) -> list[str]:
    label = f"{reference[0]}@{reference[1]}"
    problems: list[str] = []
    if expected.sha256 != actual.sha256:
        problems.append(
            f"sha256 不一致：{label}（模板 {expected.sha256} / manifest {actual.sha256}）"
        )
    if expected.chars != actual.chars:
        problems.append(f"chars 不一致：{label}（模板 {expected.chars} / manifest {actual.chars}）")
    if expected.variables != actual.variables:
        problems.append(
            f"variables 不一致：{label}"
            f"（模板 {list(expected.variables)} / manifest {list(actual.variables)}）"
        )
    return problems


def check_manifest(prompts_dir: Path, expected: PromptPackManifest) -> list[str]:
    """逐字段比对磁盘 manifest 与模板；返回差异描述，空列表表示完全一致。"""

    actual, problems = _load_written_manifest(prompts_dir)
    if actual is None:
        return problems

    expected_entries = {(entry.key, entry.version): entry for entry in expected.prompts}
    actual_entries = {(entry.key, entry.version): entry for entry in actual.prompts}
    for reference in sorted(expected_entries):
        have = actual_entries.get(reference)
        if have is None:
            problems.append(f"缺少条目：{reference[0]}@{reference[1]}（模板存在，manifest 未登记）")
            continue
        problems.extend(_diff_entry(reference, expected_entries[reference], have))
    for reference in sorted(set(actual_entries) - set(expected_entries)):
        problems.append(f"多余条目：{reference[0]}@{reference[1]}（manifest 有，模板缺失）")
    return problems


# ---- Java fallback 包导出（冻结文档 §5） --------------------------------------


def _short_sha256(data: bytes) -> str:
    """sha256 前 12 位：差异信息里只露指纹，绝不输出 Prompt 正文。"""

    return hashlib.sha256(data).hexdigest()[:_SHORT_SHA256_LENGTH]


def _relative_files(root: Path) -> set[Path]:
    """``root`` 下所有普通文件的相对路径；目录不存在时返回空集合。"""

    if not root.is_dir():
        return set()
    return {path.relative_to(root) for path in root.rglob("*") if path.is_file()}


def export_plan(prompts_dir: Path) -> dict[Path, bytes]:
    """相对路径 -> 期望字节：``manifest.json`` 与 ``texts/<key>/<version>.md``。

    ``manifest.json`` 直接复制 ``prompts/manifest.json`` 的字节，**不重新序列化**；
    模板同理逐字节复制，保证 Java 侧读到的正文与 Python 侧完全一致。
    """

    manifest_bytes = (prompts_dir / MANIFEST_FILENAME).read_bytes()
    plan: dict[Path, bytes] = {Path(MANIFEST_FILENAME): manifest_bytes}
    for template in sorted(prompts_dir.glob(_TEMPLATE_GLOB)):
        if template.name == MANIFEST_FILENAME:
            continue
        relative = Path(JAVA_TEXTS_DIRNAME) / template.parent.name / template.name
        plan[relative] = template.read_bytes()
    return plan


def compare_java_promptpack(target_dir: Path, plan: Mapping[Path, bytes]) -> list[str]:
    """逐文件字节比对 ``target_dir`` 与期望产物；返回差异描述，空列表表示完全一致。"""

    present = _relative_files(target_dir)
    problems: list[str] = []
    for relative in sorted(plan):
        expected = plan[relative]
        if relative not in present:
            problems.append(
                f"缺少文件：{relative.as_posix()}（期望 sha256 {_short_sha256(expected)}）"
            )
            continue
        try:
            actual = (target_dir / relative).read_bytes()
        except OSError as exc:
            problems.append(f"无法读取：{relative.as_posix()}（{type(exc).__name__}）")
            continue
        if actual != expected:
            problems.append(
                f"内容不一致：{relative.as_posix()}"
                f"（期望 sha256 {_short_sha256(expected)} / 实际 {_short_sha256(actual)}）"
            )
    for relative in sorted(present - set(plan)):
        try:
            actual_bytes = (target_dir / relative).read_bytes()
        except OSError:
            problems.append(f"多余文件：{relative.as_posix()}")
            continue
        problems.append(f"多余文件：{relative.as_posix()}（sha256 {_short_sha256(actual_bytes)}）")
    return problems


def _prune_empty_dirs(root: Path) -> None:
    """删除 ``root`` 下的空目录（自最深一级向上），``root`` 本身保留。"""

    if not root.is_dir():
        return
    for path in sorted(root.rglob("*"), key=lambda item: len(item.parts), reverse=True):
        if path.is_dir() and not any(path.iterdir()):
            path.rmdir()


def write_java_promptpack(target_dir: Path, plan: Mapping[Path, bytes]) -> tuple[int, int]:
    """把 ``plan`` 写入 ``target_dir``，返回 ``(写入数, 删除的多余文件数)``。

    写入前先删除目标目录里不存在于计划中的文件（含历史版本与杂项），再按字节写入；
    计划内的路径全部落在 ``target_dir`` 之下，删除操作同样以 ``target_dir`` 为界。
    """

    removed = 0
    for relative in sorted(_relative_files(target_dir) - set(plan)):
        (target_dir / relative).unlink()
        removed += 1
    _prune_empty_dirs(target_dir)
    for relative in sorted(plan):
        path = target_dir / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(plan[relative])
    return len(plan), removed


class _Arguments(NamedTuple):
    """已解析的 CLI 参数（``export-java`` 的 ``--target-dir``/``--check`` 只对它生效）。"""

    command: str
    prompts_dir: Path
    target_dir: Path | None
    check: bool


def _parse_args(argv: Sequence[str] | None) -> _Arguments:
    parser = argparse.ArgumentParser(
        prog="ai-prompts",
        description=(
            "按 prompts/<key>/<version>.md 生成/校验 prompts/manifest.json，"
            "并生成 Java 侧只读 fallback 包"
        ),
    )
    parser.add_argument(
        "command",
        nargs="?",
        choices=COMMANDS,
        default="check",
        help=(
            "check=逐字段比对（默认）；write=按模板重建 manifest.json；"
            "export-java=生成 Java 侧只读 fallback 包"
        ),
    )
    parser.add_argument(
        "--prompts-dir",
        dest="prompts_dir",
        type=Path,
        default=default_prompts_dir(),
        metavar="PATH",
        help="PromptPack 目录（默认项目根 prompts/）",
    )
    parser.add_argument(
        "--target-dir",
        dest="target_dir",
        type=Path,
        default=None,
        metavar="PATH",
        help=(
            "export-java 的目标目录"
            "（默认 <repo-root>/ai-collab-backend/src/main/resources/promptpack）"
        ),
    )
    parser.add_argument(
        "--check",
        dest="check",
        action="store_true",
        help="export-java 专用：只逐字节比对，不写入；不一致返回 1",
    )
    namespace = parser.parse_args(list(argv) if argv is not None else None)
    command = str(namespace.command)
    if namespace.check and command != "export-java":
        parser.error("--check 只能与 export-java 一起使用")
    target_dir = namespace.target_dir
    if target_dir is not None and command != "export-java":
        parser.error("--target-dir 只能与 export-java 一起使用")
    return _Arguments(
        command=command,
        prompts_dir=Path(namespace.prompts_dir),
        target_dir=Path(target_dir) if target_dir is not None else None,
        check=bool(namespace.check),
    )


def _run_export_java(arguments: _Arguments, manifest: PromptPackManifest) -> int:
    """``export-java``：先校验 PromptPack 自身，再导出或比对 Java fallback 包。"""

    problems = check_manifest(arguments.prompts_dir, manifest)
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        print(
            "PromptPack 自身不一致，先修复 prompts/ 再导出；"
            "确认变更后运行： uv run ai-prompts write",
            file=sys.stderr,
        )
        return 1

    try:
        target_dir = arguments.target_dir or default_java_promptpack_dir()
        plan = export_plan(arguments.prompts_dir)
    except PromptBuildError as exc:
        print(f"ai-prompts: {exc}", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"ai-prompts: 读取 PromptPack 失败（{type(exc).__name__}）", file=sys.stderr)
        return 1

    if arguments.check:
        differences = compare_java_promptpack(target_dir, plan)
        if differences:
            for difference in differences:
                print(difference, file=sys.stderr)
            print(
                "Java fallback 包与 PromptPack 不一致；运行： uv run ai-prompts export-java",
                file=sys.stderr,
            )
            return 1
        print(f"java promptpack OK（{len(plan)} 个文件）")
        return 0

    try:
        written, removed = write_java_promptpack(target_dir, plan)
    except OSError as exc:
        print(f"ai-prompts: 写入 Java fallback 包失败（{type(exc).__name__}）", file=sys.stderr)
        return 1
    print(f"exported: {target_dir}（写入 {written} 个文件，删除 {removed} 个多余文件）")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    """CLI 入口：返回 0（一致/写入成功）或 1（不一致/资产不合规）。"""

    arguments = _parse_args(argv)
    try:
        manifest = build_manifest(arguments.prompts_dir)
    except PromptBuildError as exc:
        print(f"ai-prompts: {exc}", file=sys.stderr)
        return 1

    if arguments.command == "write":
        path = write_manifest(arguments.prompts_dir, manifest)
        print(f"written: {path}（{len(manifest.prompts)} 条）")
        return 0

    if arguments.command == "export-java":
        return _run_export_java(arguments, manifest)

    problems = check_manifest(arguments.prompts_dir, manifest)
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        print("manifest 与模板不一致；确认变更后运行： uv run ai-prompts write", file=sys.stderr)
        return 1
    print(f"prompts OK（{len(manifest.prompts)} 条）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
