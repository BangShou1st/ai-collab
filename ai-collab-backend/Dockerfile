# syntax=docker/dockerfile:1
# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /workspace

# 可选：网络受限环境为 Maven 中央仓库配置直连镜像（如 https://maven.aliyun.com/repository/public）；
# 同时覆盖 Maven Wrapper 自身的发行版下载源（MVNW_REPOURL）。留空（默认，CI 与常规
# 环境）时使用默认仓库，行为不变。
ARG MAVEN_CENTRAL_MIRROR=""
ARG MVNW_REPO_URL=""
ENV MVNW_REPOURL="${MVNW_REPO_URL}"
RUN if [ -n "$MAVEN_CENTRAL_MIRROR" ]; then \
      mkdir -p /root/.m2 && \
      printf '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><mirrors><mirror><id>mirror-central</id><url>%s</url><mirrorOf>central</mirrorOf></mirror></mirrors></settings>' "$MAVEN_CENTRAL_MIRROR" > /root/.m2/settings.xml; \
    fi

# 先拷贝构建描述文件，利用镜像层缓存依赖下载
COPY ai-collab-backend/pom.xml ./ai-collab-backend/pom.xml
COPY ai-collab-backend/mvnw ./ai-collab-backend/mvnw
COPY ai-collab-backend/.mvn ./ai-collab-backend/.mvn
WORKDIR /workspace/ai-collab-backend
# Zen / Kilo 共享库经公开 JitPack 解析，无需 Maven 私服凭据。
# Docker Desktop 向构建容器注入 http(s)_proxy 环境变量，但 JVM 只有显式开启
# useSystemProxies 才会使用；无代理环境（如 CI）下自动回落直连，行为不变。
ENV JAVA_TOOL_OPTIONS=-Djava.net.useSystemProxies=true
RUN --mount=type=cache,target=/root/.m2/repository bash ./mvnw -B -q dependency:go-offline

COPY ai-collab-backend/src ./src
RUN --mount=type=cache,target=/root/.m2/repository bash ./mvnw -B -DskipTests package

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre-alpine@sha256:b48e02e0ea88d7bfb305335df94923bdef394905f4147dccd52e873cf17b1088

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Zen v1.6.1 中继依赖本机 Bun（免费档拒绝非 Bun 指纹）；Linux 无自动下载兜底，
# 镜像内预装固定版本（musl 构建，SHA256 校验），安装后自检版本。
ARG BUN_VERSION=1.4.4
ARG BUN_MUSL_SHA256=a5a8ccc5e74dcb883fe974f125a7bdb3b41e5195ed1b870e73f17effd3eb2b2d
USER root
RUN apk add --no-cache curl unzip libstdc++ libgcc \
    && curl -fsSL "https://github.com/oven-sh/bun/releases/download/bun-v${BUN_VERSION}/bun-linux-x64-musl.zip" -o /tmp/bun.zip \
    && echo "${BUN_MUSL_SHA256}  /tmp/bun.zip" | sha256sum -c - \
    && (cd /tmp && unzip -q bun.zip) \
    && install -m 0755 /tmp/bun-linux-x64-musl/bun /usr/local/bin/bun \
    && rm -rf /tmp/bun.zip /tmp/bun-linux-x64-musl \
    && bun --version
ENV ZEN_BUN=/usr/local/bin/bun

WORKDIR /app
COPY --from=build /workspace/ai-collab-backend/target/*.jar app.jar

USER appuser
EXPOSE 8080

# 全部配置通过环境变量注入（application.yml 已按 ${ENV_VAR:default} 形式声明）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
