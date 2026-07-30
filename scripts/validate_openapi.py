from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml


HTTP_ANNOTATIONS = {
    "GetMapping": "get",
    "PostMapping": "post",
    "PutMapping": "put",
    "PatchMapping": "patch",
    "DeleteMapping": "delete",
}


def mapping_path(annotation_arguments: str | None) -> str:
    if not annotation_arguments:
        return ""
    match = re.search(r'(?:"|value\s*=\s*")([^"]*)"', annotation_arguments)
    return match.group(1) if match else ""


def normalize_path(path: str) -> str:
    normalized = re.sub(r"/+", "/", path)
    if normalized.startswith("/api/v1"):
        normalized = normalized[len("/api/v1") :]
    return normalized or "/"


def controller_operations(source_root: Path) -> set[tuple[str, str]]:
    operations: set[tuple[str, str]] = set()
    for controller in source_root.rglob("*Controller.java"):
        content = controller.read_text(encoding="utf-8")
        class_match = re.search(
            r'@RequestMapping\s*\(\s*"([^"]*)"\s*\)[\s\S]*?public\s+class\s+\w+',
            content,
        )
        base_path = class_match.group(1) if class_match else ""
        for annotation, method in HTTP_ANNOTATIONS.items():
            pattern = rf"@{annotation}(?:\s*\(([^)]*)\))?"
            for match in re.finditer(pattern, content):
                path = normalize_path(base_path + mapping_path(match.group(1)))
                operations.add((path, method))
    return operations


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    openapi_path = (root / sys.argv[1]).resolve() if len(sys.argv) > 1 else root / "docs/api/openapi.yaml"
    if not openapi_path.is_file():
        print(f"OpenAPI 文件不存在: {openapi_path}", file=sys.stderr)
        return 1

    document = yaml.safe_load(openapi_path.read_text(encoding="utf-8"))
    for field in ("openapi", "info", "paths", "components"):
        if field not in document:
            print(f"OpenAPI 缺少必要字段: {field}", file=sys.stderr)
            return 1

    documented = {
        (path, method)
        for path, item in document["paths"].items()
        for method in HTTP_ANNOTATIONS.values()
        if method in item
    }
    implemented = controller_operations(
        root / "ai-collab-backend/src/main/java"
    )
    missing = sorted(implemented - documented)
    if missing:
        print("OpenAPI 缺少后端已实现操作:", file=sys.stderr)
        for path, method in missing:
            print(f"  {method.upper()} {path}", file=sys.stderr)
        return 1

    print(
        f"OpenAPI 契约校验通过: {len(documented)} 个文档操作，"
        f"{len(implemented)} 个后端操作。"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
