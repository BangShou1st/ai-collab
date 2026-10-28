# syntax=docker/dockerfile:1
# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /workspace

# 先拷贝构建描述文件，利用镜像层缓存依赖下载
COPY ai-collab-backend/pom.xml ./ai-collab-backend/pom.xml
COPY ai-collab-backend/mvnw ./ai-collab-backend/mvnw
COPY ai-collab-backend/.mvn ./ai-collab-backend/.mvn
WORKDIR /workspace/ai-collab-backend
# Zen / Kilo 共享库经公开 JitPack 解析，无需 Maven 私服凭据。
RUN bash ./mvnw -B -q dependency:go-offline

COPY ai-collab-backend/src ./src
RUN bash ./mvnw -B -DskipTests package

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre-alpine@sha256:b48e02e0ea88d7bfb305335df94923bdef394905f4147dccd52e873cf17b1088

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app
COPY --from=build /workspace/ai-collab-backend/target/*.jar app.jar

USER appuser
EXPOSE 8080

# 全部配置通过环境变量注入（application.yml 已按 ${ENV_VAR:default} 形式声明）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
