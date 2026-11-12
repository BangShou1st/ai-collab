# 阶段 1 阻塞证据：thinking 覆盖前缀不匹配（当前镜像不可用于门禁）

- 时间：2026-11-12T07:57Z
- 状态：**BLOCKED**。中心已暂停 C-1 主测量；未跑 tier 8/20/50 门禁。
- 当前 app 保持运行（未回滚）：镜像 `sha256:aa6b46efe4b6…`，`healthy`，FailingStreak=0。
- worker：镜像 `sha256:265b77c6490c…`，`healthy`。
- 未改任何仓库代码；只使用了仓库外 compose 覆盖文件（`/data/home/w3c/compose.phase1.*.yml`）。

## 1. 根因

`GeminiThinkingProperties` 的注解前缀与 `application.yml` 的配置层级不一致：

- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/model/GeminiThinkingProperties.java:40`
  `@ConfigurationProperties("ai.gemini-thinking")`
- `ai-collab-backend/src/main/resources/application.yml:76-81`
  配置写在**顶层** `gemini.thinking.*`（`gemini:` 与 `ai:` 同级，未嵌套在 `ai:` 下）：
  ```yaml
  gemini:
      thinking:
          enabled: "${GEMINI_THINKING_OVERRIDE_ENABLED:false}"
          budget: "${GEMINI_THINKING_BUDGET:}"
          level: "${GEMINI_THINKING_LEVEL:}"
          models: {}
  ```
- 注册点 `ModelTransportConfiguration.java:19-21` 确实注册了该 `@ConfigurationProperties`，因此 Bean 存在，但绑定不到 `gemini.thinking.enabled`。

后果：`GEMINI_THINKING_OVERRIDE_ENABLED=true` / `GEMINI_THINKING_BUDGET=1` 只落到无人读取的 `gemini.thinking.*`，`GeminiThinkingProperties.enabled` 保持默认 `false`；适配器继续走 `disablesThinking()` → 下发 `thinkingConfig.thinkingBudget=0` → 3.5 返回 400 → `postWithThinkingFallback` 去掉字段重发 → 每次模型调用产生 **2 次 provider 请求（400+200）**。

## 2. 日志计数证据（DEBUG `JsonHttpModelClient`）

| 运行 | 生效 env | request 条数 | response 状态 | 端到端耗时 |
| --- | --- | ---: | --- | ---: |
| override-01（修复前） | `GEMINI_THINKING_*`（无效） | **2** | 400 + 200 | 784.9ms |
| override-02（直接绑定名验证） | `AI_GEMINI_THINKING_ENABLED=true`、`AI_GEMINI_THINKING_BUDGET=1` | **1** | 200 | 650.7ms（网关 latencyMs=585） |
| control-01（去掉覆盖） | 无 thinking env | **2** | 400 + 200 | 1063.5ms |
| control-02 | 无 | **2** | 400 + 200 | 8294.7ms |
| control-03 | 无 | **2** | 400 + 200 | 562.2ms |

原始日志（均在 `/data/home/w3c/raw/phase1/`）：`log-override-01.txt`、`log-override-02.txt`、`log-control-01/02/03.txt`；响应体：`model-call-override-01/02.json`、`model-call-control-01/02/03.json`。

关键片段（override-02，1 次用户请求 = 1 次 provider 请求）：
```text
07:54:50.858 DEBUG ...JsonHttpModelClient - Model API request: model=, stream=false
07:54:51.441 DEBUG ...JsonHttpModelClient - Model API response status: 200
```

关键片段（control-01，fallback 导致 2 次 provider 请求）：
```text
07:55:23.847 DEBUG ... Model API request: model=, stream=false
07:55:24.161 DEBUG ... Model API response status: 400
07:55:24.162 DEBUG ... Model API request: model=, stream=false
07:55:24.845 DEBUG ... Model API response status: 200
```

结论：
- 当前镜像（`aa6b46efe4b6…`）上，文档中的 `GEMINI_THINKING_*` 名称**不生效**，每次调用固定 2 次 provider 请求；
- 用 Spring 直接绑定名 `AI_GEMINI_THINKING_ENABLED` / `AI_GEMINI_THINKING_BUDGET` 可以证明 Bean 链路本身正常（1 次请求/200），但中心已要求在 A 修复前缀前**不得用当前镜像跑门禁**。

## 3. 当前运行态

- app 容器：`ai-collab-app`，`healthy`，FailingStreak 0，8081 UP，Flyway V91。
- app env（当前 with-override，含两套名称）：
  - `JAVA_TOOL_OPTIONS=-Djava.net.useSystemProxies=true -Dlogging.level.com.shitulelv.aicollab.infrastructure.ai.model.JsonHttpModelClient=DEBUG`
  - `GEMINI_THINKING_OVERRIDE_ENABLED=true`、`GEMINI_THINKING_BUDGET=1`（当前不生效）
  - `AI_GEMINI_THINKING_ENABLED=true`、`AI_GEMINI_THINKING_BUDGET=1`（绕过验证用）
- 默认开关未变：`RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`。
- Provider（阶段 1 步骤 3 已执行）：`Gemini-3.5-Flash-Lite`，id `0f58581a-dbba-4e26-bafa-2e137f650563`，`capabilities=["CHAT","STREAMING"]`，`maxOutputTokens=8192`，`/test` 200（1428ms），已 publish（PUBLISHED），purposes `KNOWLEDGE_CHAT/AGENT/PLANNING` 均指向它。
- 磁盘：`/data` 5.2G 可用；未执行任何清理/ prune。

## 4. 下一步

等待中心提供修复后的新镜像或新 tar；在此之前不跑门禁、不改代码、不回滚当前 app。若新镜像构建需要清理，先报告预计回收量再执行。
