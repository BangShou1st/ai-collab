# Zen / Kilo 共享库移除最终门禁

- 日期：2026-11-14（Asia/Shanghai）
- 集成 HEAD：`1bc67d2934471e5e25a80889befa394f65eeb2ef`
- 范围：后端、前端、Flyway V102、部署配置、OpenAPI 与现行文档
- 结论：本地门禁全部 PASS；Docker 镜像构建与容器 healthcheck 由 push 后的 CI image job 验证

## 门禁结果

| 门禁 | Exit | 原始结果 | 日志 |
| --- | ---: | --- | --- |
| 后端 `mvnw.cmd -B -ntp verify` | 0 | `2990 tests, 0 failures, 0 errors, 60 skipped`；`BUILD SUCCESS`，23:34 min | `maven-verify.log`、`maven-verify-meta.txt` |
| 前端 `pnpm lint` | 0 | 0 errors / 69 warnings | `frontend-lint.log` |
| 前端 `pnpm typecheck` | 0 | `vue-tsc -b` 无诊断 | `frontend-typecheck.log` |
| 前端 `pnpm test` | 0 | 68 files / 515 tests passed | `frontend-test.log` |
| 前端 `pnpm build` | 0 | 1908 modules；built in 6.40s | `frontend-build.log` |
| Python `uv run pytest -q` | 0 | 1284 passed / 1 warning | `python-pytest.log` |
| Python `uv run ruff check .` | 0 | All checks passed | `python-ruff.log` |
| Python `uv run mypy --strict src` | 0 | 66 source files，无问题 | `python-mypy.log` |
| `prod-compose-contract` | 0 | 生产拓扑 + registration-off 变体 | `prod-compose-contract*.log` |
| `local-compose-contract` | 0 | 本地一键拓扑 | `local-compose-contract.log` |
| `prod-config-check` | 0 | 容器内 6/6 tests passed | `prod-config-check-tests-container.log` |
| `docker compose config -q` | 0 | 生产与本地均通过 | `*-compose-config-quiet.log` |

Python 第一次运行被当前终端注入的 `SPEC_AGENT_TAVILY_API_KEY` 按 worker 的 P0 密钥隔离断言拒绝；只在子进程环境中移除该变量后重跑，代码与断言未改动。失败原始输出见 `python-pytest-attempt1-envblock.log`。

## 依赖与残留

- `mvnw dependency:tree`：BUILD SUCCESS，依赖树中 `com.sharedlibs`、OpenCode Zen、Kilo Gateway、JitPack 命中为 0（`dependency-tree.log`、`dependency-tree-residual.txt`）。
- 本机 Maven 缓存仍保留旧构建下载的历史 `com/sharedlibs` 文件，但未出现在当前项目依赖图；完整文件名记录在 `m2-sharedlibs-status.txt`。
- 活动应用代码、前端、部署配置与现行文档精确扫描：0 命中（`zero-residue-active.log`、`zero-residue-meta.txt`）。
- 扫描仅保留不可改写的迁移记录：V45、V65 与 V102 共 11 条预设标识命中，均属于历史 schema 或本轮清理 SQL，不能清除（`zero-residue-with-migrations.log`）。

## 复现命令

```powershell
cd ai-collab-backend
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd -B -ntp dependency:tree

cd ..\ai-collab-frontend
pnpm lint
pnpm typecheck
pnpm test
pnpm build

cd ..\ai-collab-ai-worker
Remove-Item Env:SPEC_AGENT_TAVILY_API_KEY -ErrorAction SilentlyContinue
uv run pytest -q
uv run ruff check .
uv run mypy --strict src

cd ..
docker compose --env-file ai-collab-deploy/prod.env.example -f ai-collab-deploy/compose.prod.yml config --format json |
  node ai-collab-deploy/scripts/prod-compose-contract.mjs
docker compose --env-file docs/acceptance-evidence/2026-11-14/remove-zen-kilo/final/local-contract.env `
  -f ai-collab-deploy/compose.local.yml config --format json |
  node ai-collab-deploy/scripts/local-compose-contract.mjs
docker run --rm -v "E:\project\ai-collab:/work" -w /work node:22-alpine `
  sh -c "apk add --no-cache openssl >/dev/null && node --test ai-collab-deploy/scripts/prod-config-check.test.mjs"
```

## 未执行

- `NOT_RUN`：浏览器 E2E。本次设计门禁未要求新增浏览器用例；前端仅执行四项静态/组件/构建门禁。
- `NOT_RUN`（本文生成时）：CI `Image build & container smoke`。该结果在 dev push 后以 workflow dispatch 验证，run URL 记录在最终交付答复。
