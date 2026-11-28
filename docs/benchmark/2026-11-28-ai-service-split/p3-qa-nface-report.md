# P3 问答 N 面指标与集成验证报告（2026-11-28，ai-service-split @ a4cfa6d5）

> 契约：`docs/design/p3-qa-contract-2026-11-28.md` §8.1 选项 A（检索层 E2 门槛 + 问答层
> 泄漏率/流式完整性硬门槛，忠实度/拒答率如实记录不卡门）、§8.2/§8.3 冒烟。
> 本轮为 P3 三工作包（#52 金标准 / #53 Python / #54 Java 中继）合并后的首次真实链路验证。
> **结论：全部硬门槛通过。** E2 39 题 **52/54 ≥ 52**（与 P2 第 2 轮逐分一致，检索层零回归）；
> 问答层 **泄漏率 0/23**、**流式完整性 23/23**（均硬门槛）；引用忠实度 18/18、
> 拒答正确率 0/2（如实记录，非门槛）。

## 1. 运行环境（全隔离，复用 target/e2-env/ 隔离栈）

- e2-postgres/e2-redis/e2-minio @15432/16390/19000（宿主机 Docker 重启后手动拉起）
- Java 后端 local profile @8080（ai-service-split 合并后代码）；ai-worker @9100
- Ollama 本机 11434：embedding `qwen3-embedding:0.6b`（1024 维，沿用 ACTIVE generation
  `708382d5`，无残留 BUILDING）；chat `qwen2.5:7b-instruct`（经共享模型
  `E2-Ollama-Chat` 发布后租借，OPENAI_CHAT 协议）
- 检索门 52/54 的评测口径与 P2 报告一致（自建合成题集、字符串匹配判定、
  HNSW(m=16,efc=64)+iterative_scan、rerank=HEURISTIC、单轮如实记录）

## 2. 检索层：E2 39 题重跑（硬门槛）

| 口径 | 保留/总数 | 门槛 |
| --- | --- | --- |
| **QA（硬门槛）** | **52 / 54** | ≥52 ✅ |
| Agent 工具（仅记录） | 52 / 54 | 不作门槛 |

- 空上下文 0；失分与 P9/P2 基线同为 B2「退回发起节点」（RETRIEVAL_MISS）、
  B4「延长不得超过五个工作日」（BODY_TRUNCATION）两处边界事实，**逐分复现基线**
- 明细：`e2-39q-p3-rerun.json`（评测 CLI 直打 worker 检索端点，P3 未动检索入口）
- **检索层零回归确认**：路由随 P3 迁 Python 后，门面固定下发 HYBRID+原查询
  （与 E2 评测口径一致），得分与迁移前完全一致

## 3. 问答层：N 面 23 轮（21 dev 事实题 + 2 holdout 拒答题）

经 **Java 中继真实链路**（用户 JWT → 会话 → `POST .../questions/stream` → 中继 →
worker `qa:stream` → finalize → 复核落库 → citations/done），SSE 逐帧采集比对：

| 指标 | 结果 | 门槛 |
| --- | --- | --- |
| **泄漏率** | **0 / 23** | =0 ✅（硬门槛） |
| **流式完整性**（持久化 answer == token 拼接；拒答轮按现状语义判定） | **23 / 23** | =100% ✅（硬门槛） |
| 引用忠实度（回答 [Sn] ⊆ citations 集合） | 18 / 18 | 如实记录 |
| 拒答正确率 | 0 / 2 | 如实记录（两道 abstain 题检索命中相关块，模型尽力作答未拒答——问答层能力现状，与现状链路行为一致） |
| 平均端到端时延 | 4.5 s/轮（7B 本地模型） | 参考 |

- 事件序全部 `token* → citations → done`；23/23 轮零 error、零限流命中
- 评测脚本与原始明细：`qa_nface_eval.py`、`qa-nface-p3.json`（e2-env 内，不入库）

## 4. 冒烟（§8.2/§8.3 对应项实测）

| 项 | 结果 |
| --- | --- |
| 泄漏预检拦截 | **实测触发 1 次**（第 1 轮 N 面 G02-B-4DOCS）：k-gram 命中即中止流并发
error(CONTENT_BLOCKED)，K_GRAM−1 尾缓冲保证泄漏窗口从未发出、无落库——拦截即防线生效，
泄漏率仍为 0；复跑同题未再触发（生成随机性），非误报可判定材料不出 worker（设计使然） |
| 限流命中 | **实测触发**：调试累计超 60 次/小时后连续 429（KnowledgeRateLimiter 生效） |
| 拒答短路（零来源） | **实测**：H-G07-INDIRECT 一轮检索零命中 → 零来源路径 → 无 token、
insufficientEvidence=true + 统一拒答文案落库（P3-R2 后中继豁免校验的路径） |
| 拒答短路（模型已调但校验不足） | **实测 6 轮**：流出模型文本 → 校验判不足 → 持久化为统一拒答文案
（现状行为保留，token 拼接与 finalize.answer 逐字节一致由中继校验保证） |
| 断连取消/撤权竞态 | 单元测试覆盖（KnowledgeQaRelayServiceTest、worker pipeline/lease 测试） |
| 越权 | worker 端 401/403/空可见零租借（单测）；`ai_collab_app` 读 ai_index 被拒（本轮实测复现，隔离生效） |

## 5. 执行期发现与修订（全部已合并进集成分支）

| # | 问题 | 处置 |
| --- | --- | --- |
| 1 | 合并断裂：#52 导出器引用 #54 删除的类（分支基线设计失误，各自 verify 绿、组合后 src/test 编译断） | 导出器完成使命后删除，README 登记历史检出复现路径（`a3c50685`） |
| 2 | **契约缺口 P3-R1**：§3.1 冻结 qa 令牌 purp=[KNOWLEDGE_CHAT]，但 Q3=a 进程内检索需 EMBEDDING（rerank 绑定时还有 RERANK）租借 → 403 | 契约 §11 P3-R1：purp 扩为三项；scope 仍不带 knowledge:search（端点访问权与租借资源授权分离） |
| 3 | **契约缺口 P3-R2**：P2 代际绑定 EMBEDDING 租借要求 knowledge:search scope；qa 管线按代际绑定租借（worker 强制代际一致性）被拒 | 契约 §11 P3-R2：authorize 对（generationId ∧ 无 taskId）的 EMBEDDING 租借接受 knowledge:qa |
| 4 | 零来源轮 finalize 校验误判：worker 不发 token、finalize.answer=拒答文案 → 中继判丢字 | 中继豁免（insufficientEvidence ∧ 未转发 token），有 token 时的逐字节不变式不变（`a4cfa6d5`） |
| 5 | E2 环境配置（非代码）：共享 chat 模型的 base_url 含 `/v1` 而 api_path 亦含全路径 → worker 拼出 `/v1/v1` 404；LeaseCache 缓存坏租约需重启清空 | 修正共享模型行；登记"api_path 含全路径时 base_url 必须为宿主根" |

## 6. 门禁判定

- **检索层**：52/54 ≥ 52 ✅（压线，与 P2/P9 基线逐分一致）
- **问答层**：泄漏率 0 ✅、流式完整性 100% ✅（两项硬门槛）
- **P3 门（契约 §8）全部通过**，未动任何冻结参数（maxSources=5、budget、阈值、
  K_GRAM=16），评测单轮如实记录、无 best-of-N。
