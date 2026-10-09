# C1–C5 修复浏览器验收证据（bsk，2026-10-08 第二轮）

环境：本地开发栈。本轮新构建后端 `:8082`（profile=local，**受控窗口**临时配置
`target/c1c5-controlled.yml`：`mimo-v2.6-flash-free` 窗口压至 30000、输出预留 2000、
安全余量 500，仅作用于本实例，未改用户持久配置），前端 vite `:5174`
（`VITE_BACKEND_ORIGIN=http://127.0.0.1:8082`），生产数据库 `ai_collab`（Flyway v64，
未新增迁移）。模型：用户当前 AGENT 配置 `mimo-v2.6-flash-free`。
浏览器一律 `bsk`（`session start --no-focus`，后台不抢窗口，仅电脑端；
验收完成后 `session stop`，截图 `final-answer.png`）。

## 场景 1：简单事实查询

- 输入：「项目里当前有几个任务？分别是什么状态？」
- 实测：1 次工具调用（`list_tasks`），无无关扩容；该项目实际 0 任务，
  回答如实给出 `total=0、hasMore=false` 并逐状态列 0，不编造。
- 运行 `e3075370`：3 步 / 1 工具 / 输入 17070、输出 178，`SUCCEEDED`。

## 场景 2：四文档直接研究（引用与缺口）

- 输入：逐份深入阅读四份文档正文，说明容量上限/评分聚合/脱敏/验收阈值，要求引用与缺口。
- 实测：`来源（15）`，含 filename/heading/quote；如实标注
  `trust=SOURCE_DATA_ONLY`、"文档内容快照，非当前运行时能力"。
- 运行 `2810b096`：4 步 / 5 工具 / 输入 24423、输出 2042，`SUCCEEDED`。

## 场景 3：委派式全文研究（C5 子运行 + 父综合）

- 输入：用 `delegate_document_research` 逐份深入研究，每份读完整正文。
- 实测（数据库复核）：父运行 `d7c2789c`，`children_used=3`，10 步 / 8 工具，
  累计输入 141142、输出 3000+，`SUCCEEDED`；3 个子运行全部成功。
- 回答质量：四份文档正文均完整读取（`hasMore=false、truncated=false`）；
  关键数据逐项准确（单次导入上限 500 人；0.8% 为开发环境 1000 人样本实测、
  明确标注"尚未在 500 人并发场景验证"；跨文档阈值交叉引用）；
  引用逐条带章节定位。`final-answer.png` 为最终回答页面。

## 场景 4：刷新与跨标签恢复

- 离开会话页（/projects）再返回（含更换浏览器标签/重建 bsk 会话）：
  三个问题的完整问答、执行过程、`来源（15）` 引用与诊断完整恢复；
  状态「Agent 已完成」，无"继续"按钮，不重复发起运行。

## 受控压缩触发的如实说明

- 受控窗口 30000 → `H=27500`、`T≈23375` tokens（约 7 万字符）。
- 三轮真实运行的活跃上下文峰值（末次请求估算 9058 tokens，
  `MODEL_STARTED.inputBreakdown` 实证，且新字段 `runContextChars` 已接入）低于触发线，
  **本轮真实模型未触发 RUN_CONTEXT 压缩**——委派子运行各自独立管理上下文，
  父运行的子证据注入远小于 24k 字符上限。
- 压缩闭环（生成→提交→消费→缩小活跃视图、两周期推进、partial 偏移、
  幂等/fencing）由隔离回归覆盖：`AgentRunContextCompactionRegressionTest`（4 项，
  未修复基线 4/4 红灯）、`AgentRunContextCommitPostgresTest`（6 项，真实 PostgreSQL）。
  **不把本轮浏览器运行表述为"已实测压缩周期"。**

## 环境问题（单独说明）

- Edge 扩展文件上传权限仍被拦截（沿用上轮结论），本轮未再走上传路径；
  复用上轮已上传的 4 份 READY 文档。
- `:8080`/`:8081`/`:5173` 为用户既有进程，本轮后端用 `:8082`、前端用 `:5174`
  （并在本实例放行 `http://localhost:5174` 来源，属性键 `security.cors.allowed-origins`）。
- Testcontainers 在全量测试中出现过 1 次端口映射 `BindException`（环境抖动），
  单独重跑该类 23/23 通过。
