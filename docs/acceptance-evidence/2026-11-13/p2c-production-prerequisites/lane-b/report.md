# P2-C Lane B：生产密钥与真实证书链

> 日期：2026-11-13（Asia/Shanghai）
> 基线：`dev @ 211e4e32`
> 资源锁：`prod-secrets`（仅检查存在性、指纹、有效期、权限；未读取或输出私钥内容）
> 结论：**NOT_VERIFIED**
> 生产动作：**0**。未启用 Gateway、未打开生产流量、未修改生产配置。

## 1. 结论先行

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 生产证书/私钥注入 | **NOT_VERIFIED** | 当前工作区没有 `prod.env`，`TLS_CERT_FILE`/`TLS_KEY_FILE` 未设置，也没有真实生产证书材料 |
| 本地自签 TLS 协议 | **PASS（仅 LOCAL_SIMULATION）** | 同一份 Nginx 生产配置下 TLS 1.2/1.3 返回 200，TLS 1.1 握手失败；证书是自签 `localhost`，不能计入生产 PASS |
| 生产证书链/hostname/有效期 | **NOT_VERIFIED** | 无生产域名与 CA 证书链；本地自签证书链构建失败，SAN 仅覆盖 `localhost/127.0.0.1`；现有闸门只校验 leaf，不校验 fullchain |
| Ed25519 轮换流程 | **CONFIG_READY / NOT_VERIFIED** | runbook 已定义 `k1→k2`、旧公钥保留 `2×TTL`；未用生产密钥演练 |
| 私钥权限 | **NOT_VERIFIED** | `install.sh`/`prod-config-check` 不校验 TLS 私钥文件 mode/owner；本地测试 key ACL 过宽；Ed25519 私钥经环境变量注入，存在 Docker 配置暴露面 |
| 仓库密钥泄漏 | **PASS（仅 tracked repo）** | 无 tracked `.pem/.key`，无私钥标记；`.env`、`*.pem`、`*.key` 已被忽略 |
| 本地日志/崩溃信息 | **PASS（仅本地扫描）** | `target/` 日志未命中私钥标记；生产日志/崩溃转储未取得，生产侧仍为 `NOT_VERIFIED` |
| 配置闸门 | **PASS（NODE_UNIT）** | 容器内 `prod-config-check.test.mjs` 6/6 通过，覆盖 Ed25519 格式、证书/私钥匹配、SAN/hostname/有效期 |

**Lane B 判定：`NOT_VERIFIED`。** 没有真实生产证书/私钥，也没有生产等价注入环境；不能用本地自签材料冒充生产 PASS。

## 2. 生产等价环境是否存在

### 2.1 配置入口

`ai-collab-deploy/compose.prod.yml` 要求：

```text
AGENT_MCP_GATEWAY_ENABLED=${AGENT_MCP_GATEWAY_ENABLED:-false}
INTERNAL_AI_TOKEN_MODE=${INTERNAL_AI_TOKEN_MODE:-static}
INTERNAL_AI_ED25519_PRIVATE_KEY=${INTERNAL_AI_ED25519_PRIVATE_KEY:-}
INTERNAL_AI_ED25519_PUBLIC_KEYS=${INTERNAL_AI_ED25519_PUBLIC_KEYS:-}
INTERNAL_AI_ED25519_KID=${INTERNAL_AI_ED25519_KID:-k1}
TLS_CERT_FILE=<required absolute path>
TLS_KEY_FILE=<required absolute path>
```

依据：`ai-collab-deploy/compose.prod.yml:172-176`、`:222-223`。

### 2.2 当前工作区事实

