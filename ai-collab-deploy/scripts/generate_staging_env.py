#!/usr/bin/env python3
"""
生成 Staging 环境凭据文件到 /data/ai-collab-staging/config/.env
文件权限强制设为 0600，不向标准输出打印真实密码值。
"""
import os
import secrets
import sys

TARGET_PATH = "/data/ai-collab-staging/config/.env"

def main():
    os.makedirs(os.path.dirname(TARGET_PATH), exist_ok=True)
    
    pg_password = secrets.token_hex(16)
    redis_password = secrets.token_hex(16)
    minio_password = secrets.token_hex(16)
    jwt_secret = secrets.token_urlsafe(32)
    master_key = secrets.token_hex(16)

    env_content = f"""# AI-Collab Staging Environment Configuration
# Automatically generated for InstaBox Staging deployment
POSTGRES_DB=ai_collab_staging
POSTGRES_USER=ai_collab_staging
POSTGRES_PASSWORD={pg_password}
REDIS_PASSWORD={redis_password}
MINIO_ROOT_USER=minio_staging_admin
MINIO_ROOT_PASSWORD={minio_password}
MINIO_BUCKET=ai-collab-staging
JWT_SECRET={jwt_secret}
MODEL_CONFIG_MASTER_KEY={master_key}
AUTH_ALLOWED_ORIGINS=https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech,https://ai-collab-web.pages.dev
STAGING_PORT=8443
APP_IMAGE=ai-collab-backend:staging-39988959
AUTH_PUBLIC_REGISTRATION_ENABLED=true
"""

    with open(TARGET_PATH, "w", encoding="utf-8") as f:
        f.write(env_content)
    
    os.chmod(TARGET_PATH, 0o600)
    print(f"SUCCESS: Staging configuration created at {TARGET_PATH} with mode 0600.")

if __name__ == "__main__":
    main()
