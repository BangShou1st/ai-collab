# F1 复现命令

工作目录：`E:\project\ai-collab-wt\f1-decide-dataset\ai-collab-ai-worker`

```powershell
uv sync --frozen

uv run python tests/fixtures/decide_set/candidates/2026-11-14-local-hardening/tools/build_candidates_20261114.py --check

uv run python tests/fixtures/decide_set/candidates/2026-11-14-local-hardening/tools/validate_candidates_20261114.py --json

uv run pytest -q tests/unit/test_decide_candidates_20261114.py

Remove-Item Env:SPEC_AGENT_TAVILY_API_KEY -ErrorAction SilentlyContinue
uv run pytest -q `
  tests/unit/test_decide_eval.py `
  tests/unit/test_decide_parser.py `
  tests/unit/test_decide_candidates_20261114.py `
  tests/integration/test_decide_eval_cli.py `
  tests/integration/test_decide_api.py `
  tests/contract/test_agent_decide_wire_contract.py

Remove-Item Env:SPEC_AGENT_TAVILY_API_KEY -ErrorAction SilentlyContinue
uv run pytest -q

uv run ruff check .
uv run ruff format --check .
uv run mypy src
```

`SPEC_AGENT_TAVILY_API_KEY` 是宿主机环境带入的 Provider 密钥变量；worker 的 P0 环境守卫会
在 pytest collection 阶段拒绝它。该变量只在测试进程内移除，未写入任何仓库文件。
