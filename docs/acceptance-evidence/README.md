# 验收证据目录规范

本目录只保存可审计的文本与结构化证据：

- `README.md` / `report.md`：验收结论、命令、断言与环境
- `*.json` / `*.jsonl` / `*.txt` / `*.log`：脱敏后的原始回执或摘要
- 截图、录屏、浏览器 HAR 等二进制文件：**不进入 Git**

截图/录屏统一归档到仓库外受控目录：

```text
E:\project\_archive\ai-collab-archive\<YYYY-MM-DD>\<task-id>\screenshots\
```

报告中登记归档相对路径、文件清单和 SHA-256。CI 会拒绝 `docs/acceptance-evidence`
与 `docs/archive/audits/shots` 下新增图片或视频文件。
