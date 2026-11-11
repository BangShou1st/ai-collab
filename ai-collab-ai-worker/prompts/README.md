# `prompts/`：PromptPack 资产（唯一来源）

```text
prompts/
  manifest.json                       # key/version/sha256/chars/variables（CLI 生成，禁止手写）
  <key>/<version>.md                  # 模板正文，如 knowledge.system/2026-11-11.2.md
```

- **只加不删**：已发布的 `<key>/<version>.md` 不得原地修改；改文案即发新版本号
  （`YYYY-MM-DD.N`），Java 侧按 key 预取所需版本（设计 §7.2）。
- **manifest 只能由 CLI 重建**，不要手写 `sha256`/`chars`/`variables`：

```bash
cd ai-collab-ai-worker
uv run ai-prompts check    # 逐字段比对（默认），不一致返回 1
uv run ai-prompts write    # 按模板重建 manifest.json
uv run ai-prompts export-java        # 生成/覆盖 Java 侧只读 fallback 包
uv run ai-prompts export-java --check  # 逐字节比对 Java fallback 包，不一致返回 1
```

- 文件必须是 UTF-8 无 BOM、LF 换行、以换行结尾；正文 = 文件内容去掉末尾**唯一一个**换行。
  正文自身允许以换行结尾（文件则以两个换行结尾）——六个 Scenario 的正文（Java 文本块）就是
  这种形状；文件末尾多出的换行属于正文，不会被静默裁掉。
- `chars` 是正文的 Unicode 码点数量，`sha256` 是正文 UTF-8 字节的 SHA-256（十六进制小写）。
- 占位符 `{{ name }}` 的集合必须**恰好等于** manifest 的 `variables`（排序去重）；
  多一个、少一个、顺序不对都是硬错误，`pytest` 与 `GET /v1/prompts/{key}/{version}` 都会失败。
- 服务端只暴露 manifest 的四个字段（`key/version/sha256/chars`）；`variables` 仅用于自检与
  单条文档响应。
- `README.md` 位于根部，不匹配 `<key>/<version>.md`，不会被当成模板；模板子目录里也不要放
  非模板的 `.md`。

## Java 侧只读 fallback 包（`export-java` 的产物）

`prompt.registry.enabled=false`（默认）时 Java 从 classpath 读这份包，因此它只是本目录的
**逐字节副本**，由 CLI 生成、禁止手改：

```text
ai-collab-backend/src/main/resources/promptpack/
  manifest.json               # == prompts/manifest.json（字节相同，不重新序列化）
  texts/<key>/<version>.md    # == prompts/<key>/<version>.md（字节相同）
```

- `--target-dir` 覆盖目标目录；写入前会删除目标目录里 manifest 未登记的多余文件（含历史版本）。
- `--check` 先校验本目录（manifest ↔ 模板），再逐文件比对目标目录：缺失/多余/字节不同都返回 1，
  差异只打印相对路径与 sha256 前 12 位，**不打印 Prompt 正文**。
- 两道守护同时存在：本 CLI 的 `--check` 与 `tests/contract/test_java_promptpack_sync.py`。
