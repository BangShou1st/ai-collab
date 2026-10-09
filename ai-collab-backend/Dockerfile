# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 先拷贝构建描述文件，利用镜像层缓存依赖下载
COPY pom.xml ./
COPY mvnw ./
COPY .mvn ./.mvn
RUN ./mvnw -B -q dependency:go-offline

COPY src ./src
RUN ./mvnw -B -DskipTests package

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar

USER appuser
EXPOSE 8080

# 全部配置通过环境变量注入（application.yml 已按 ${ENV_VAR:default} 形式声明）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
