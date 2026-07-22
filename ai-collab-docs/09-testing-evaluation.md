# 测试与 AI 评测方案

## 1. 测试分层

### 1.1 单元测试

重点覆盖：

- 项目角色策略
- 任务状态转换
- 依赖环检测
- 文档文件类型校验
- 文本清洗和分块
- RAG 引用验证
- AI 草案日期、成员和依赖校验
- 幂等键冲突判断

### 1.2 集成测试

使用 Testcontainers：

- `pgvector/pgvector:pg17`
- Redis 7.4
- MinIO 容器

覆盖：

- Flyway 迁移成功
- MyBatis 映射与项目过滤
- pgvector 精确检索
- MinIO 上传、预签名和删除
- 任务规划确认事务回滚

### 1.3 API 测试

使用 `MockMvc` 或 `WebTestClient`：

- 未登录返回 401
- MEMBER 调用 ADMIN 接口返回 403
- 访问其他项目资源返回 404
- 参数错误返回统一错误体
- 文件过大返回 413
- 幂等重试返回同一结果

### 1.4 前端测试

- Vitest：Store、日期格式、依赖编辑器
- Vue Test Utils：任务表单、引用卡片、规划草案表格
- Playwright：登录 → 项目 → 上传 → 问答 → 规划确认主链路

## 2. AI 测试替身

默认测试不得调用真实模型。

```java
public final class FakeAiModelGateway implements AiModelGateway {
    private final Map<String, Object> responses;
    // 根据测试场景返回固定 Chat、Embedding 和结构化结果
}
```

真实供应商测试使用 `@Tag("ai-live")`，默认 Maven 测试排除。仅在本地有免费额度时手动运行。

## 3. RAG 评测集

建立 `backend/src/test/resources/evaluation/rag-cases.jsonl`，至少 60 条：

- 30 条可回答问题
- 15 条资料不足问题
- 10 条跨项目越权问题
- 5 条文档 Prompt Injection 问题

每条包含：

```json
{
  "caseId": "rag-001",
  "projectKey": "competition-demo",
  "question": "提交截止时间是什么？",
  "expectedDocument": "competition-rule.pdf",
  "expectedKeywords": ["2026-08-31", "截止"],
  "answerable": true
}
```

## 4. RAG 验收目标

这些是开发目标，不是预先声称的实际成绩：

| 指标 | 目标 |
|---|---:|
| Retrieval Hit@5 | ≥ 0.80 |
| 引用正确率 | ≥ 0.85 |
| 无答案拒答率 | ≥ 0.90 |
| 跨项目泄漏 | 0 |
| Prompt Injection 导致规则失效 | 0 |

## 5. 任务规划评测

建立 20 个固定场景：

- 不同项目时长
- 成员数量不足
- 文档包含明确评分点
- 已存在里程碑
- 模型返回不存在成员
- 模型返回依赖环
- 模型返回越界日期

目标：

| 指标 | 目标 |
|---|---:|
| 首次 JSON 可解析率 | ≥ 0.90 |
| 一次修复后可解析率 | ≥ 0.95 |
| 后端校验后依赖环 | 0 |
| 未确认即写入正式任务 | 0 |
| 重复确认产生重复任务 | 0 |

## 6. 性能测试

使用 k6 或 JMeter：

- 20 并发用户查询任务列表，持续 2 分钟
- 10 并发用户加载项目概览
- 5 并发用户发起知识问答，模型网关使用 Fake

目标：

- 非 AI API p95 < 500 ms
- 错误率 < 1%
- 项目列表和看板无 N+1 查询
- 单个 20 MB 文件上传不占满 JVM 堆

AI Provider 实际延迟只记录，不设置硬性 p95，因为免费模型服务不稳定。

## 7. 测试命令

```bash
cd backend
./mvnw clean test
./mvnw verify -Pintegration

cd ../frontend
pnpm install
pnpm test
pnpm build
pnpm exec playwright test
```

## 8. 完成证据

GitHub 仓库应保存：

- CI 测试结果
- RAG 评测 CSV/Markdown
- 任务规划评测结果
- k6/JMeter 报告
- 关键 Bug 排查记录
