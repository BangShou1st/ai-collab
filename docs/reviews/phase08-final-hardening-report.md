# Phase 08 最终全面收口报告

日期：2026-07-28  
仓库：`E:\ai-collab`  
分支：`feat/phase-08-ai-task-planning`

## 结论

本轮从 RED 重新执行后端、前端、契约、真实 PostgreSQL/Spring、真实服务和 Microsoft Edge
验收。16 项最终门槛均已满足，结论为 **PASS**。

## 根因与修复

1. OpenAPI 曾有重复 path 和 DTO/状态/版本来源冲突。现已合并同一路径方法，Controller
   返回稳定 API DTO，并以契约测试锁定 Java、OpenAPI 和 TypeScript。
2. Validator 曾先产出平面 code，后续无法可靠恢复 target/field。现由生产 Validator
   直接产出带定位、安全详情和 severity 的 `StructuredValidationIssue`。
3. 生成阶段曾把完整 Repair 输出重新当 Detail 解析。现按服务端 issue scope 生成并应用
   Patch；tempKey、title、objective、sortOrder 等字段永久锁定。
4. 局部 Repair 曾缺少 mode、issue 版本归属、配额和并发提交约束。现校验
   issueId/plan/version/target/mode，复用 Redis throttle 和成功配额，远程调用不占长事务，
   提交时再次校验 latest version，并原子写 version/issues/event/status/attempt。
5. 编辑与恢复曾存在两套提交语义。现完整 Draft 保存和恢复统一经
   `TaskPlanVersionCommitService`，历史版本不可变，乐观锁冲突返回 409。
6. 事件曾缺少真实字段和目标。V11 增加 JSONB `changed_targets_json`，安全 DTO 返回
   changedFields/changedTargets/issueCodes，不返回 hash、SQL 或模型原文。
7. 查询权限与命令守卫曾分别硬编码。现统一使用 `TaskPlanActionPolicy`；
   READY_WITH_ISSUES、REPAIRING、CONFIRMING、CONFIRMED 的动作矩阵一致。
8. 前端曾显示英文 enum、内部错误摘要和 tempKey，并缺少真实局部修复/历史限制。
   现状态、版本、优先级、问题、事件和安全错误全部中文，问题名称来自 Draft，
   REPAIRING 持续轮询，历史版本只读且不可确认。
9. Edge RED 发现问题定位只聚焦折叠面板内的隐藏 input。定位动作现在先展开目标所属
   里程碑，再滚动并聚焦；真实组件测试锁定该行为。
10. Edge RED 发现保存成功后 `dirty` 未清除，`refresh()` 因脏状态拒绝重新加载，页面
    停在旧版本。现在成功保存先更新 snapshot，再刷新详情、历史和权限；组件测试验证
    detail/versions 均重新请求。

## 自动化验证

| 范围 | 本轮新鲜命令/证据 | 结果 |
|---|---|---|
| 后端完整测试 | `mvnw.cmd test` | 247 tests，0 failure/error/skipped |
| 后端干净构建 | `mvnw.cmd clean package` | 245 个生产源重新编译，247 tests，JAR 构建成功 |
| Spring/PostgreSQL 目标集 | `TaskPlanSpringBeanPostgresIntegrationTest` | 5 tests，0 失败 |
| 前端类型 | `pnpm typecheck` | 通过 |
| 前端测试 | `pnpm test` | 6 files / 42 tests，0 失败 |
| PlanningView | 真实 `mount(PlanningView)` | 14 tests，0 失败 |
| 前端构建 | `pnpm build` | 1725 modules，构建成功；仅有非阻断 chunk 警告 |
| OpenAPI | `PlanningOpenApiContractTest` | 无重复 path，契约一致 |

真实 Spring 测试自动注入 Orchestrator、Command、Partial Repair、Confirmation、Version
Commit、Repositories 和 ObjectMapper；只替换模型边界。PostgreSQL 17、真实 MinIO
Testcontainer 和 Flyway V1～V11 实际运行。覆盖合法生成、降级、手工解决、scoped
Repair、历史确认冲突、正式任务/依赖落地、event/issue 注入失败回滚和并发编辑一成功一
版本冲突。

事务失败注入证明 event 或 issue insert 抛错时，version、latest pointer、issues、event
和 plan status 全部回滚。

## 全系统真实服务回归

- `/actuator/health`：UP。
- PostgreSQL：Flyway 1～11 均为 `success=true`。
- Redis：认证后的真实 `PING` 返回 `PONG`。
- MinIO：真实 `.txt` 上传返回 202，异步处理进入 `READY`，产生
  `DOCUMENT_UPLOADED` 和 `DOCUMENT_INDEXED` 审计。
- 鉴权/项目/成员：Edge 注册、refresh、me、项目创建、OWNER 权限与成员列表成功。
- 普通文本知识问答：无可用来源时返回安全的“当前项目资料不足”中文文本，不虚构答案。
- 正式任务：create、list、完整 update、dependency replace、delete 均成功；
  update 进入 `IN_PROGRESS`，DELETE 返回 204，审计产生 10 条对应记录。
