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
# Docker Desktop 向构建容器注入 http(s)_proxy 环境变量，但 JVM 只有显式开启
# useSystemProxies 才会使用；无代理环境（如 CI）下自动回落直连，行为不变。
ENV JAVA_TOOL_OPTIONS=-Djava.net.useSystemProxies=true
RUN --mount=type=cache,target=/root/.m2/repository bash ./mvnw -B -q dependency:go-offline

COPY ai-collab-backend/src ./src
RUN --mount=type=cache,target=/root/.m2/repository bash ./mvnw -B -DskipTests package

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre-alpine@sha256:b48e02e0ea88d7bfb305335df94923bdef394905f4147dccd52e873cf17b1088

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app
COPY --from=build /workspace/ai-collab-backend/target/*.jar app.jar

USER appuser
# 8080：业务 HTTP；8081：Actuator/管理口（application.yml 的 management.server.port 默认值，
# 可用 MANAGEMENT_SERVER_PORT 覆盖）。两个端口都声明，便于编排工具与运维识别。
EXPOSE 8080 8081

# 全部配置通过环境变量注入（application.yml 已按 ${ENV_VAR:default} 形式声明）
# 健康检查必须打管理口 8081：actuator 实际监听 management.server.port，8080 上只有业务
# 端点（探 8080/actuator/health 会稳定 404，容器恒 unhealthy）。staging/prod compose 已同口径。
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
