# Cloudflare 部署可行性审计报告

**项目**：ai-collab（AI 协作项目管理平台）
**审计日期**：2026-09-06
**审计范围**：完整项目架构分析与 Cloudflare 部署可行性评估

---

## 1. 当前项目架构

### 1.1 整体结构

| 目录 | 说明 |
|------|------|
| ai-collab-frontend/ | Vue 3 + Vite + TypeScript 前端 |
| ai-collab-backend/ | Spring Boot 4.1 + Java 21 后端 |
| ai-collab-deploy/ | Docker Compose 部署配置 |

### 1.2 前端技术栈

| 项目 | 版本 |
|------|------|
| Vue | 3.5.x |
| Vite | 7.1.x |
| TypeScript | 5.8.x |
| Element Plus | 2.10.x |
| Pinia | 3.0.x |
| Wrangler | 4.129.x |

### 1.3 后端技术栈

| 项目 | 版本 |
|------|------|
| Spring Boot | 4.1.0 |
| Java | 21 |
| MyBatis-Plus | 3.5.17 |
| MinIO SDK | 9.0.2 |
| Apache Tika | 3.3.0 |

---

## 2. 后端运行依赖分析

### 2.1 外部服务依赖

| 依赖 | 必要性 | 用途 |
|------|--------|------|
| PostgreSQL | ✅ 必须 | 所有业务数据持久化 |
| Redis | ⚠️ 增强 | 限流计数器（有本地 fallback） |
| MinIO | ✅ 必须 | 文档文件存储 |
| SSE | ✅ 核心 | Agent 事件流、知识库问答 |
| 定时任务 | ⚠️ 运维 | Agent 恢复、通知提醒 |

### 2.2 PostgreSQL 使用详情

**ORM**：MyBatis-Plus 3.5.17
**数据表**：38 个迁移，35+ 张表

**PostgreSQL 特有特性**：

| 特性 | 使用情况 | D1 兼容 |
|------|----------|---------|
| UUID 主键 | gen_random_uuid() 几乎所有表 | ❌ |
| JSONB | 大量使用 | ❌ |
| pgcrypto | gen_random_uuid() | ❌ |
| pgvector | 向量搜索 | ❌ |
| PL/pgSQL 触发器 | 防删除已确认计划 | ❌ |
| CHECK 约束 | 大量使用 | ⚠️ |
| timestamptz | 所有时间列 | ❌ |

**D1 迁移结论：❌ 不可行**

### 2.3 Redis 使用详情

3 处限流计数器，全部有本地 fallback：
- KnowledgeRateLimiter（问答限流）
- PlanningAttemptThrottle（防刷）
- PlanningGenerationRateLimiter（生成限流）

**结论**：Redis 可完全省略。

### 2.4 MinIO 使用详情

唯一存储网关：MinioDocumentStorageGateway.java
功能：put/open/presign/delete
**R2 迁移难度：低**

---

## 3. SSE / 实时通信

Agent 事件流和知识库问答使用 SseEmitter。
Containers 运行 Java → SSE 正常。

---

## 4. 前端部署分析

当前 Workers Static Assets 纯静态部署。
问题：没有 API 反向代理，/api/* 404。

---

## 5. 鉴权机制

- Access Token：JWT Bearer Header（内存）
- Refresh Token：HttpOnly Cookie，SameSite=Strict
- CORS：精确 Origin 白名单
- Session：无状态

跨域风险：Cookie SameSite=Strict → 需同一域名 + Worker 反代

---

## 6. Cloudflare 部署方案评估

### 🏆 方案 A：Cloudflare Containers（推荐）

**组件映射**：

| 当前组件 | Cloudflare 方案 |
|----------|-----------------|
| 前端 SPA | Workers Static Assets |
| Spring Boot | Cloudflare Containers |
| PostgreSQL | 外部托管 + Hyperdrive |
| Redis | 省略 |
| MinIO | Cloudflare R2 |
| API 反代 | Worker 脚本 |

**改动**：约 5 个文件

| 文件 | 改动 |
|------|------|
| 新增 Dockerfile | 容器化 |
| MinioDocumentStorageGateway.java | R2/S3 适配 |
| application.yml | 连接配置 |
| CORS 配置 | 添加域名 |
| .env | R2 环境变量 |

**成本**：$5-15/月

### 方案 B：Workers 重写
❌ 不推荐，460+ Java 文件，数月工作量

### 方案 C：外部 Java 平台
✅ 可行，但 Cookie 跨域问题

---

## 7. Docker 检查

项目无 Dockerfile，需新增。

---

## 8. Cloudflare 服务对应关系

| 组件 | 方案 |
|------|------|
| 前端 | Workers Static Assets |
| Spring Boot | Containers |
| PostgreSQL | 外部 + Hyperdrive |
| Redis | 省略 |
| MinIO | R2 |

---

## 9. 修改文件清单

### 新增
- Dockerfile

### 修改
- MinioDocumentStorageGateway.java
- pom.xml
- application.yml
- .env
- wrangler.toml

### 不修改
- 所有 Controller/Service/Domain
- MyBatis Mapper
- 前端 Vue 组件

---

## 10. 风险评估

| 风险 | 等级 | 缓解 |
|------|------|------|
| 冷启动 | 中 | min_instances |
| PG 延迟 | 低 | Hyperdrive |
| Cookie 跨域 | 中 | Worker 反代 |

---

## 11. 最终推荐

### 🏆 方案 A：Cloudflare Containers

1. 改动最小
2. 风险最低
3. 成本合理（$5-15/月）
4. 功能完整
5. 全球部署

### 部署步骤

1. 准备外部 PostgreSQL
2. 准备 R2 Bucket
3. 修改 MinIO → R2
4. 创建 Dockerfile
5. 配置 Containers
6. 配置 Worker 反代
7. 配置 CORS/Cookie
8. 部署验证

---

## 12. 方案对比

| 维度 | 方案A | 方案B | 方案C |
|------|-------|-------|-------|
| 改动 | 小 | 巨大 | 中 |
| 数据库 | ✅ | ❌ | ✅ |
| SSE | ✅ | ⚠️ | ✅ |
| 成本 | $5-15 | $0-5 | $5-20 |
| 推荐 | ⭐⭐⭐⭐⭐ | ⭐ | ⭐⭐⭐ |

---

*审计报告完成*