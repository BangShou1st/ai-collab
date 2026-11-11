"""``ai-prompts`` CLI：按 ``prompts/<key>/<version>.md`` 生成并校验 ``manifest.json``。

设计 §7.2：``prompts/manifest.json`` 是 manifest 的唯一来源，**不做在线编辑**；因此 manifest
只能由本 CLI 从模板文件重建，禁止手写 ``sha256``/``chars``/``variables`` 数字。

```bash
uv run ai-prompts check              # 默认命令：逐字段比对，返回 0/1
uv run ai-prompts write              # 按模板重建 manifest.json
uv run ai-prompts check --prompts-dir /app/prompts
```
"""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Sequence
from pathlib import Path
from typing import Final

from pydantic import ValidationError

from ai_collab_worker.contract.prompts import (
    PromptPackEntry,
    PromptPackManifest,
    extract_variables,
    prompt_chars,
    prompt_sha256,
    prompt_text_from_bytes,
)

#: 子命令：``check`` 只比对，``write`` 重建 manifest。
COMMANDS: Final[tuple[str, ...]] = ("check", "write")

MANIFEST_FILENAME: Final[str] = "manifest.json"

_TEMPLATE_GLOB: Final[str] = "*/*.md"


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


def _parse_args(argv: Sequence[str] | None) -> tuple[str, Path]:
    parser = argparse.ArgumentParser(
        prog="ai-prompts",
        description="按 prompts/<key>/<version>.md 生成/校验 prompts/manifest.json",
    )
    parser.add_argument(
        "command",
        nargs="?",
        choices=COMMANDS,
        default="check",
        help="check=逐字段比对（默认）；write=按模板重建 manifest.json",
    )
    parser.add_argument(
        "--prompts-dir",
        dest="prompts_dir",
        type=Path,
        default=default_prompts_dir(),
        metavar="PATH",
        help="PromptPack 目录（默认项目根 prompts/）",
    )
    namespace = parser.parse_args(list(argv) if argv is not None else None)
    return str(namespace.command), Path(namespace.prompts_dir)


def main(argv: Sequence[str] | None = None) -> int:
    """CLI 入口：返回 0（一致/写入成功）或 1（不一致/资产不合规）。"""

    command, prompts_dir = _parse_args(argv)
    try:
        manifest = build_manifest(prompts_dir)
    except PromptBuildError as exc:
        print(f"ai-prompts: {exc}", file=sys.stderr)
        return 1

    if command == "write":
        path = write_manifest(prompts_dir, manifest)
        print(f"written: {path}（{len(manifest.prompts)} 条）")
        return 0

    problems = check_manifest(prompts_dir, manifest)
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        print("manifest 与模板不一致；确认变更后运行： uv run ai-prompts write", file=sys.stderr)
        return 1
    print(f"prompts OK（{len(manifest.prompts)} 条）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
