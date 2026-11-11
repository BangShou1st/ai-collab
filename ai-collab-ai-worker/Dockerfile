# AI Collab Python 认知面（P0）运行镜像。
# 只用锁文件安装生产依赖：不装 dev 依赖、不 pip install、不在镜像里跑评测。
# 构建上下文是 ai-collab-ai-worker/（见同目录 .dockerignore）。
FROM python:3.13-slim

# uv 版本与 pyproject 的本地门禁、CI job 精确对齐；只拷贝二进制，不引入构建阶段。
COPY --from=ghcr.io/astral-sh/uv:0.12.2 /uv /uvx /bin/

# 运行时确定性：只用基础镜像自带的 CPython 3.13，不临时下载托管解释器；
# 不写 .pyc（非 root 运行，源码目录只读），输出不缓冲便于容器日志采集。
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    PATH="/app/.venv/bin:$PATH"

WORKDIR /app

# README.md 是 hatchling 构建元数据（pyproject readme 字段）必需项，缺了 uv sync 会失败。
# 先拷元数据与源码再装依赖：依赖层只随锁文件变化，源码改动不会让下载层失效。
COPY pyproject.toml uv.lock .python-version README.md ./
COPY src ./src
COPY prompts ./prompts

# --frozen：锁文件与 pyproject 不一致时直接失败，绝不静默改锁；
# --no-dev：镜像里不含 pytest/mypy/ruff；--no-editable：项目按 wheel 安装，避免挂载源码即可运行。
RUN uv sync --frozen --no-dev --no-editable \
    && rm -rf /root/.cache/uv

# 非 root 运行（uid/gid 10001）；数值 USER 不依赖 /etc/passwd 名称解析。
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --no-user-group --shell /usr/sbin/nologin app

# 配置只走 AI_WORKER_*（pydantic-settings allowlist）；PromptPack 以只读文件形式随镜像分发。
ENV AI_WORKER_HOST=0.0.0.0 \
    AI_WORKER_PORT=9100 \
    AI_WORKER_PROMPTS_DIR=/app/prompts

USER 10001:10001
EXPOSE 9100

# 就绪探针只打本机 9100，不依赖 curl/wget（slim 镜像里没有）。
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:9100/healthz', timeout=2)"]

CMD ["uvicorn", "ai_collab_worker.main:app", "--host", "0.0.0.0", "--port", "9100"]