| 检查 | 结果 |
| --- | --- |
| `ai-collab-deploy/prod.env` | **MISSING** |
| `ai-collab-deploy/local.env` | **MISSING** |
| 根 `.env` | 存在，但仅本地开发配置；无 TLS/Ed25519 生产密钥 |
| `TLS_CERT_FILE` | unset/empty |
| `TLS_KEY_FILE` | unset/empty |
| `INTERNAL_AI_TOKEN_MODE` | unset/empty |
| `INTERNAL_AI_ED25519_PRIVATE_KEY` | unset/empty |
| `INTERNAL_AI_ED25519_PUBLIC_KEYS` | unset/empty |
| `INTERNAL_AI_ED25519_KID` | unset/empty |
| `AGENT_MCP_GATEWAY_ENABLED` | unset/empty（未启用） |

原始脱敏输出：[`raw/env-presence.txt`](raw/env-presence.txt)。

Docker 仅有 `default` 与 `desktop-linux` 两个本地上下文，没有远程/生产 context；当前无法从本机直接取得生产证书或私钥。记录见 [`raw/docker-contexts.txt`](raw/docker-contexts.txt)。

### 2.3 Secret Manager 绑定

仓库文档要求生产密钥由 Secret Manager/部署平台注入（`docs/deployment/mcp-gateway-production-readiness-2026-11-13.md:100-124`），但仓库内没有可执行的 Secret Manager/Vault/SOPS/External Secrets 绑定，也没有 `prod.env` 实例。当前只能确认“配置闸门存在”，不能确认“生产密钥已注入”。

另外，`ai-collab-deploy/prod.env.example` 只列了 `TLS_CERT_FILE`、`TLS_KEY_FILE` 和 `AGENT_MCP_ALLOWED_HOSTS`，没有列出 `AGENT_MCP_GATEWAY_ENABLED`、`INTERNAL_AI_TOKEN_MODE`、`INTERNAL_AI_ED25519_PRIVATE_KEY`、`INTERNAL_AI_ED25519_PUBLIC_KEYS`、`INTERNAL_AI_ED25519_KID`；`install.sh` 生成的 `prod.env` 也不包含这些键。生产启用 Gateway 前必须由运维按 runbook 显式补齐，避免漏注入后仍以 `static` 启动。

## 3. 本地自签材料（只作模拟）

当前唯一可用的 TLS 材料来自 P2-A 测试目录：

```text
target/p2a-e2e/real-20261113-220539/tls/local-mcp-cert.pem
target/p2a-e2e/real-20261113-220539/tls/local-mcp-key.pem
target/p2a-e2e/real-20261113-220539/tls/local-mcp-truststore.p12
```

证书元数据（未输出私钥）：

| 字段 | 值 |
| --- | --- |
| Subject | `CN=localhost, O=AI Collab P2-A, C=CN` |
| Issuer | `CN=localhost, O=AI Collab P2-A, C=CN` |
| 自签 | **是** |
| 公钥 | RSA 2048 |
| SAN | `DNS:localhost, IP:127.0.0.1` |
| NotBefore (UTC) | `2026-11-13T14:00:39Z` |
| NotAfter (UTC) | `2026-11-15T14:05:39Z`（约 2 天） |
| 链构建 | **False**（自签根不在系统信任链） |
| SHA-256 指纹 | `A797D8C8989AE36EBCFFB5FE665887BDC61B7DA5BB7896952C7547031CAA1F62` |

原始脱敏输出：[`raw/cert-metadata.txt`](raw/cert-metadata.txt)。

结论：这是 **P2-A 本地测试证书**，CN/SAN 只覆盖 `localhost`，有效期仅 2 天，链构建失败。它不能证明生产域名、CA 证书链、OCSP/HSTS 或生产有效期。

## 4. TLS 协议复核（本地模拟）

使用同一份 `ai-collab-deploy/nginx/production.conf` 启动 `nginx:1.30-alpine`，挂载上述自签证书，在 `localhost:54443` 实测：

| 场景 | HTTP/退出码 | 判定 |
| --- | --- | --- |
| TLS 1.2（`--tlsv1.2 --tls-max 1.2`） | 200 / exit 0 | PASS（本地） |
| TLS 1.3（`--tlsv1.3`） | 200 / exit 0 | PASS（本地） |
| TLS 1.1（`--tlsv1.1 --tls-max 1.1`） | 000 / exit 35 | 握手失败，符合预期 |
| 客户端显式信任本地证书，访问 `localhost` | 200 / exit 0 | 本地信任模拟通过 |
| 客户端信任本地证书，访问 `collab.example.org` | 000 / exit 35 | hostname 不匹配，符合预期 |

