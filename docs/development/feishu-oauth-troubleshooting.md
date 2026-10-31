# 飞书 OAuth 故障排查

适用范围：项目级飞书知识连接的用户授权（`ConnectorOAuthService` + `FeishuOAuthClient`）。

## 端点配对（必须遵守）

| 用途 | 端点 |
| --- | --- |
| 授权（浏览器跳转） | `https://accounts.feishu.cn/open-apis/authen/v1/authorize` |
| 授权码换 Token / 刷新 | `https://open.feishu.cn/open-apis/authen/v2/oauth/token` |

- 两个端点的 `redirect_uri` 必须逐字符一致，且与飞书开放平台「安全设置 → 重定向 URL」登记一致。
- v2 令牌端点要求 `Content-Type: application/json; charset=utf-8` 的 JSON 请求体，不是 form-urlencoded。
- **禁止改用 `oauth/v3/token`**：它是 app/tenant_access_token 的 RFC-6749 端点，未接入 PKCE，PKCE 授权码会被拒绝（20049）。

## 常见错误码

| 码 | 含义 | 处理 |
| --- | --- | --- |
| 20049 | PKCE 校验失败 | 先确认令牌端点是 v2 而非 v3；再检查 `code_verifier` 是否发送 |
| 20003/20004/20065 | 授权码无效/过期/已用 | 授权码 5 分钟有效且单次使用；让用户重新发起授权，不得重放 |
| 20026/20037 | refresh_token 无效/过期 | 重新授权；注意 v2 只接受 v2 下发的 refresh_token |
| 20064/20073 | refresh_token 已撤销/已使用 | 重新授权；轮换后的新 refresh_token 必须落库 |
| 20071 | redirect_uri 与授权时不一致 | 检查 `FEISHU_OAUTH_REDIRECT_URI` 与授权 URL 中的值 |
| 20072/429 | 服务暂不可用/限频 | 指数退避重试；结果不确定时不得当作成功 |
| 20074 | 应用未开启刷新开关 | 飞书后台「安全设置」开启刷新 user_access_token，发版生效 |

## 排查步骤

1. `.\dev.ps1 doctor`（本地）：确认 `FEISHU_OAUTH_REDIRECT_URI` 格式为 `https://<host>/api/v1/connector/oauth/callback`。
2. 后端日志搜索 `飞书 OAuth 端点拒绝`：WARN 只记录 `status/feishuCode/msg`，不含凭据；按上表定位。
3. 确认连接的事件订阅凭据 `verified=true`（未配置撤权事件会拒绝发起授权）。
4. 本地真实 OAuth 需要受控 HTTPS 回调域名（应用侧强制 HTTPS）；仅本地 Mock 测试不需要该配置。

协议细节以飞书官方《获取 user_access_token》《刷新 user_access_token》文档为准；P7.2 根因分析见
[docs/archive/report/P7.2-FEISHU-OAUTH-DUAL-ENV-ACCEPTANCE.md](../archive/report/P7.2-FEISHU-OAUTH-DUAL-ENV-ACCEPTANCE.md)。
