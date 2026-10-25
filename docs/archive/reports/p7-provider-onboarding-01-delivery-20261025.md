# P7-PROVIDER-ONBOARDING-01 交付报告：AI 模型接入自动化与能力发现改造

日期：2026-10-25 · 仓库：`BangShou1st/ai-collab` · 分支：`dev`
证据目录：[docs/acceptance-evidence/2026-10-25/p7-provider-onboarding-01/](../../acceptance-evidence/2026-10-25/p7-provider-onboarding-01/README.md)
设计文档：[docs/design/provider-onboarding-and-capability-detection.md](../../design/provider-onboarding-and-capability-detection.md)

## A. 改造前真实问题与根因

1. **I-3（P7-BUSINESS-E2E-01 遗留，本轮最高优先级）**：模型实际支持原生 Tool Calling，但用户创建 Provider 时未手动勾选 `NATIVE_TOOLS`，UI 显示"不支持"。根因链：`RoutingModelTurnGateway.turnWithConfig` 在声明门控处提前抛出（无真实请求）→ `AiCapabilityProbeService.classify` 把该消息启发式归为 `NOT_SUPPORTED`。流式探针同理（`RoutingChatModelGateway` 的 `ModelCapabilityPolicy.require`）。
2. **URL/Path 重复前缀**：Base URL 带 `/v1` + 默认 API Path `/v1/chat/completions` → 模型发现请求拼成 `/v1/v1/models`，无组合检查。
3. **接入摩擦**：模型发现必须先保存 Provider；表单把 NATIVE_TOOLS 等内部枚举直接暴露为必经勾选项。

## B. 新旧接入流程对比

| 步骤 | 旧流程 | 新流程 |
| --- | --- | --- |
| 服务商 | 手选协议类型，自己拼端点 | 档案下拉（OpenAI 兼容 / 魔搭 ModelScope / Anthropic / Gemini），端点自动预填 |
| 模型 | 必须先保存才能发现；手输为主 | 输入 Key 即可一键发现（未保存发现）；失败降级手输并显示分类错误 |
| 能力 | 用户必须理解 NATIVE_TOOLS 等枚举并手动勾选 | 勾选收进高级设置且仅表示"允许尝试"；「检测并配置」由真实探针确定能力 |
| 验证 | 分步手动测试，未声明即报"不支持" | 一键「检测并配置」：确认预算 → 串行真实探针 → PASS 能力 CAS 写回 → 状态表分层展示 |
| 地址错误 | 发请求后才失败 | 保存/发现前即警告 + 一键修正 + 端点预览（0 次请求） |

## C. 核心方案

- **探针专用执行边界** `CapabilityProbeContext`（服务端 ThreadLocal，仅探针服务设置，按 id+userId 匹配）：仅豁免能力声明门控，enabled/凭据/SSRF/结构校验/审批全部不变；生产路径零影响。详见设计文档第 3 节。
- **结果语义**：落库四态不变，展示层派生七态（已验证可用/未测试/已声明未验证/需重测/真实失败/暂时无法判断/已确认不支持/未启用未声明）。"未勾选"不再显示为"不支持"。
- **检测并配置**：`POST /{id}/detect-and-configure`，后端串行编排 + JVM 内 per-provider 互斥 + 版本绑定 + CAS 写回（`UPDATE ... WHERE updated_at=? RETURNING updated_at`）+ `rebaseProbeTestedVersion` 版本锚点迁移。规划 PASS 不启用 STRUCTURED_OUTPUT（生产规划走 PROMPT_JSON，机械映射会造成假声明）。预置连接不参与写回。
- **URL/Path 规则**：仅识别"Base URL 末段 == API Path 首段且为版本段"的拼接重复；前端镜像同口径（`endpoint-rules.ts`），后端保存/发现前硬拒绝；Gemini `/v1beta`、Anthropic `/v1`、自定义网关特殊路径不误伤。
- **跨协议工具探针**：合成工具 schema 弃用 Gemini OpenAPI 子集不支持的 `const`/`additionalProperties`（真实验收发现并修复），参数值正确性由响应侧校验兜底。

## D. T01–T10 逐项结果

见 [验收证据 README 第 2 节](../../acceptance-evidence/2026-10-25/p7-provider-onboarding-01/README.md)。要点：