原始输出：[`raw/tls-local.txt`](raw/tls-local.txt)。

边界：

- 这是 Nginx 配置 + 自签证书的 **LOCAL_SIMULATION**，不是生产 TLS 验收；
- “客户端显式信任本地证书”只证明本地链可控，不证明公共 CA 链、生产域名或外部客户端信任；
- 生产仍需在真实域名、真实证书链和真实网络入口上复验 TLS 1.2/1.3、链、hostname、有效期。
- 当前 `compose.prod.yml` 假设由 Nginx 终止 TLS；若生产改用云 LB 终止，必须重新验证证书注入、链、hostname、头部信任与安全组，不能沿用本结论。

## 5. 轮换与私钥权限

### 5.1 Ed25519 轮换

已有 runbook：`docs/archive/handoffs/w3-ed25519-rotation-runbook-20261112.md`。其定义：

- `kid` 不复用；换密钥必须换新 `kid`；
- 旧公钥至少保留 `2×TTL`（TTL 300s，建议保留 24h）；
- 顺序为 Python 先加新公钥 → Java 切新 `kid` + 新私钥 → 观察 ≥10 分钟 → 移除旧公钥；
- 禁止先删旧公钥再切 Java。

判定：**流程文档完整，但没有生产密钥可演练**。生产 `kid`、私钥、公钥集合均未注入，因此轮换只能标记 `CONFIG_READY / NOT_VERIFIED`。

### 5.2 TLS 证书续期

`docs/deployment/server-install.md` 只定义“证书续期后重启 proxy”，没有自动续期/热加载实现；生产证书来源仍由运维决定。当前无生产证书，无法验证续期流程。

现有配置闸门只读取站点证书（leaf）并检查配对、SAN、hostname、有效期，**不验证 fullchain 是否包含中间证书，也不做 CA 信任链校验**：

- `prod-config-check.mjs` 使用 `new X509Certificate(...)` 读取证书，未调用链验证；
- `install.sh` 使用 `openssl x509` 读取 leaf，未执行 `openssl verify -CAfile`。

因此即使脚本通过，也不能证明生产证书链完整。生产验收必须额外用 `openssl verify` 或真实外部客户端验证 fullchain，并确认中间证书顺序。

### 5.3 私钥权限

| 检查 | 结果 |
| --- | --- |
| `install.sh` 的 TLS 检查 | 只检查绝对路径、存在、证书/私钥匹配、过期、hostname；**不检查 mode/owner**（`ai-collab-deploy/install.sh:354-369`） |
| `prod-config-check.mjs` | 只读文件并检查匹配/SAN/hostname/有效期；**不检查权限**（`ai-collab-deploy/scripts/prod-config-check.mjs:126-143`） |
| `prod.env` 权限 | `install.sh` 生成时 `chmod 600`（`:464`、`:519`），但 `TLS_KEY_FILE` 可在任意路径，未被该 chmod 覆盖 |
| 本地测试 key ACL | `Authenticated Users=Modify`、`BUILTIN\Users=ReadAndExecute`；仅测试 key，不代表生产，但说明测试材料未按生产权限硬化 |
| Ed25519 私钥注入 | `compose.prod.yml:174` 作为容器环境变量传入；`docker inspect`/宿主 root 可读取容器配置，属于 `CONFIG` 级残余暴露面 |

原始 ACL（已脱敏）：[`raw/key-acl.txt`](raw/key-acl.txt)。

建议：生产 TLS 私钥至少 `0600` 且属主为运维账号；Ed25519 私钥优先用 Docker secret/只读文件挂载而非普通环境变量；若必须用环境变量，限制 Docker API 访问并明确接受 `docker inspect` 风险。

