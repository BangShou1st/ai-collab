# PromptPack golden 夹具（J2 同文本一致性的裁决依据）

本目录是 **改动前** Java 实现的 Prompt 出站正文快照，由一次性取证工具在
`dev @ 7c52d961`（P0 交付后、J2 开工前）从**旧代码实际调用**中导出，
不是手工抄写，也不是从 PromptPack 反推。

## 生成方式（可复现）

在 J2 开工前的工作树上新增一个临时 JUnit 用例 `PromptGoldenDumpTest`，
它构造真实的 `KnowledgePromptProvider` 与六个 `builtin/*Scenario` 实例，把下列内容
按「UTF-8 无 BOM、LF、正文 + 一个换行」写入 `ai-collab-backend/target/prompt-golden/`：

| 调用 | 导出的文件 |
| --- | --- |
| `provider.systemPrompt()` 去除动态时间前缀（锚点 `你是 AI Collab 的项目知识库问答助手。`）后的正文 | `knowledge.system.body.txt` |
| 同上，被去除的前缀本身 | `knowledge.system.full-prefix.txt` |
| `provider.buildUserPayload(问题, 证据, "")` | `knowledge.user.no-context.txt` |
| `provider.buildUserPayload(问题, 证据, 会话上下文)` | `knowledge.user.with-context.txt` |
| `provider.buildUserPayload(含 XML 元字符的问题/证据, 含 & 的上下文)` | `knowledge.user.xml-escape.txt` |
| 六个 Scenario 的 `instruction()` / `outputContract()` | `scenario.<code>.{instruction,output_contract}.txt` |

导出后该临时用例被删除；本目录即为其输出。取证结论与映射表见
[`docs/archive/handoff/j2-prompt-pack-freeze-20261111.md`](../../../../../docs/archive/handoffs/j2-prompt-pack-freeze-20261111.md)。

## 为什么不能用"新路径自己生成"的夹具

用新实现生成夹具再与新实现比对是同义反复。这里的文件来自**旧实现**，
J2 的一致性用例只做一件事：把 PromptPack 模板按新实现渲染，与这些字节比较。

## 口径

- 每个文件 = 该 Prompt 的**完整正文** + 恰好一个换行；读取时去掉末尾唯一一个换行。
- `knowledge.system.full-prefix.txt` 是唯一例外：它本身是"动态时间前缀"，正文即
  `当前北京时间：yyyy-MM-dd HH:mm（UTC+8），日期计算必须基于此时区。` 后接三个换行
  （`TimeContext.beijingTimeContext()` 自带一个 + 拼接时补两个），因此文件以两个换行结尾。
- 六个 Scenario 的正文自身以换行结尾（Java 文本块保留末尾行终止符），因此对应文件以两个换行结尾。
