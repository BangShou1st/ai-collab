# PR CI 与镜像构建结果（ci-image-build-results.md）

## 一、运行标识

| 项 | 值 |
| --- | --- |
| Workflow Run ID | [40103355487](https://github.com/BangShou1st/ai-collab/actions/runs/40103355487) |
| attempt | 1（一次通过，无重跑） |
| event | `pull_request`（非 push、非 schedule、非条件误判） |
| branch/ref | head `dev`，检出合成 merge ref（SHA `281fdbee998edfc2d2a93d2bbae024e18c2bbc1f`） |
| 最终 conclusion | **success**（2026-10-24T14:44:54Z） |

## 二、三个 Job 逐项结论（全部实际执行，无一 skipped）

| Job | Job ID | 结论 | 耗时 | 关键证据 |
| --- | --- | --- | --- | --- |
| Backend (Maven verify) | 120899851685 | success | ~7 min | `Tests run: 1688, Failures: 0, Errors: 0, Skipped: 33` + `BUILD SUCCESS` + `Container test guard passed for all required classes.` |
| Frontend (pnpm) | 120899851774 | success | ~1 min | 51 个测试文件全通过；lint 0 errors（75 warnings 为既有基线）；typecheck/build 通过 |
| **Image build & container smoke** | 120899851810 | **success（首次真实执行）** | ~2.5 min | 见下节 |

Image Job 此前在 dev push 上一直 skipped；本 run 由 `pull_request` 事件真实触发，`if` 条件命中，步骤全部执行——**不是 scheduled、不是条件错误跳过**。

## 三、镜像构建验收（任务书第七阶段逐项）

### A. 构建成功

- **Dockerfile 真实构建**：`Build backend image` 步骤 success；日志显示完整构建链路——`dependency:go-offline` 拉取依赖（公开 JitPack，无私服凭据）→ 编译 → `jar:3.5.0:jar` → Spring Boot repackage → `BUILD SUCCESS`（Docker 内 Maven 构建自身的 BUILD SUCCESS，非宿主 CI 混淆）。
- **构建上下文正确**：CI 用仓库根为 context、`-f ai-collab-backend/Dockerfile`，与 Compose 的 `context: ..` 一致；`COPY ai-collab-backend/...` 路径全部命中。
- **运行时镜像**：多阶段构建，运行层 `eclipse-temurin:21-jre-alpine` + 非 root `appuser` + `HEALTHCHECK`；导出 manifest `sha256:f8ebc43746897b143b73a35c5f35e4854ec4a08f3f0702d575210a7a1853cbd4`。
- **Maven/Java 版本匹配**：构建镜像 `maven:3.9-eclipse-temurin-21`，运行镜像 JRE 21，与项目 Java 21 要求一致。
- **镜像不携带本地敏感配置**：Dockerfile 无 COPY `.env`/密钥步骤，全部配置运行时经环境变量注入（application.yml `${ENV_VAR:default}`）。

### B. 版本追踪

- 镜像 tag：`ai-collab-backend:rc-281fdbee998e`（merge commit 前 12 位）。
- `org.opencontainers.image.revision` = `281fdbee998edfc2d2a93d2bbae024e18c2bbc1f` = 本次 CI 实际检出的 merge commit；CI 内断言 `[ "$rev" = "$GITHUB_SHA" ]` 通过（退出码 0 才能到后续步骤）。
- revision 与源分支 dev（`9ef7550`）的关系：merge commit 树与 dev 树逐字节一致（`git diff` 为空），因此 label 指向的代码内容与 dev HEAD 等价；merge commit 的父提交链（main `9b4f7c5` + dev `9ef7550`）已在 git-and-pr-audit.md §四记录。
- `org.opencontainers.image.source` label = 仓库 URL。
- **不是只看 tag 带 `rc` 就算通过**：以上断言均来自 CI 日志原文（证据日志已归档）。

### C. 安全边界

- 工作流权限：`permissions: contents: read`（最小）；未使用 `pull_request_target`（PR 代码在本工作流中只被检出构建，无仓库写权限）。
- 冒烟凭据全部为合成值（`smoke-only*`、32 字节假 JWT secret、假 master key），与生产凭据无任何关联，不写入镜像、不落仓库。
- 不推送任何镜像仓库（镜像仅存在于本次 runner，run 结束即丢弃）。
- 构建日志检查：无密钥/凭据输出（已人工复核归档日志）。

## 四、是否修复代码

**无**。Image Job 首次真实执行一次通过，无需任何 Dockerfile / workflow / 应用配置修复，也不存在需要被禁止的"降门禁"操作。
