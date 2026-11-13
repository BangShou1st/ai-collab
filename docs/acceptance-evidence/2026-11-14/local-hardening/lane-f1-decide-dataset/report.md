# F1 decide 候选扩集：执行证据

## 结论口径

- `IMPLEMENTED`：新增 80 条 `PENDING_REVIEW` 候选、确定性生成器、候选校验器和定向单测。
- `TESTED(UNIT)`：候选 schema、请求契约、建议标签、adversarial probe、历史哈希与生成器复现。
- `TESTED(INTEGRATION)`：decide 定向集 103 项、Python 全量 1284 项，退出码均为 0。
- `NOT_RUN`：模型生成（本候选集为确定性编写，不需要模型）；运行时失败注入（F1 无服务链路）；
  冻结/质量门禁（必须先经中心复核，不在本 lane 声称）。

本报告不把候选称为冻结集，不声明质量结论。

## 产物

| 产物 | 路径 |
| --- | --- |
| 候选说明 | `ai-collab-ai-worker/tests/fixtures/decide_set/candidates/2026-11-14-local-hardening/README.md` |
| golden 候选 | `.../golden.v1.jsonl` |
| adversarial probe | `.../adversarial.v1.jsonl` |
| manifest | `.../manifest.json` |
| 生成器 | `.../tools/build_candidates_20261114.py` |
| 校验器 | `.../tools/validate_candidates_20261114.py` |
| 定向测试 | `ai-collab-ai-worker/tests/unit/test_decide_candidates_20261114.py` |

## 数量与状态

| 项 | 值 |
| --- | ---: |
| 候选总数 | 80 |
| golden | 64 |
| adversarial probe | 16 |
| `reviewStatus=PENDING_REVIEW` | 80 / 80 |
| `review.reviewer=null` | 80 / 80 |
| `CALL_TOOL` 建议 | 42 |
| `FINAL` 建议 | 8 |
| `ASK_USER` 建议 | 6 |
| `NO_OP` 建议 | 8 |
| locale | 9 |
| 场景 | 15 |

工具覆盖：`search_project_knowledge` 16、`get_document_metadata` 12、
`get_project_snapshot` 10、`create_task` 4（仅 `allowWrite=true`）。另有 22 条非工具决策。

来源覆盖：`FROZEN_V1_VARIANT` 39、`SYNTHETIC_COVERAGE_GAP` 30、
`DESIGN_REFERENCE` 10、`LIVE_ROUND5_REFERENCE` 1。每条来源都带可解析引用与 rationale。

## 历史集未变

以下 raw SHA-256 由校验器和测试双重比对：

| 文件 | SHA-256 |
| --- | --- |
| `decide_baseline.v1.jsonl` | `144439dcb0998cceef549c13bb87720ebe0d5fa16072649f2738444a00a91413` |
| `decide_baseline.v2.jsonl` | `6b74c9325964d9a0c5a5f18e995da73483683fbb89b8ff1f683972909998fd2b` |
| `decide_baseline.v3.jsonl` | `68a66d8a42a2171e3ab8a88e0b93fd77cf621921167b3ef569419fee20b0dd5a` |
| `decide_real_gemini31.v1.jsonl` | `ec35ac978446b898b87c6e1566efe9799f4132c76126845e8292e6ed66fec0ba` |

## 原始输出

| 命令 | 退出码 | 原始文件 |
| --- | ---: | --- |
| 生成器 `--check` | 0 | `raw/build-check.txt` |
| 候选校验器 `--json` | 0 | `raw/validate-candidates.txt`、`raw/candidate-summary.json` |
| 候选定向 pytest | 0 | `raw/pytest-targeted.txt` |
| decide 定向 pytest（6 个文件） | 0 | `raw/pytest-decide-targeted.txt` |
| Python 全量 pytest | 0 | `raw/pytest-full.txt` |
| `ruff check`（候选/测试） | 0 | `raw/ruff-check.txt` |
| `ruff format --check`（候选/测试） | 0 | `raw/ruff-format-check.txt` |
| 全 worker `ruff check` | 0 | `raw/ruff-full-check.txt` |
| 全 worker `ruff format --check` | 0 | `raw/ruff-full-format-check.txt` |
| `mypy src` | 0 | `raw/mypy.txt` |

全量 pytest 第一次收集失败的原因是宿主机环境变量
`SPEC_AGENT_TAVILY_API_KEY` 命中 worker 的 P0 禁入后缀 `_API_KEY`；未改代码，只在该测试
进程取消该环境变量后重跑。失败原始输出保留在
`raw/pytest-full-attempt1-env-guard.txt`；decide 定向测试的同类第一次输出保留在
`raw/pytest-decide-targeted-attempt1-env-guard.txt`。

## 复核入口

1. 先读候选目录 `README.md`，逐项检查人工复核清单。
2. 运行 `build_candidates_20261114.py --check` 与 `validate_candidates_20261114.py --json`。
3. 由 reviewer 填写 `review.reviewer/reviewedAt/notes`；在此之前全部保持 `PENDING_REVIEW`。
4. 若批准进入冻结流程，另建新版本文件、补全真实标签/模型输出并单独审批；禁止改写 v1/v2/v3。

## 当前缺口

- 建议标签全部未人工复核，不能用于一致率、质量门禁或默认模式切换。
- adversarial probe 只证明候选输出会命中现有解析器的预期拒绝原因，不证明真实模型会稳定产出这些错误。
- 本 lane 未调用 Magpie/Gemini，也未产生真实模型采样。