## 6. 泄漏边界

### 6.1 仓库

| 检查 | 结果 |
| --- | --- |
| tracked 私钥标记（`BEGIN ... PRIVATE KEY`） | 0 命中 |
| tracked `.pem/.key/.p12/.pfx/.jks` | 0 个 |
| `.gitignore` 规则 | `.env`、`*.pem`、`*.key` 均忽略 |
| 本地测试 key 是否被忽略 | `**/target/` 命中，未 tracked |

原始脱敏输出：[`raw/repo-leak-scan.txt`](raw/repo-leak-scan.txt)。

### 6.2 日志/崩溃信息

- `target/` 下 `*.log/*.txt/*.json` 私钥标记命中数：**0**；
- 当前工作区未取得生产日志或崩溃转储，无法验证生产侧；
- `prod-config-check` 的失败消息只描述字段/格式，不包含配置值；但这是代码审查，不是生产崩溃演练。

判定：**仓库与本地日志 PASS；生产日志/崩溃信息 NOT_VERIFIED**。

## 7. 配置闸门测试

在 `node:22-alpine` 容器内安装 `openssl` 后运行：

```text
node --test ai-collab-deploy/scripts/prod-config-check.test.mjs
tests 6 / pass 6 / fail 0
```

覆盖：

- 完整合成配置接受，占位符/重复密钥/可变镜像标签拒绝；
- Origin/MinIO/Feishu 配置校验；
- `AGENT_MCP_GATEWAY_ENABLED=true` 时强制 `ed25519`，拒绝非 Ed25519 材料；
- 实际证书/私钥匹配、SAN/hostname/有效期检查；
- 环境变量覆盖不安全值时 CLI 拒绝。

原始输出：[`raw/prod-config-check-tests-container.txt`](raw/prod-config-check-tests-container.txt)。

宿主直接运行曾因本机无 `openssl` 出现 5/6（唯一失败是 `spawnSync openssl ENOENT`），不是代码逻辑失败；容器内安装 `openssl` 后 6/6 通过。宿主输出保留在 [`raw/prod-config-check-tests.txt`](raw/prod-config-check-tests.txt)。

边界：**测试通过只证明配置闸门能校验格式/配对，不证明真实生产证书已注入或生产链路已验收。**

## 8. 缺口与决策人

| 缺口 | 当前状态 | 需要谁提供/决策 | 最小下一步 |
| --- | --- | --- | --- |
| 真实生产 TLS 证书链 + 私钥 | NOT_VERIFIED | 运维/平台 owner；域名 owner | 提供真实 `fullchain.pem`/`privkey.pem`，在生产等价环境按 0600 注入 |
| 生产域名/hostname | NOT_VERIFIED | 产品/运维 | 确认生产域名与 SAN；外部客户端复验 |
| 生产 Ed25519 私钥 + `kid` + 公钥集合 | NOT_VERIFIED | 后端 owner + 运维/安全 | 经 Secret Manager/部署平台注入；验证启动失败不回退 static |
| 私钥权限策略 | NOT_VERIFIED | 安全/运维 | 明确 TLS key mode/owner；决定 Ed25519 用文件/Docker secret 还是环境变量 |
| 生产轮换演练 | NOT_VERIFIED | 运维 + 后端 owner | 在 staging 用 `k2` 走完 runbook，保留脱敏证据 |
| 生产日志/崩溃信息检查 | NOT_VERIFIED | 运维 | 注入后抓取启动/异常日志与崩溃转储，确认无私钥/令牌 |

## 9. 判定

```text
Lane B：NOT_VERIFIED
原因：没有真实生产证书/私钥，也没有生产等价注入环境
本地 TLS：PASS（LOCAL_SIMULATION，仅自签 localhost）
生产 canary：仍未启动，且不得在 Lane B 未闭环前申请批准
```

未执行：生产密钥注入、生产证书链验证、生产轮换、生产 canary、生产流量切换。
