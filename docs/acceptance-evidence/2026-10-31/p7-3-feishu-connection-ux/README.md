# P7.3 飞书连接体验优化 — 本地浏览器验收证据

- 日期：2026-10-31
- 环境：Windows 本地（`dev.ps1 start`，后端 local profile @ :8080，前端 Vite @ :5173，提交 `1860b3a` 构建）
- 工具：browser-skill（bsk，session `obzo`，Agent Window 标签页 702823319）

## 证据清单

| 文件 | 内容 | 断言 |
| --- | --- | --- |
| `create-dialog-wiki-error.png` | 「添加飞书文件夹知识源」对话框，文件夹链接输入 `https://myteam.feishu.cn/wiki/Xy9abcDEF123` | 展示两种授权身份说明；Wiki 链接被拒绝并显示「这不是飞书文件夹链接……Wiki、文档、表格链接不受支持」的红色分类错误 |
| `staging-new-ui-and-url-parse.png` | Staging（`staging-0e35b2e` + 同提交前端构建）同一对话框，输入 `https://p73staging.feishu.cn/drive/folder/fldcnStageTest99` | Staging 前端为 P7.3 新版：出现「本项目可用连接」管理区（AI-Collab 知识库连接器 · 系统级共享 · 由系统管理员管理）；URL 自动识别「已识别文件夹 Token：fldcnStageTest99」；回调提示为 Staging 受控 HTTPS 域名 |
| `staging-dialog-style-fixed.png` | Staging（`1edefae` 样式修复后重建前端）同一对话框 | 长 URL 正确断行，连接名称/App ID/Secret 输入框与确认复选框均回到对话框边界内；「我的飞书授权」「本项目可用连接」「飞书应用连接（系统管理员）」三个区域渲染正常 |
| `checkbox-wrap-fixed.png` | 本地（`88f375e`）对话框底部 | 授权确认复选框文本正确换行为两行，完全在对话框边界内；URL 解析提示正常 |

## 同会话已验证的交互（bsk step 观察记录，无截图留存）

1. 输入 `https://myteam.feishu.cn/drive/folder/fldcnTestToken123` 后，表单出现「已识别文件夹 Token：fldcnTestToken123」提示（VOM 文本 `* 文件夹链接 已识别文件夹 Token：fld…`）。
2. 输入 Wiki 链接后提示切换为分类错误文案，且提交按钮保持禁用。
3. 对话框初始状态：连接下拉（暂无可用连接 + 新建本项目专属连接表单）、授权身份选择、「授权我的飞书账号 / 刷新授权状态」、回调地址白名单提示 `http://localhost:5173/api/v1/connector/oauth/callback`。

## 阻断项（BLOCKED_EXTERNAL）

本地未配置 `FEISHU_OAUTH_REDIRECT_URI`（受控 HTTPS 回调域名），真实飞书 OAuth、事件验证与真实同步未在本地执行；对应逻辑由后端/前端自动化测试覆盖。Staging 新版本部署未执行（需用户明确授权），仅完成只读检查，见验收报告第 11 节。

## Staging 部署后补充（2026-10-31 晚，已获用户授权）

- Staging 已部署 `ai-collab-backend:staging-0e35b2e` 并重建前端 dist（同提交构建），`box-health.sh` healthy，Flyway V73。
- API 验收（专用测试账号 `p73owner10311851` / `p73member10311851`，测试项目 A/B）：伪造连接的 OAuth start / 知识源创建 / 事件状态全部 404；普通成员创建连接、事件状态、添加知识源全部 403 `PROJECT_ADMIN_REQUIRED`；非成员视角一律 `PROJECT_NOT_FOUND`。
- 真实 SYSTEM 连接上的 OAuth start 返回飞书授权 URL（仅创建 5 分钟过期的 flow，未点击授权、未产生授权记录、未触碰现有真实授权）。
- 现有真实连接、授权、知识源、文档行数与内容在部署前后一致；页面显示正常。
- 样式修复（`1edefae`）：用户反馈对话框内长回调 URL 未断行导致嵌套表单溢出；为提示文本/code 增加 `word-break` 并约束表单项与对话框宽度，本地与 Staging 均已验证。
- 样式修复二（`88f375e`）：用户复验发现确认复选框仍溢出——根因是 Element Plus `el-checkbox` 默认 `white-space: nowrap`；覆盖为可换行布局（`align-items: flex-start` + label `word-break`），本地浏览器验证通过（`checkbox-wrap-fixed.png`），Staging 前端已随 `88f375e` 重建。
