#!/usr/bin/env python3
"""
生成或补齐 Staging 环境凭据文件（默认 /data/ai-collab-staging/config/.env，可用第一个参数
或 STAGING_ENV_PATH 覆盖，便于本地演练）。

幂等：文件已存在时只补齐缺失的键，已有值一律不覆盖；同时移除已下线的变量。
文件权限强制 0600。标准输出只打印变量名，绝不打印任何密码、密钥或私钥。

内部 AI 令牌（Ed25519）：
  INTERNAL_AI_ED25519_PRIVATE_KEY  app 签发私钥（PKCS#8 DER base64）
  INTERNAL_AI_ED25519_KID          当前签发 kid（默认 k1）
  AI_WORKER_ED25519_PUBLIC_KEYS    worker 验签公钥列表（kid=SPKI-DER-base64）
私钥已存在而公钥缺失时，从私钥推导公钥补齐，保证两侧始终成对。
"""
import base64
import os
import secrets
import subprocess
import sys
import tempfile

DEFAULT_PATH = "/data/ai-collab-staging/config/.env"

# 已下线变量（设计 2026-11-25：删除 mode/shadow/static 令牌与 planning/agent MQ 开关）。
REMOVED_KEYS = {
    "RERANK_MODE", "RERANK_SERVICE_TOKEN", "RERANK_MODEL_USER_ID",
    "DECIDE_MODE", "PLANNING_DRAFT_MODE",
    "INTERNAL_AI_TOKEN_MODE", "PLANNING_MQ_ENABLED", "AGENT_MQ_ENABLED",
    "AI_WORKER_TOKEN_MODE", "AI_WORKER_SERVICE_TOKEN",
    "AGENT_MQ_FALLBACK_POLL_MS", "AI_WORKER_ALLOW_INSECURE_INTERNAL", "AI_WORKER_MODEL_ALIAS",
}
REMOVED_PREFIXES = (
    "RERANK_SHADOW_", "DECIDE_", "PLANNING_DRAFT_",
    "ROCKETMQ_AGENT_RUN_", "ROCKETMQ_PLANNING_", "AI_WORKER_DECIDE_", "AI_WORKER_PLANNING_",
)


def is_removed(key):
    return key in REMOVED_KEYS or key.startswith(REMOVED_PREFIXES)


def b64(data):
    return base64.b64encode(data).decode("ascii")


def ed25519_keypair():
    """返回 (PKCS#8 DER base64 私钥, SPKI DER base64 公钥)。优先 cryptography，回退 openssl。"""
    try:
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    except ImportError:
        der = subprocess.run(
            ["openssl", "genpkey", "-algorithm", "ed25519", "-outform", "DER"],
            check=True, capture_output=True).stdout
        return b64(der), ed25519_public_from_private(b64(der))
    key = Ed25519PrivateKey.generate()
    private_der = key.private_bytes(
        serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption())
    public_der = key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return b64(private_der), b64(public_der)


def ed25519_public_from_private(private_b64):
    der = base64.b64decode(private_b64)
    try:
        from cryptography.hazmat.primitives import serialization
        key = serialization.load_der_private_key(der, password=None)
        return b64(key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))
    except ImportError:
        result = subprocess.run(
            ["openssl", "pkey", "-inform", "DER", "-pubout", "-outform", "DER"],
            input=der, check=True, capture_output=True)
        return b64(result.stdout)


def read_env(path):
    lines, values = [], {}
    if not os.path.exists(path):
        return lines, values
    with open(path, encoding="utf-8") as f:
        for raw in f.read().splitlines():
            stripped = raw.strip()
            if stripped and not stripped.startswith("#") and "=" in stripped:
                key = stripped.split("=", 1)[0].strip()
                if is_removed(key):
                    continue
                values[key] = stripped.split("=", 1)[1]
            lines.append(raw)
    return lines, values


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("STAGING_ENV_PATH", DEFAULT_PATH)
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    lines, values = read_env(path)
    existed = bool(lines)

    defaults = {
        "POSTGRES_DB": "ai_collab_staging",
        "POSTGRES_USER": "ai_collab_staging",
        "POSTGRES_PASSWORD": lambda: secrets.token_urlsafe(24),
        "REDIS_PASSWORD": lambda: secrets.token_urlsafe(24),
        "MINIO_ROOT_USER": "minio_staging_admin",
        "MINIO_ROOT_PASSWORD": lambda: secrets.token_urlsafe(24),
        "MINIO_BUCKET": "ai-collab-staging",
        "JWT_SECRET": lambda: secrets.token_urlsafe(48),
        "MODEL_CONFIG_MASTER_KEY": lambda: secrets.token_hex(16),
        "AUTH_ALLOWED_ORIGINS": "https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech,https://ai-collab-web.pages.dev",
        "MINIO_PUBLIC_ENDPOINT": "https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech",
        "STAGING_PORT": "8443",
        "APP_IMAGE": "ai-collab-backend:staging-39988959",
        "AUTH_PUBLIC_REGISTRATION_ENABLED": "true",
        # 应用数据库角色 ai_collab_app 的密码（C0.1：db-init 创建/同步角色，app-1/app-2 只用它连库；
        # POSTGRES_USER 超级用户只留给 db-init 与备份）。
        "APP_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
        # ai_worker 数据库角色密码（db-init 创建/同步角色；P2 起 ai-worker 经 AI_INDEX_DATABASE_URL 以 ai_worker 角色连 ai_index schema）。
        "AI_WORKER_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
        "INTERNAL_AI_ED25519_KID": "k1",
    }

    added = []
    for key, default in defaults.items():
        if key not in values:
            values[key] = default() if callable(default) else default
            added.append(key)

    kid = values["INTERNAL_AI_ED25519_KID"] or "k1"
    if "INTERNAL_AI_ED25519_PRIVATE_KEY" not in values:
        private_b64, public_b64 = ed25519_keypair()
        values["INTERNAL_AI_ED25519_PRIVATE_KEY"] = private_b64
        added.append("INTERNAL_AI_ED25519_PRIVATE_KEY")
        if "AI_WORKER_ED25519_PUBLIC_KEYS" not in values:
            values["AI_WORKER_ED25519_PUBLIC_KEYS"] = f"{kid}={public_b64}"
            added.append("AI_WORKER_ED25519_PUBLIC_KEYS")
    elif "AI_WORKER_ED25519_PUBLIC_KEYS" not in values:
        public_b64 = ed25519_public_from_private(values["INTERNAL_AI_ED25519_PRIVATE_KEY"])
        values["AI_WORKER_ED25519_PUBLIC_KEYS"] = f"{kid}={public_b64}"
        added.append("AI_WORKER_ED25519_PUBLIC_KEYS")

    if not existed:
        lines = ["# AI-Collab Staging Environment Configuration",
                 "# Automatically generated for InstaBox Staging deployment"]
    lines.extend(f"{key}={values[key]}" for key in added)
    content = "\n".join(lines) + "\n"

    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(path)), prefix=".env.")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
    os.chmod(path, 0o600)

    state = "updated" if existed else "created"
    print(f"SUCCESS: staging configuration {state} at {path} with mode 0600.")
    print("added keys: " + (", ".join(added) if added else "(none)"))


if __name__ == "__main__":
    main()