- 本轮临时 `edge_*` 用户、29 个 Edge 项目和 smoke 项目已精确删除；确认删除保护触发器
  在清理事务后恢复为 enabled。

## 四条规划业务链路与 Edge A～F

Microsoft Edge（Playwright `channel=msedge`）在真实 Vue、Spring Boot、PostgreSQL、
Redis、MinIO 下完成：

1. 合法生成：创建返回 202，观察
   `SKELETON_GENERATING → DETAIL_GENERATING → READY`，两个完整任务可见。
2. 可编辑降级：确定性本地模型产生依赖日期冲突，生成 Repair 仍无效，最终
   `READY_WITH_ISSUES`；显示中文具体问题和完整 Draft，确认入口不存在。
3. 用户编辑：点击问题会展开并定位 `t2.startDate`；保存产生 `MANUAL_EDIT` 新版本，
   冲突 issue 消失、状态 READY、时间线显示中文“开始日期”变化。
4. AI 局部修复：请求进入 REPAIRING 并持续轮询，生成 `AI_PARTIAL_REPAIR`；
   request mode 为 `REPAIR_DATES_AND_DEPENDENCIES`，仅允许 `t2.startDate`，
   t1 与锁定字段逐字段保持不变。
5. 历史版本：切换 v2 后只读且无确认入口；恢复创建 `RESTORED` 新版本和中文事件，
   不覆盖历史。
6. 确认：仅最新 READY 可确认；落地 1 个里程碑、2 个正式任务、1 条依赖；
   使用相同幂等键重复确认仍返回 200/SUCCESS。

脱敏 Network 关键结果：

| Request | Method | Status | response.code / message |
|---|---:|---:|---|
| `/ai/task-plans` | POST | 202 | `SUCCESS` / 操作成功 |
| `/versions` | POST | 200 | `SUCCESS` / 操作成功 |
| `/versions/{id}/restore` | POST | 200 | `SUCCESS` / 操作成功 |
| `/partial-regenerate` | POST | 202 | `SUCCESS` / 操作成功 |
| `/confirm`（首次） | POST | 200 | `SUCCESS` / 操作成功 |
| `/confirm`（相同幂等键） | POST | 200 | `SUCCESS` / 操作成功 |
| `/tasks` | GET | 200 | `SUCCESS` / 操作成功 |

响应未提供 requestId 时记录为 null；证据中未保存 Authorization 或 Cookie。首次匿名
refresh 的 401 是注册页建立会话前的预期行为；favicon 404 不影响应用业务。

中文最终界面截图：[phase08-edge-final.png](phase08-edge-final.png)

## 静态扫描

- `parseDetail(repairResult`：无命中。
- `new StructuredValidationIssue`：只命中生产 Validator 创建和 Repository 反序列化。
- `lastErrorSummary`：只作为类型/输入传给安全中文映射；组件测试证明不显示内部摘要。
- `TODO`：只命中正式任务业务状态 `TaskStatus.TODO`、迁移约束和确认插入值，不是待办注释。
- `READY_WITH_ISSUES`、`REPAIRING`、`AI_PARTIAL*`：Java、OpenAPI、TypeScript、中文映射
  和迁移中的一致合法定义。
- `git diff --check`：通过。

## 提交

```text
a1f7d97 docs: record Phase 08 hardening baseline
9ab3ea6 fix(api): freeze final planning contract
c201f8f fix(planning): produce located validation issues from domain validator
178fccc fix(planning): complete scoped generation repair pipeline
f9a3cd6 fix(planning): harden scoped partial repair and transaction boundaries
a695cf9 fix(planning): unify editable versions and audit events
51d6592 fix(planning): align confirmation and permissions across states
1ee8143 fix(frontend): complete Chinese editable planning workflow
4da32a9 test(planning): verify final production workflows with postgres
76c012b fix(frontend): refresh saved plans and expand issue targets
```

最终证据提交见本文件所在提交。

## 16 项最终门槛

1. 后端 test 与 clean package：满足。
2. 前端 typecheck/test/build：满足。
3. 真实 Spring + PostgreSQL：满足。
4. 生成 Repair 使用 scoped Patch：满足。
5. 生产 Validator 产生定位 issue：满足。
6. 局部 Repair 权限、issueIds、mode、配额、事务：满足。
7. 编辑契约统一：满足。
8. 安全事件 DTO 与字段变化：满足。
9. 历史版本不能确认：满足。
10. REPAIRING 完整工作：满足。
11. 规划页面完整中文：满足。
12. PlanningView 真实组件测试：满足。
13. Edge A～F：满足。
14. 全系统回归：满足。
15. 生产改动全部提交且工作区干净：最终提交后验证。
16. 仅 `E:\ai-collab` 一个工作目录：满足。

遗留风险：无已知阻断风险。生产接入真实外部模型时，响应质量、配额和网络延迟仍属于需
持续监控的外部运行风险；前端产物仍有现有的大 chunk 非阻断警告。
