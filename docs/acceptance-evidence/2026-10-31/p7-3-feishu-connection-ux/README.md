# P7.3 飞书连接体验优化 — 本地浏览器验收证据

- 日期：2026-10-31
- 环境：Windows 本地（`dev.ps1 start`，后端 local profile @ :8080，前端 Vite @ :5173，提交 `1860b3a` 构建）
- 工具：browser-skill（bsk，session `obzo`，Agent Window 标签页 702823319）

## 证据清单

| 文件 | 内容 | 断言 |
| --- | --- | --- |
| `create-dialog-wiki-error.png` | 「添加飞书文件夹知识源」对话框，文件夹链接输入 `https://myteam.feishu.cn/wiki/Xy9abcDEF123` | 展示两种授权身份说明；Wiki 链接被拒绝并显示「这不是飞书文件夹链接……Wiki、文档、表格链接不受支持」的红色分类错误 |

## 同会话已验证的交互（bsk step 观察记录，无截图留存）

1. 输入 `https://myteam.feishu.cn/drive/folder/fldcnTestToken123` 后，表单出现「已识别文件夹 Token：fldcnTestToken123」提示（VOM 文本 `* 文件夹链接 已识别文件夹 Token：fld…`）。
2. 输入 Wiki 链接后提示切换为分类错误文案，且提交按钮保持禁用。
3. 对话框初始状态：连接下拉（暂无可用连接 + 新建本项目专属连接表单）、授权身份选择、「授权我的飞书账号 / 刷新授权状态」、回调地址白名单提示 `http://localhost:5173/api/v1/connector/oauth/callback`。

## 阻断项（BLOCKED_EXTERNAL）

本地未配置 `FEISHU_OAUTH_REDIRECT_URI`（受控 HTTPS 回调域名），真实飞书 OAuth、事件验证与真实同步未在本地执行；对应逻辑由后端/前端自动化测试覆盖。Staging 新版本部署未执行（需用户明确授权），仅完成只读检查，见验收报告第 11 节。
