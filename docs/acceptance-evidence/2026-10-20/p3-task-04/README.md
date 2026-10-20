# P3-TASK-04：资料失败处理指引与单项重试反馈

日期：2026-10-20（Asia/Shanghai）。批准范围：**仅 U-04 最小增量**。

## 基线与范围

开工通过 `git status`、`git rev-parse HEAD`、`git ls-remote` 核实：本地 `dev` 与远端均为
`ad6e888c62f36901a9e7828cf8b74cd8efd2821c`，工作区干净；`main` 为
`6303b4a8011ebb1b3f9617df3e910ef7c7f65df7`。没有修改或合并 main。

已有能力包括 FAILED 单项重试、READY 重索引、状态轮询、管理员校验、项目可写校验及状态条件更新。
本轮只改善使用路径，不重建处理链；无后端代码、数据库迁移或 API 契约变更。
U-05、E-01、E-02 未实施，六份历史文档未操作。

## 实现

- `document-failure.ts` 仅精确匹配 `DocumentProcessingService.safeMessage` 已有的八种安全文案。
  已知原因展示处理建议与责任角色；向量化错误只说明处理阶段，不推断网络、凭据或具体根因。
  系统 Embedding 由系统管理员检查；项目所有者/管理员执行文档重试。个人 AI 设置不用于文档索引。
- 未知、空值及历史错误统一显示“处理失败，原因暂不明确”；不直接渲染保存的错误原文，防止意外泄露内部细节。
  后端新增文案若未纳入精确映射，会安全降级为未知，不做关键词猜测。
- 列表提供原生按钮“查看处理建议”，可用键盘打开；详情将处理建议放在正文和元数据前。
  编码、大小等不可由重试修复的问题明确建议修正/拆分后重新上传。
- 重试按钮展示提交中并禁用重复操作；提交后只表示“已提交处理”，以服务端最新状态为准。
  请求拒绝或响应不确定时显示“未确认成功”，随后刷新状态与项目权限。
- 增加“刷新状态”；加载失败标记状态未知与旧数据可能过时，禁止在旧状态上重试。
  详情抽屉也显示未知提示，并标注“上次状态（可能过时）”，不继续宣称正在后台处理。
  权限加载失败时撤下管理入口；文档列表返回 401/403/404 时清除旧资料与详情。
  COMPLETED/ARCHIVED 项目禁用重试并说明只读原因。
- 详情宽度适配窄屏，长内容可换行；状态反馈使用 `role="status"`。

没有自动重试、批量恢复、模型探测或额外索引请求；原有 reindex/reindexAll 行为保留。

## 自动化

| 检查 | 实际结果 |
| --- | --- |
| `pnpm exec vitest run` | PASS：49 文件、373 项；原基线 47 文件、348 项 |
| `pnpm lint` | PASS：0 errors、77 warnings，与开工基线一致 |
| `pnpm typecheck` | PASS |
| `pnpm build` | PASS；既有 PURE 注释、大 chunk 提示保留 |
| `git diff --check` | PASS |
| Impeccable detector | 已执行，目标 DocumentView.vue，结果 `[]`；不替代浏览器验收 |

新增 25 项：安全原因/未知原因与隐私兜底 13 项；资料页面行为 12 项。
页面测试覆盖 Owner/Admin/Member、两类只读状态、无自动重试、重复点击、提交中反馈、
INDEXING/READY 与重新挂载、请求失败后权限刷新、列表加载失败未知、撤权清空以及权限无法刷新。
READY 分支为自动化确定性数据，不代表本轮真实索引恢复。

本轮不修改后端，因此没有为展示逻辑另造后端测试；后端全量 Maven verify 由现有 CI 执行，
没有关闭容器守卫、禁用测试或降低覆盖率。命令摘录见 [checks.txt](checks.txt)。

## 真实浏览器与真实后端

使用 browser-skill / BSK 0.3.3、真实 Edge 155，后台 session `kaxj`；抽屉补验使用 `ddks`。补验前工具报告 `kaxj` 已不存在，
在同一 Edge 实例新建会话继续；未主动提前结束未完成的验收。
复用本地后端 8080 与 Vite 5173。桌面约 1541×847 CSS px；窄屏为真实 Edge 的
390×844@3x CDP 仿真，不是物理手机。

合成账号 `p3u04_owner`、`p3u04_admin` 与项目“U04 失败资料验收”通过既有业务 API 创建。
第二账号初为项目 ADMIN，后经 Owner 的正常 API 降为 MEMBER、移出项目。
上传的 24 字节 `invalid-utf8.txt` 为无效 UTF-8，失败发生在文本解析阶段，不调用 Embedding 或生成模型。
无 SQL 直改、无历史业务数据操作、无系统模型配置修改。

