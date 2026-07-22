# AI Collab Documentation Pack

本目录是“面向高校竞赛团队的 AI 项目协作平台”的开发文档包。第一版面向开发者本人和受邀请的测试用户，完全本地运行，GitHub 仅保存源码、文档、测试材料与演示视频。

## 固定技术基线

- Java 21
- Spring Boot 3.5.16
- Spring AI 1.1.8
- Maven 3.9+
- PostgreSQL 17 + pgvector
- Redis 7.4
- MinIO
- MyBatis-Plus
- Flyway
- Vue 3 + TypeScript + Element Plus
- Node.js 22+
- pnpm 10+
- Docker Compose

Spring AI 1.1.x 与 Spring Boot 3.5.x 配套使用。项目不使用微服务，采用模块化单体，并为未来拆出文档处理与 AI 服务保留边界。

## 命名与编码约束

- 仓库目录：`ai-collab`
- 后端目录：`backend`
- 前端目录：`frontend`
- 部署目录：`deploy`
- 文档目录：`docs`
- Java 根包：`com.shitulelv.aicollab`
- 数据库、Java 包、类名、文件名、ZIP 内路径全部使用 ASCII；不使用中文包名和中文目录名。
- Markdown 正文使用 UTF-8 编码，可正常写中文。
- API 前缀统一为 `/api/v1`。

## 文档索引

1. [项目需求规格](00-product-requirements.md)
2. [系统架构设计](01-system-architecture.md)
3. [模块与包结构](02-module-package-design.md)
4. [数据库设计](03-database-design.md)
5. [REST API 规范](04-api-specification.md)
6. [RAG 知识库设计](05-ai-rag-design.md)
7. [AI 任务规划设计](06-ai-task-planning-design.md)
8. [前端设计](07-frontend-design.md)
9. [权限与安全设计](08-security-permissions.md)
10. [测试与 AI 评测](09-testing-evaluation.md)
11. [开发规范](10-development-guide.md)
12. [本地部署指南](11-local-deployment.md)
13. [八周开发路线](12-eight-week-roadmap.md)
14. [演示与简历材料](13-demo-resume-guide.md)
15. [枚举与错误码](14-enums-error-codes.md)
16. [官方参考资料](15-official-references.md)
17. [OpenAPI 文件](api/openapi.yaml)
18. [数据库初始化 SQL](database/V1__init_schema.sql)
19. [本地配置示例](config/application-local.example.yml)
20. [环境变量示例](config/env.example)
21. [Docker Compose 示例](deploy/docker-compose.example.yml)
22. [最终设计规格](docs/superpowers/specs/2026-07-18-ai-collab-platform-design.md)
23. [实施计划](docs/superpowers/plans/2026-07-18-ai-collab-platform-implementation.md)

## 推荐阅读顺序

第一次阅读按 `00 → 01 → 02 → 03 → 04`；开始开发前再阅读 `08 → 09 → 10 → 11 → 12`。开发 AI 功能时重点阅读 `05` 和 `06`。

## 第一版不做

- 微服务、注册中心、API 网关和分布式事务
- 长期公网部署、开放注册和第三方登录
- 实时聊天、复杂日历、文件版本管理
- Excel、PPT 文档解析
- 模型训练、微调和多 Agent 编排
- 自动查询不同供应商的免费额度
- AI 未经用户确认直接写入任务数据
