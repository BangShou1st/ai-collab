# `prompts/`：PromptPack 资产（唯一来源）

```text
prompts/
  manifest.json                       # key/version/sha256/chars/variables（CLI 生成，禁止手写）
  <key>/<version>.md                  # 模板正文，如 knowledge.system/2026-11-11.1.md
```

- **只加不删**：已发布的 `<key>/<version>.md` 不得原地修改；改文案即发新版本号
  （`YYYY-MM-DD.N`），Java 侧按 key 预取所需版本（设计 §7.2）。
- **manifest 只能由 CLI 重建**，不要手写 `sha256`/`chars`/`variables`：

```bash
cd ai-collab-ai-worker
uv run ai-prompts check    # 逐字段比对（默认），不一致返回 1
uv run ai-prompts write    # 按模板重建 manifest.json
```

- 文件必须是 UTF-8 无 BOM、LF 换行、以且仅以一个换行结尾；正文 = 文件内容去掉末尾换行。
- `chars` 是正文的 Unicode 码点数量，`sha256` 是正文 UTF-8 字节的 SHA-256（十六进制小写）。
- 占位符 `{{ name }}` 的集合必须**恰好等于** manifest 的 `variables`（排序去重）；
  多一个、少一个、顺序不对都是硬错误，`pytest` 与 `GET /v1/prompts/{key}/{version}` 都会失败。
- 服务端只暴露 manifest 的四个字段（`key/version/sha256/chars`）；`variables` 仅用于自检与
  单条文档响应。
- `README.md` 位于根部，不匹配 `<key>/<version>.md`，不会被当成模板；模板子目录里也不要放
  非模板的 `.md`。
