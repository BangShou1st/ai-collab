#!/usr/bin/env python3
"""Run the W4 v1 decide set with the Magpie/Marvis provider.

This is a provider revalidation, not a new prompt round.  The script:

1. logs in with the staging admin credentials;
2. snapshots providers and AGENT purpose;
3. creates, tests, and publishes a temporary OPENAI_COMPATIBLE Magpie provider;
4. temporarily assigns AGENT to that provider;
5. runs the existing ``run_decide_live.py`` harness against the v1 set;
6. restores AGENT to the Gemini provider and deletes the temporary provider.

The gateway key is read only from a 0600 file and is never printed, passed on a
command line, or written to evidence.  ``--dry-run`` and ``--check-local`` do
not contact staging.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Final

DEFAULT_APP_BASE_URL: Final[str] = "http://127.0.0.1:8080"
DEFAULT_ORIGIN: Final[str] = "http://127.0.0.1:8080"
DEFAULT_GATEWAY_URL: Final[str] = "https://3425-agqyc3kteqhqgazwefpmhwulu.instabox.tech"
DEFAULT_MODEL: Final[str] = "marvis/deepseek-v4-1-flash-261017"
DEFAULT_PROVIDER_NAME: Final[str] = "Magpie-Marvis-DeepSeek-V4.1-Flash-W4R"
DEFAULT_PROVIDER_TYPE: Final[str] = "OPENAI_COMPATIBLE"
DEFAULT_API_PATH: Final[str] = "/v1/chat/completions"
DEFAULT_GEMINI_PROVIDER_ID: Final[str] = "0f58581a-dbba-4e26-bafa-2e137f650563"
DEFAULT_REPO: Final[str] = "/data/home/ai-collab"
DEFAULT_OUT: Final[str] = "/data/home/w4-magpie-eval"
DEFAULT_ADMIN_ENV: Final[str] = "/data/.secrets/admin.env"
DEFAULT_KEY_FILE: Final[str] = "/data/home/magpie-gw.key"
DEFAULT_WORKER_ENV: Final[str] = "/data/.secrets/worker.env"
DEFAULT_WORKER_IMAGE: Final[str] = "ai-collab-ai-worker:dev"
DEFAULT_HARNESS: Final[str] = (
    "/data/home/ai-collab/docs/acceptance-evidence/2026-11-12/"
    "w4-decide-final/raw/harness/run_decide_live.py"
)
DEFAULT_DATA_SET: Final[str] = (
    "/data/home/ai-collab/ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v1.jsonl"
)
DEFAULT_PROMPT: Final[str] = (
    "/data/home/ai-collab/ai-collab-ai-worker/prompts/decide.system/2026-11-12.3.md"
)
DEFAULT_V1_SHA256: Final[str] = "1cd743bab6e48fb46f99776fcad558af62bcbfbe0a44cdb25be157c233694a58"


class HarnessError(RuntimeError):
    """A safe, non-secret-bearing failure message."""


def _load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export ") :].lstrip()
        key, separator, value = line.partition("=")
        if not separator:
            continue
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def _require_secure_file(path: Path, label: str) -> None:
    if not path.is_file():
        raise HarnessError(f"{label} 不存在：{path}")
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode & 0o077:
        raise HarnessError(f"{label} 权限必须为 0600，实际为 {mode:04o}")
    if path.stat().st_size == 0:
        raise HarnessError(f"{label} 为空：{path}")


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def _sanitize(value: Any) -> Any:
    """Remove credential-shaped fields from API evidence."""

    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for key, item in value.items():
            lowered = str(key).lower()
            if any(
                marker in lowered
                for marker in (
                    "apikey",
                    "api_key",
                    "password",
                    "authorization",
                    "access_token",
                    "refresh_token",
                    "encrypted",
                    "secret",
                )
            ):
                result[str(key)] = "<redacted>"
            else:
                result[str(key)] = _sanitize(item)
        return result
    if isinstance(value, list):
        return [_sanitize(item) for item in value]
    return value


def _request_json(
    method: str,
    url: str,
    *,
    token: str | None = None,
    payload: dict[str, Any] | None = None,
    origin: str = DEFAULT_ORIGIN,
    timeout: float = 120.0,
) -> dict[str, Any]:
    headers = {"Accept": "application/json", "Origin": origin}
    data: bytes | None = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        raw_body = exc.read().decode("utf-8", errors="replace")
        try:
            detail = json.dumps(_sanitize(json.loads(raw_body)), ensure_ascii=False)
        except json.JSONDecodeError:
            detail = "<non-json response>"
        raise HarnessError(f"{method} {url} HTTP {exc.code}: {detail[:800]}") from exc
    except urllib.error.URLError as exc:
        raise HarnessError(f"{method} {url} 网络失败：{exc.reason}") from exc
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError as exc:
        raise HarnessError(f"{method} {url} 返回非 JSON") from exc
    if not isinstance(parsed, dict):
        raise HarnessError(f"{method} {url} 返回不是 JSON object")
    return parsed


def _api(
    app_base_url: str,
    path: str,
    token: str,
    method: str,
    payload: dict[str, Any] | None = None,
    *,
    timeout: float = 120.0,
) -> dict[str, Any]:
    return _request_json(
        method,
        f"{app_base_url.rstrip('/')}{path}",
        token=token,
        payload=payload,
        timeout=timeout,
    )


def _success(response: dict[str, Any], label: str) -> Any:
    if response.get("code") != "SUCCESS":
        raise HarnessError(
            f"{label} 未成功：{json.dumps(_sanitize(response), ensure_ascii=False)[:800]}"
        )
    return response.get("data")


def _admin_login(app_base_url: str, admin_env: Path) -> tuple[str, dict[str, Any]]:
    env = _load_env(admin_env)
    username = env.get("ADMIN_USERNAME", "")
    password = env.get("ADMIN_PASSWORD", "")
    if not username or not password:
        raise HarnessError(f"{admin_env} 缺少 ADMIN_USERNAME/ADMIN_PASSWORD")
    response = _request_json(
        "POST",
        f"{app_base_url.rstrip('/')}/api/v1/auth/login",
        payload={"username": username, "password": password},
        timeout=30.0,
    )
    data = _success(response, "admin login")
    if not isinstance(data, dict) or not isinstance(data.get("accessToken"), str):
        raise HarnessError("admin login 响应缺少 accessToken")
    summary = {
        "code": response.get("code"),
        "user": _sanitize(data.get("user")),
        "expiresInSeconds": data.get("expiresInSeconds"),
    }
    return str(data["accessToken"]), summary


def _provider_rows(app_base_url: str, token: str) -> list[dict[str, Any]]:
    data = _success(_api(app_base_url, "/api/v1/admin/ai/providers", token, "GET"), "providers")
    return [item for item in data if isinstance(item, dict)] if isinstance(data, list) else []


def _purposes(app_base_url: str, token: str) -> dict[str, Any]:
    data = _success(
        _api(app_base_url, "/api/v1/user/ai-providers/purposes", token, "GET"),
        "purposes",
    )
    return data if isinstance(data, dict) else {}


def _provider_summary(provider: dict[str, Any]) -> dict[str, Any]:
    keys = (
        "id",
        "name",
        "providerType",
        "baseUrl",
        "apiPath",
        "modelName",
        "enabled",
        "temperature",
        "maxOutputTokens",
        "capabilities",
        "hasApiKey",
        "isShared",
        "sharedStatus",
        "capabilityConfigVersion",
    )
    return {key: provider[key] for key in keys if key in provider}


def _check_gateway_models(gateway_url: str, key_file: Path) -> None:
    _require_secure_file(key_file, "Magpie gateway key")
    key = key_file.read_text(encoding="utf-8").strip()
    if not key:
        raise HarnessError("Magpie gateway key 为空")
    request = urllib.request.Request(
        f"{gateway_url.rstrip('/')}/v1/models",
        headers={"Authorization": f"Bearer {key}", "Accept": "application/json"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=30.0) as response:
            if response.status != 200:
                raise HarnessError(f"Magpie /v1/models HTTP {response.status}")
    except urllib.error.HTTPError as exc:
        raise HarnessError(f"Magpie /v1/models HTTP {exc.code}") from exc
    except urllib.error.URLError as exc:
        raise HarnessError(f"Magpie /v1/models 网络失败：{exc.reason}") from exc


def _create_provider(
    app_base_url: str,
    token: str,
    *,
    name: str,
    gateway_url: str,
    model: str,
    key_file: Path,
) -> dict[str, Any]:
    key = key_file.read_text(encoding="utf-8").strip()
    if not key:
        raise HarnessError("Magpie gateway key 为空")
    payload = {
        "name": name,
        "providerType": DEFAULT_PROVIDER_TYPE,
        "baseUrl": gateway_url.rstrip("/"),
        "apiPath": DEFAULT_API_PATH,
        "apiKey": key,
        "modelName": model,
        "enabled": True,
        "temperature": 0,
        "maxOutputTokens": 8192,
        "capabilities": ["CHAT", "STREAMING"],
    }
    response = _api(app_base_url, "/api/v1/admin/ai/providers", token, "POST", payload)
    data = _success(response, "create Magpie provider")
    if not isinstance(data, dict) or not isinstance(data.get("id"), str):
        raise HarnessError("create Magpie provider 响应缺少 id")
    return data


def _test_provider(
    app_base_url: str,
    token: str,
    provider_id: str,
) -> dict[str, Any]:
    response = _api(
        app_base_url,
        f"/api/v1/admin/ai/providers/{provider_id}/test",
        token,
        "POST",
        timeout=180.0,
    )
    data = _success(response, "test Magpie provider")
    if not isinstance(data, dict):
        raise HarnessError("test Magpie provider 响应不是 object")
    if not str(data.get("content", "")).strip():
        raise HarnessError("test Magpie provider 返回空 content")
    return data


def _publish_provider(
    app_base_url: str,
    token: str,
    provider_id: str,
) -> dict[str, Any]:
    response = _api(
        app_base_url,
        f"/api/v1/admin/ai/providers/{provider_id}/publish",
        token,
        "POST",
    )
    data = _success(response, "publish Magpie provider")
    if not isinstance(data, dict):
        raise HarnessError("publish Magpie provider 响应不是 object")
    if data.get("isShared") is not True and data.get("sharedStatus") != "PUBLISHED":
        raise HarnessError("publish Magpie provider 未返回 PUBLISHED")
    return data


def _assign_purpose(
    app_base_url: str,
    token: str,
    provider_id: str,
) -> dict[str, Any]:
    response = _api(
        app_base_url,
        "/api/v1/user/ai-providers/purposes/AGENT",
        token,
        "PUT",
        {"providerId": provider_id},
    )
    _success(response, "assign AGENT purpose")
    return _purposes(app_base_url, token)


def _restore_purpose(
    app_base_url: str,
    token: str,
    original_provider_id: str,
) -> dict[str, Any]:
    response = _api(
        app_base_url,
        "/api/v1/user/ai-providers/purposes/AGENT",
        token,
        "PUT",
        {"providerId": original_provider_id},
    )
    _success(response, "restore AGENT purpose")
    return _purposes(app_base_url, token)


def _delete_provider(app_base_url: str, token: str, provider_id: str) -> None:
    response = _api(
        app_base_url,
        f"/api/v1/admin/ai/providers/{provider_id}",
        token,
        "DELETE",
    )
    _success(response, "delete Magpie provider")


def _run_live(
    *,
    repo: Path,
    out_dir: Path,
    gateway_url: str,
    model: str,
    provider_id: str,
    prompt_version: str,
    tag: str,
    worker_image: str,
    worker_env: Path,
) -> None:
    harness = (
        repo / "docs/acceptance-evidence/2026-11-12/w4-decide-final/raw/harness/run_decide_live.py"
    )
    data_set = repo / "ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v1.jsonl"
    if not harness.is_file() or not data_set.is_file():
        raise HarnessError("repo 内缺少 run_decide_live.py 或 v1 冻结集")
    out_dir.mkdir(parents=True, exist_ok=True)
    command = [
        "docker",
        "run",
        "--rm",
        "--network",
        "ai-collab-deploy_ai",
        "--user",
        "0",
        "-v",
        f"{repo}:/repo:ro",
        "-v",
        f"{out_dir}:/out",
        "--env-file",
        str(worker_env),
        "--entrypoint",
        "/app/.venv/bin/python",
        worker_image,
        "/repo/docs/acceptance-evidence/2026-11-12/w4-decide-final/raw/harness/run_decide_live.py",
        "--set",
        "/repo/ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v1.jsonl",
        "--out-dir",
        "/out",
        "--worker-url",
        "http://ai-worker-api:9100",
        "--interval-ms",
        "10000",
        "--evidence",
        "LIVE_VERIFIED (staging, real model, Magpie provider)",
        "--model",
        model,
        "--subject",
        "w4/decide-final @ 87450efe (Magpie provider revalidation)",
        "--tag",
        tag,
    ]
    result = subprocess.run(
        command,
        text=True,
        capture_output=True,
        timeout=1800,
        check=False,
    )
    (out_dir / "live-output.txt").write_text(
        (result.stdout or "") + (result.stderr or ""),
        encoding="utf-8",
    )
    if result.returncode != 0:
        raise HarnessError(f"live harness 退出码 {result.returncode}，见 live-output.txt")
    _write_json(
        out_dir / "provider-run-meta.json",
        {
            "provider": "Magpie / Marvis",
            "providerId": provider_id,
            "gatewayUrl": gateway_url,
            "apiPath": DEFAULT_API_PATH,
            "model": model,
            "promptVersion": prompt_version,
            "promptFile": str(repo / "ai-collab-ai-worker/prompts/decide.system/2026-11-12.3.md"),
            "promptSha256": hashlib.sha256(
                (repo / "ai-collab-ai-worker/prompts/decide.system/2026-11-12.3.md").read_bytes()
            ).hexdigest(),
            "v1CanonicalSha256": DEFAULT_V1_SHA256,
            "v1FileSha256": hashlib.sha256(
                (
                    repo / "ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v1.jsonl"
                ).read_bytes()
            ).hexdigest(),
            "tag": tag,
            "workerImage": worker_image,
            "range": "2026-11-12/w4-decide-magpie",
        },
    )


def _matrix_summary(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {"present": False, "path": str(path)}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {
        "present": True,
        "path": str(path),
        "sha256": data.get("sha256"),
        "aggregate": data.get("aggregate"),
    }


def _print_ready(args: argparse.Namespace) -> None:
    required = [
        ("repo", Path(args.repo)),
        ("outDir", Path(args.out_dir)),
        (
            "harness",
            Path(args.repo)
            / "docs/acceptance-evidence/2026-11-12/w4-decide-final/raw/harness/run_decide_live.py",
        ),
        (
            "v1Set",
            Path(args.repo)
            / "ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v1.jsonl",
        ),
        ("prompt", Path(args.repo) / "ai-collab-ai-worker/prompts/decide.system/2026-11-12.3.md"),
    ]
    missing = [name for name, path in required if not path.exists()]
    if missing:
        print("NOT_READY missing=" + ",".join(missing))
        raise SystemExit(2)
    print("READY")
    print(f"provider={args.provider_name}")
    print(f"model={args.model}")
    print(f"gateway_url={args.gateway_url}")
    print("prompt=2026-11-12.3")
    print("gate=agreement>=0.98,json>=0.995,invalidToolCalls=0")
    print("staging=NOT_ACQUIRED")


def _dry_run(args: argparse.Namespace) -> None:
    print("DRY_RUN")
    print(f"app_base_url={args.app_base_url}")
    print(f"gateway_url={args.gateway_url}")
    print(f"provider_name={args.provider_name}")
    print(f"model={args.model}")
    print(f"gemini_restore_id={args.gemini_provider_id}")
    print(f"repo={args.repo}")
    print(f"out_dir={args.out_dir}")
    print(
        "steps=login,snapshot,create,test,publish,assign_AGENT,run_v1_32,restore_AGENT,delete_provider"
    )
    print("secrets=read_from_0600_files_only")


def _run(args: argparse.Namespace) -> int:
    repo = Path(args.repo)
    out_dir = Path(args.out_dir)
    key_file = Path(args.key_file)
    stage = out_dir / "staging-state.json"
    state: dict[str, Any] = {}
    app_url = args.app_base_url.rstrip("/")
    token = ""
    provider_id: str | None = None
    original_agent: str | None = None
    cleanup_errors: list[str] = []
    try:
        _require_secure_file(Path(args.admin_env), "admin.env")
        _require_secure_file(key_file, "Magpie gateway key")
        _check_gateway_models(args.gateway_url, key_file)
        token, login_summary = _admin_login(app_url, Path(args.admin_env))
        _write_json(out_dir / "login.json", login_summary)

        providers_before = _provider_rows(app_url, token)
        purposes_before = _purposes(app_url, token)
        _write_json(out_dir / "providers-before.json", _sanitize(providers_before))
        _write_json(out_dir / "purposes-before.json", _sanitize(purposes_before))
        original_agent = str(purposes_before.get("AGENT", "") or "")
        if original_agent != args.gemini_provider_id:
            raise HarnessError(
                "AGENT purpose 不是预期 Gemini provider；拒绝改动，"
                f"before={original_agent or '<unset>'}"
            )
        existing = [p for p in providers_before if p.get("name") == args.provider_name]
        if existing:
            raise HarnessError(
                f"已存在同名 provider {args.provider_name}，拒绝覆盖；"
                "请先人工清理或换 --provider-name"
            )

        created = _create_provider(
            app_url,
            token,
            name=args.provider_name,
            gateway_url=args.gateway_url,
            model=args.model,
            key_file=key_file,
        )
        provider_id = str(created["id"])
        state = {
            "providerId": provider_id,
            "originalAgentProviderId": original_agent,
            "name": args.provider_name,
            "model": args.model,
            "gatewayUrl": args.gateway_url,
            "createdAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        _write_json(stage, state)
        _write_json(out_dir / "provider-create.json", _sanitize(_provider_summary(created)))

        tested = _test_provider(app_url, token, provider_id)
        _write_json(out_dir / "provider-test.json", _sanitize(tested))
        published = _publish_provider(app_url, token, provider_id)
        _write_json(out_dir / "provider-publish.json", _sanitize(_provider_summary(published)))

        purposes = _assign_purpose(app_url, token, provider_id)
        _write_json(out_dir / "purposes-after-assign.json", _sanitize(purposes))
        if purposes.get("AGENT") != provider_id:
            raise HarnessError("AGENT purpose 未指向临时 Magpie provider")

        _run_live(
            repo=repo,
            out_dir=out_dir,
            gateway_url=args.gateway_url,
            model=args.model,
            provider_id=provider_id,
            prompt_version="2026-11-12.3",
            tag=args.tag,
            worker_image=args.worker_image,
            worker_env=Path(args.worker_env),
        )
        aggregate = out_dir / f"aggregate-decide-live-{args.tag}.json"
        _write_json(out_dir / "result-summary.json", _matrix_summary(aggregate))
        if not aggregate.is_file():
            raise HarnessError("live harness 成功退出但没有生成 aggregate")
    finally:
        if token and provider_id:
            try:
                restored = _restore_purpose(app_url, token, args.gemini_provider_id)
                _write_json(out_dir / "purposes-after-restore.json", _sanitize(restored))
                _write_json(
                    out_dir / "providers-after-restore.json",
                    _sanitize(_provider_rows(app_url, token)),
                )
                if restored.get("AGENT") != args.gemini_provider_id:
                    raise HarnessError("AGENT purpose 恢复后未指向 Gemini 3.5")
            except Exception as exc:
                print(f"CLEANUP_RESTORE_FAILED: {exc}", file=sys.stderr)
                cleanup_errors.append(f"restore: {exc}")
            try:
                _delete_provider(app_url, token, provider_id)
                rows = _provider_rows(app_url, token)
                _write_json(
                    out_dir / "providers-after-cleanup.json",
                    _sanitize(rows),
                )
                if any(row.get("id") == provider_id for row in rows):
                    raise HarnessError("临时 Magpie provider 仍存在")
            except Exception as exc:
                print(f"CLEANUP_DELETE_FAILED provider={provider_id}: {exc}", file=sys.stderr)
                cleanup_errors.append(f"delete: {exc}")
    if cleanup_errors:
        raise HarnessError("清理失败：" + "; ".join(cleanup_errors))
    return 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-base-url", default=DEFAULT_APP_BASE_URL)
    parser.add_argument("--gateway-url", default=DEFAULT_GATEWAY_URL)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--provider-name", default=DEFAULT_PROVIDER_NAME)
    parser.add_argument("--gemini-provider-id", default=DEFAULT_GEMINI_PROVIDER_ID)
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--out-dir", default=DEFAULT_OUT)
    parser.add_argument("--admin-env", default=DEFAULT_ADMIN_ENV)
    parser.add_argument("--key-file", default=DEFAULT_KEY_FILE)
    parser.add_argument("--worker-env", default=DEFAULT_WORKER_ENV)
    parser.add_argument("--worker-image", default=DEFAULT_WORKER_IMAGE)
    parser.add_argument("--tag", default="v1-magpie")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--check-local", action="store_true")
    parser.add_argument("--run", action="store_true")
    args = parser.parse_args()
    if sum((args.dry_run, args.check_local, args.run)) > 1:
        parser.error("--dry-run / --check-local / --run 只能选一个")
    return args


def main() -> int:
    args = _parser()
    if args.dry_run:
        _dry_run(args)
        return 0
    if args.check_local:
        _print_ready(args)
        return 0
    if not args.run:
        print("NOTHING_TO_DO; use --check-local, --dry-run, or --run", file=sys.stderr)
        return 2
    try:
        return _run(args)
    except Exception as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
