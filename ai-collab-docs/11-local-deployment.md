# 本地部署指南

## 1. 目录准备

将文档包中的示例文件复制到源码仓库对应位置，然后进入仓库根目录：

```bash
cd ai-collab
cp .env.example .env
```

## 2. 环境变量

参考 `config/env.example`，至少填写：

- PostgreSQL 密码
- MinIO 管理密码
- JWT Secret
- 一个 Chat Model API Key
- 一个 Embedding Model API Key

## 3. 启动基础设施

```bash
docker compose -f deploy/docker-compose.yml up -d

docker compose -f deploy/docker-compose.yml ps
```

期望 PostgreSQL 和 Redis 显示 healthy，MinIO 显示 running；随后访问 `http://localhost:9001` 验证 MinIO 控制台。

## 4. 启动后端

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

验证：

```bash
curl http://localhost:8080/actuator/health
```

期望：

```json
{"status":"UP"}
```

## 5. 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

浏览器访问 `http://localhost:5173`。

## 6. MinIO

- 控制台：`http://localhost:9001`
- Bucket：`ai-collab`
- Bucket 必须保持 private
- 应用启动时自动创建 Bucket

## 7. 初始化用户

第一版推荐提供一个仅在 `local` Profile 启用的初始化器：

- username：`demo_owner`
- password：由环境变量 `DEMO_OWNER_PASSWORD` 指定
- displayName：`Demo Owner`

不得在源码中写固定密码。

## 8. 常见问题

### PostgreSQL 没有 vector 扩展

确认使用 `pgvector/pgvector:pg17`，并检查 Flyway 第一条语句：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

### 文档一直处于 PARSING

检查后端日志中的 `requestId`，确认解析线程池未满；重启后超过 10 分钟的任务会转为 FAILED，可点击重试。

### 模型额度用完

修改 `.env` 中默认 Chat Provider 和模型名称，重启后端。仅切换 Chat Model 不需要重建向量；切换 Embedding Model 需要重新索引。

### Windows ZIP 中文乱码

本项目所有 ZIP 内目录和文件名均为 ASCII，Java 包名也为 ASCII。Markdown 正文为 UTF-8。使用 VS Code、IntelliJ 或现代记事本打开。

## 9. 停止与清理

停止：

```bash
docker compose -f deploy/docker-compose.yml down
```

删除本地数据：

```bash
docker compose -f deploy/docker-compose.yml down -v
```

第二条命令会删除数据库与 MinIO 数据，只用于重置开发环境。