- **T01（核心）**：三层证据——单元（探针边界三情形）、服务层（未声明→真实合成请求→PASS→自动启用）、真实浏览器+真实 Gemini（创建时未声明 NATIVE_TOOLS → 工具探针 12924ms 真实 PASS → `capabilities` 自动变为 `CHAT,NATIVE_TOOLS,STREAMING` → 用途 Agent 显示可用 → 刷新持久）。
- **T02/T03**：真实 401→FAIL（不写回、后续 SKIPPED）；真实 503/400→INCONCLUSIVE（不误报不支持、无界重试不存在，每步恰好一次）。
- **T05/T06/T07**：真实 PostgreSQL CAS 集成测试（版本推进拒绝写回、合并只增不减、rebase 精确到模式）；浏览器实测检测中改模型→旧结论即时过期。
- **T04**：浏览器实测警告+一键修正生效；后端日志记录出站前拦截。
- **T09**：ModelScope（真实发现 37 模型、真实 401 FAIL）、Gemini（真实发现 50 模型、三探针真实 PASS）；Anthropic 协议行为由规则/档案测试覆盖；Zen/Kilo 共用同一编排（前端测试）。真实付费调用未执行（分层结论，不扩大声称范围）。
- **T10**：权限/SSRF/凭据既有测试不回退；探针上下文不可被客户端伪造；全部日志与证据无凭据。

## E. 实际模型请求消耗

见验收证据第 3 节。真实生成请求合计约 10 次（ModelScope 1 + Gemini 9），模型发现只读请求 4 次。用户提供的 Gemini Key 仅经表单与后端出站使用，未落任何日志/证据。**未新建 Provider 后静默消耗额度**：每轮检测均经确认框明示预算后单次授权。

## F. 测试与验证

- 后端定向：新增/更新 7 个测试类共 55 项（端点规则 5、端点校验 9、探针边界 5、检测并配置 7、探针结果 7、规划契约 4、发现服务 8、CAS 集成 3 等）全绿。
- 后端完整 `mvnw verify`：见 G 节 CI 回执（Windows 本地增量编译产物损坏曾导致 12 项假失败，删除 `target/classes` 后干净重建验证，见下）。
- 前端四件套：409 测试全绿、lint 0 errors（新增模块 0 警告）、typecheck/build 通过。
- 真实 PostgreSQL 集成：CAS/rebase/版本竞争 3 项全绿。

## G. Git 与 CI

- 开工 SHA：`dev=4c98633eda2d89148ec9d70268d9baf443c30466`、`main=9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`；PR #18 保持 Draft 未合并；`main` 无改动。
- 最终 SHA 与 CI：见文末补记。

## H. 真实浏览器验收

见验收证据 README 第 1、4 节。覆盖：新建 Provider（简化表单）、服务商档案预填、重复前缀警告与修正、模型发现与选择、一键检测并配置（预算确认）、能力自动写回、刷新持久、修改模型后旧结果过期、Agent Tool Calling 不再假报"不支持"。使用账号：本机既有测试账号（协作用户），测试 Provider 验收后清理（保留 1 个真实 Gemini 配置与 1 个演示配置）。

## I. 未执行项与残余风险

1. **真实付费调用未执行**：OpenAI/Anthropic 真实生成未获授权，分层结论不声称其"真实已验证"；协议行为由受控测试与档案测试覆盖。
2. **检测锁为 JVM 内实现**：当前单体部署正确；跨实例部署需演进为 DB/Redis 锁（设计文档已登记）。
3. **browser-skill 截图通道间歇超时**：验收后半程无法截图，T01 终态以数据库脱敏记录为准（非 Mock）。
4. **生产门禁不变**：`PRODUCTION_DEPLOYMENT_NO_GO` 维持，本轮未改变任何发布裁决。
5. **dev.ps1 临时代理改动已还原**：本地 Gemini 验收需本机代理（`AI_COLLAB_JVM_EXTRA` 注入点为验收临时加，提交前已还原，不进入仓库）。

## J. 最终裁决

**`P7_PROVIDER_ONBOARDING_01_ACCEPTED`**（待最终 SHA CI 回执确认后生效）——裁决条件 1–7 已达成（见 D/F/H 节），条件 8 于 CI 补记后满足。

## 补记（提交后填写）

- 代码+证据提交 SHA：待填写
- CI run：待填写
