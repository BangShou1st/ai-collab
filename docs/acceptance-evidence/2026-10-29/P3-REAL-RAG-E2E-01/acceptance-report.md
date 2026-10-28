# P3-REAL-RAG-E2E-01 验收报告 ｜ 真实模型接入与 RAG 闭环

- 日期：2026-10-29
- 最终判定：**REAL_RAG_ACCEPTED**
- Git SHA：`dev@5c8e31b`（修复提交，CI run `40728163278` 全绿）
- 服务器镜像：`ai-collab-backend:staging-5c8e31b`（仅重建 app 容器，其余容器未动）
- 前端：https://ai-collab-web.pages.dev/ （Production `11dfcfc`，本轮未改动）
- 后端：InstaBox `box-aae54f`（AWS us-west-2 出口）

## 1. 变更清单

| 提交 | 内容 |
| --- | --- |
| `5c8e31b` | fix(embedding): 兼容 Gemini OpenAI 端点缺失 `data[].index` 的响应；test-candidate 改用请求维度探测；网关异常记录完整堆栈；新增 `ProjectEmbeddingGatewayResponseCompatTest` 回归测试 |

部署方式：盒子仓库 `git pull` 后 `docker build` 新镜像，`docker compose -p ai-collab-staging up -d app` 仅重建 app 容器。数据库迁移数保持 V68，未修改 Cloudflare Pages。

## 2. 根因分析与修复（本轮关键发现）

P2 遗留阻断「系统嵌入模型未配置」的真正原因不是缺少配置，而是兼容性缺陷：

1. Gemini `generativelanguage.googleapis.com/v1beta/openai/embeddings` 的响应 `data[]` 项**不含 `index` 字段**（仅有 `object`、`embedding`）。
2. 既有网关强制校验 `index != null` 且必须等于数组下标，不满足即抛 `DOCUMENT_EMBEDDING_FAILED`（HTTP 502）。
3. 该失败路径无任何日志输出（BusinessException 处理器与静默校验路径均不打日志），导致代理日志只见 502、应用日志零痕迹。

修复策略为增量兼容，**完整保留原 OpenAI 语义**：全部缺失 `index` 时按响应顺序对齐输入；部分缺失仍视为响应不合规拒绝；有 `index` 时仍然排序并校验 `index == 下标`。回归测试三条路径全覆盖（含 OpenAI 标准路径）。

## 3. Gemini Embedding 实测

| 项 | 值 |
| --- | --- |
| Provider | `OPENAI_COMPATIBLE` |
| Endpoint | `https://generativelanguage.googleapis.com` + `/v1beta/openai/embeddings` |
| 模型 | `gemini-embedding-001`（稳定版，未引入 Embedding 2） |
| 维度 | **768**（test-candidate 真实响应确认：`{"dimensions":768,"latencyMs":437}`；数据库 `vector_dims=768` 再次确认） |
| Batch Size | 1（首轮） |
| 错误码 | 无（修复后）；修复前为 502 `DOCUMENT_EMBEDDING_FAILED`（响应缺 `index`） |
| 配置指纹 | `d7f3eaa36d7d703f484d466c9143a889854611e9b402fc0cd1849bc5a2bf2430`，`staleChunks=0`（无既有索引受影响） |

密钥经系统加密存储（`MODEL_CONFIG_MASTER_KEY` 未变更），接口仅返回 `hasApiKey: true`。

## 4. 魔搭（ModelScope）聊天模型实测

| 项 | 值 |
| --- | --- |
| Provider | `OPENAI_COMPATIBLE`，`https://api-inference.modelscope.cn` + `/v1/chat/completions` |
| 模型 | `Qwen/Qwen3.8-Flash-Next`（model-discovery 返回 37 个可用模型中选取） |
| 最小生成验证 | `POST /user/ai-providers/{id}/test` → `SUCCESS`，返回内容「连接成功」，`promptTokens=67, completionTokens=101, latencyMs=4179` |
| 用途绑定 | 用户级 Provider 绑定 `KNOWLEDGE_CHAT`（管理员与普通用户凭据隔离验证：admin 创建的 Provider 对用户 A 不可见，符合预期） |

