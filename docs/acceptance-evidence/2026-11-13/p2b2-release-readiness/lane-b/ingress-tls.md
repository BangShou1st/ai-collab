# Lane B ingress/TLS 复核证据

> 日期：2026-11-13
> 环境：Windows + Docker Desktop；Nginx `1.30.6-alpine`
> 性质：本地同配置验证，不是生产 canary

## 1. 命令与结果

### 1.1 Nginx 语法

```powershell
docker run --rm `
  -v "E:\project\ai-collab\ai-collab-deploy\nginx\production.conf:/etc/nginx/conf.d/default.conf:ro" `
  -v "E:\project\ai-collab\target\p2b2-lane-b-ops\tls\cert.pem:/etc/nginx/tls/cert.pem:ro" `
  -v "E:\project\ai-collab\target\p2b2-lane-b-ops\tls\key.pem:/etc/nginx/tls/key.pem:ro" `
  nginx:1.30-alpine nginx -t
```

结果：

```text
nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
nginx: configuration file /etc/nginx/nginx.conf test is successful
```

证据等级：`REAL_PROCESS`（容器内 Nginx 解析同一份生产配置）。

### 1.2 实际路由

启动同一份配置并挂载临时 SPA index：

```text
GET /actuator/health        -> 404
GET /internal/ai/v1/mcp     -> 404
```

判定：

- `/internal/ai/v1/mcp` **没有被 proxy_pass 到 app**，公网不能调用内部端点；
- 已增加 `location ^~ /internal/ { return 404; }`，实测不再命中 SPA fallback；
- 本地 Nginx 容器对 `/actuator/health` 与 `/internal/ai/v1/mcp` 均返回 404。

证据等级：`REAL_PROCESS`。

### 1.3 TLS 协议

```text
curl --tlsv1.2 --tls-max 1.2 -> 200
curl --tlsv1.3               -> 200
curl --tlsv1.1 --tls-max 1.1 -> 000 / exit 35
```

结论：TLS 1.2/1.3 可用，TLS 1.1 握手失败；证据等级 `REAL_PROCESS`。

## 2. 静态边界

| 检查 | 结果 | 依据 |
| --- | --- | --- |
| `/actuator` 显式 404 | PASS | `production.conf` 的 `location ^~ /actuator { return 404; }` |
| 管理端口 8081 不映射 | PASS | `compose.prod.yml` app 无 ports |
| Nginx 不引用 8081 | PASS | `prod-compose-contract.mjs` 断言 |
| `/internal/**` 不 proxy_pass | PASS | 显式 404 规则 + 实际请求 |
| 代理信任锚 | PASS | Nginx 固定 `172.30.173.2`；后端只信任该地址 |
| `Forwarded` 丢弃 | PASS | `proxy_set_header Forwarded ""` |
| TLS 1.2/1.3 | PASS | 实测 |

## 3. 未验证

- 生产证书链/OCSP/HSTS：`NOT_RUN`（无生产域名/证书）；
- 真实生产安全组与公网扫描：`NOT_RUN`；
- 生产 canary：`NOT_STARTED`。