| 场景 | 真实结果与证据边界 |
| --- | --- |
| 上传后解析失败 | 真实业务上传、后端处理为 FAILED；列表显示“文件编码不受支持”，详情建议改为 UTF-8 后重新上传 |
| Owner 单项重试、防重复 | BSK 双击重试只记录 **1 次 POST**，返回 202；界面显示已提交处理，仍如实显示编码失败，不宣称恢复成功 |
| Admin 单项重试 | 第二账号以 ADMIN 点击，真实 POST 返回 202；真实页面观察到“正在提交”与禁用的刷新按钮，完成后显示已提交处理 |
| 权限变化与 Member 边界 | Owner 在后端将 ADMIN 降为 MEMBER；旧页面仍有按钮时再点击，真实后端返回 **403**；显示“未确认成功”，刷新权限后移除重试入口。详情提示联系项目所有者或管理员 |
| 撤权与刷新 | Owner 移出第二账号后，BSK reload，真实项目 GET 返回 **404**；页面中性提示“项目不存在或无权访问”，无旧文档名、无重试按钮 |
| 只读项目 | 合成项目经合法状态转换为 COMPLETED，点击刷新后显示只读提示、重试禁用；随后恢复 ACTIVE。ARCHIVED 另有自动化覆盖，未在真实浏览器单独执行 |
| 加载失败 | BSK task-local rule 阻断一次文档列表 GET；显示当前状态未知、旧数据可能过时，重试禁用。后续真实刷新可继续读取状态。另在打开的详情中真实重试返回 202 后阻断列表刷新，抽屉同样显示未知与过时状态标记，且不再宣称正在后台处理 |
| 系统 Embedding 指引 | **受控响应**：对一次详情 GET 使用 mock 返回已有安全的向量化失败文案，真实 DOM 展示系统管理员检查系统 Embedding、具体原因未确认。没有制造真实 Embedding 故障 |
| 未知错误与隐私 | **受控响应**：详情返回未知错误和合成敏感标记；真实 DOM 显示原因暂不明确，敏感标记未出现在页面正文 |
| 键盘 | 原生建议按钮可 focus、Enter 打开详情；Tab/Escape 操作已执行；关闭后可继续页面操作。未触发 Agent 或下载 |
| 390px | 处理建议可读、自然换行；测得 `innerWidth=390`、页面 `scrollWidth=390`、详情宽度 374，建议位于可视区域内。Owner 与 Member 均已截图并查看 |

脱敏的路由、状态码、控制标记与 DOM 断言见 [browser-checks.json](browser-checks.json)。
Owner 双击对应 `d280b39553bad:n10`；Admin 202 与降权后 403 分别对应
`d6bfd68a837c1:n1`、`d6bfd68a837c1:n4`。原始 capture 保留在 Git 忽略的 `target/u04`，
公开文件不含请求头、Cookie、Token、密码或响应原文。
补验前通过 Owner 邀请 API 恢复第二合成账号的 ADMIN 成员资格。项目最终为 ACTIVE、
合成资料仍为编码失败，账号和资料保留用于复验。

截图：[Owner 390px](03-owner-narrow.png)、[Member 390px](05-member-narrow.png)。
截图为滚动后的真实视口，只含合成资料与账号；均逐张查看。桌面视口截图调用多次发生 BSK RPC timeout，
因此未归档旧布局或失败截图；桌面结论来自 BSK DOM、交互、网络证据，不声称桌面截图验收通过。
一次早期控制规则误用于请求修改，控制失败并中止该 GET；已保留为 `intervention.state=failed`，
不认定为产品故障；随后使用明确的 mock 响应完成两个展示分支验证。

## 未验证与风险

- 真实模型调用、真实 Provider 探测与真实 429：**NOT_TESTED**，没有外推为通过。
- 本轮没有将合成资料真实恢复至 READY，也未制造真实 Embedding/存储故障；成功恢复由自动化覆盖，
  真实索引恢复须在另行获准的可控依赖环境中验证。
- 六份历史资料的当前状态与可恢复数未知，R-1 保留既有状态；R-2～R-5 不因本轮升级。
- 不宣称所有解析错误均已分类，不宣称所有 Provider、并发场景、物理手机或公网部署通过。
- 后端清表死锁再次出现，见下一节；未根治，不能用后续绿色 CI 抹去失败记录。

## Git、CI 与回退

主功能与自动化提交：`4bf14af8495dad01fb2674a93c0f4cb09ecd28c6`，普通推送 `dev`。

[CI 39420753335 attempt 1](https://github.com/BangShou1st/ai-collab/actions/runs/39420753335/attempts/1)
精确对应上述 SHA：Frontend PASS；Backend FAIL。
唯一测试错误为 `RetrievalCoveragePostgresTest.reset:139` 的 `TRUNCATE ... CASCADE` 死锁，
1543 项、0 failures、1 error、18 skipped。原始 Surefire 报告归档于
[ci-first-attempt-failure.txt](ci-first-attempt-failure.txt)，上游 artifact 为 `backend-surefire-reports` / `12263544039`。
本轮没有后端变更，失败与历史同类，归入测试清理并发稳定性风险；不推断已知产品路径有回归，也不宣称根因已修复。

[同一 SHA、同一 run 的 attempt 2](https://github.com/BangShou1st/ai-collab/actions/runs/39420753335/attempts/2)
仍为 Frontend PASS、Backend FAIL。这次唯一测试错误是
`DocumentRegistrationStatusIntegrationTest.resetDatabase:65`，SQL 为 `TRUNCATE TABLE app_user CASCADE`，
仍为 AccessExclusiveLock / RowExclusiveLock 死锁。1543 项、0 failures、1 error、18 skipped。
**两个 attempt 的失败测试类不同**，只确认均为清理阶段死锁，不推断根因已定位。
第二次原始报告见 [ci-second-attempt-failure.txt](ci-second-attempt-failure.txt)，上游 artifact `12264161224`。

抽屉未知状态补强、相应断言与交付文档在后续提交一并交付。
其精确 SHA 与对应 CI 结论见最终交付消息，不同 SHA 的运行分别记录。

回退功能：先普通 revert 后续抽屉补强提交，再 `git revert 4bf14af`，再正常推送 `dev`。本轮无迁移，
不回滚数据库、不删除 Docker 卷或合成/历史业务数据。未 force push、未硬重置、未合并 main。