## 5. RAG 闭环逐项证据

| # | 项 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 隔离测试项目创建 | PASS | 用户 `p3owner6192` 创建 `caf6ca39-f93d-47b1-990c-e892c8d4086a` |
| 2 | 合成文档上传 ×2 | PASS | `8f1d959a…`（文档A）、`db66c265…`（文档B），状态 `UPLOADED` |
| 3 | 向量索引自动完成 | PASS | 两份文档自动进入 `READY` |
| 4 | pgvector 真实向量 | PASS | `SELECT count(*), vector_dims(embedding) … → 8 行 / 768`；向量样例 `[-0.011362527,0.013237107,…]` |
| 5 | SSE 流式真实生成 | PASS | Q1：17 个 `token` 事件 + `citations` + `done`，内容为真实模型生成（见 `sse-q1-stream.txt`） |
| 6 | 答案正确性 | PASS | 「星桥 StarBridge」「验收工程师阿灵」与文档 A 原文一致 |
| 7 | 来源引用指向 | PASS | Q1 引用 [S1][S5] → 文档A「旗舰特性」chunk（相似度 0.7797）；Q2 引用 → 文档B「特殊术语」「积分规则」chunk（相似度 0.7270） |
| 8 | 刷新后持久化 | PASS | 重新登录后 `GET session` 返回 `messages=2`；刷新后继续提问（Q2）正常 |
| 9 | 无证据拒答 | PASS | Q3（虚构问题）回答「当前项目资料不足以回答该问题」并说明 SOURCES 实际范围，未编造 |
| 10 | 权限隔离 | PASS | 非成员用户 `p3out6192` 访问项目详情/文档列表/文档正文/知识会话均返回 `404 PROJECT_NOT_FOUND`（不泄露存在性） |

## 6. 额度与请求数量（真实外部调用台账）

Gemini Embedding（真实到达 Google 的请求，共 **13** 次 ≤ 20 上限）：

1. 盒子直连验证 ×1（768 维，200）
2. 修复前 test-candidate ×2（响应解析静默失败，各消耗 1 次调用）
3. app 容器内 Java 探针 ×1（无 dimensions，返回默认 3072 维）
4. 修复后 test-candidate ×1（768 维，200）
5. 文档索引 8 chunks × 1 = 8 次（batchSize=1）

另有 2 次本地直连请求被 Google 以 `400 FAILED_PRECONDITION`（地区限制）拒绝，未产生推理；edge 网络 404 探测 1 次未携带密钥。

魔搭生成请求共 **4** 次 ≤ 10 上限：Provider test ×1、QA Q1-Q3 ×3。

## 7. 失败项、已修复问题与剩余风险

已修复：

1. Gemini OpenAI 兼容端点 `data[].index` 缺失导致全部嵌入静默失败（本轮唯一阻断，已修复 + 回归测试 + 镜像重部署）。
2. test-candidate 硬编码 `dimensions=0`，无法探测目标维度（已改为透传请求维度）。
3. 网关异常日志只记 `e.getMessage()`（可能为 null），已改为记录完整堆栈。

无未修复失败项。

剩余风险与建议：

1. **魔搭 API Key 已在验收会话中意外暴露一次（终端回显），建议尽快在魔搭控制台轮换**。Gemini Key 未发生明文暴露。
2. `data[].index` 全缺失时的「按响应顺序对齐」语义依赖上游按输入顺序返回（Gemini 实测如此）；若未来上游乱序返回且不带 index，属可感知风险，回归测试已将该行为显式化。
3. 批量索引 batchSize 仍为 1（首轮稳妥配置）；后续可调大以降低请求数，需另行评估限流。
4. 本轮未触碰其他用户项目、全局索引与既有数据；未合并 main。
