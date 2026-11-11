# 内部服务令牌：Ed25519 短令牌签发/轮换 runbook

> 日期：2026-11-12
> 适用：W3.2 起 Java 控制面 → Python 认知面，以及 Python → `/internal/ai/**` 的回调。
> 契约：[W3 收尾冻结](w3-final-freeze-20261112.md) §2；设计 §7.8。
> **本文不写入任何真实密钥**：只写操作步骤、变量名与判据。

---

## 1. 现状与目标

| 项 | W3（现状） | W3.2（Ed25519，opt-in） |
| --- | --- | --- |
| 传输 | 静态 `RERANK_SERVICE_TOKEN`（两端同值） | 短令牌 JWS（EdDSA/Ed25519），TTL 300s |
| 私钥位置 | — | **只在 Java**（PKCS#8 DER base64） |
| 公钥位置 | — | **只在 Python**（SPKI DER base64，多 `kid`） |
| claims | — | `iss/sub/aud/scope/jti/iat/exp/kid` |
| 默认 | `static` | `static`（Ed25519 需显式开启） |

回滚：两侧同时切回 `static` + 同一 `RERANK_SERVICE_TOKEN`，无需改代码。

---

## 2. 生成密钥（一次性，离线执行）

```bash
# 私钥（PKCS#8 DER，base64 单行）——只在 Java 侧注入
openssl genpkey -algorithm ed25519 -out ed25519-k1.pem
openssl pkcs8 -topk8 -nocrypt -in ed25519-k1.pem -outform DER | base64 -w0 > ed25519-k1.private.b64

# 公钥（SPKI DER，base64 单行）——注入 Python
openssl pkey -in ed25519-k1.pem -pubout -outform DER | base64 -w0 > ed25519-k1.public.b64

# 自检：私钥/公钥配对（任意一段可验证签名即可，openssl 3.x）
openssl pkey -in ed25519-k1.pem -pubout -outform PEM | openssl pkey -pubin -pubout -outform DER | base64 -w0
# 输出应与 ed25519-k1.public.b64 相同
```

- `kid` 用短标识（`k1`、`k2`…），**不复用**：换密钥即新 `kid`；
- 私钥文件只在密钥托管/部署机存在，不提交 Git、不进镜像、不写文档。

---

## 3. 配置注入

Java（app）：

```dotenv
INTERNAL_AI_TOKEN_MODE=ed25519
INTERNAL_AI_ED25519_PRIVATE_KEY=<ed25519-k1.private.b64 的内容>
INTERNAL_AI_ED25519_KID=k1
# 保留用于回滚；ed25519 模式下不参与签发
RERANK_SERVICE_TOKEN=<原静态令牌>
```

Python（worker）：

```dotenv
AI_WORKER_TOKEN_MODE=ed25519
AI_WORKER_ED25519_PUBLIC_KEYS=k1=<ed25519-k1.public.b64 的内容>
# 保留用于回滚
AI_WORKER_SERVICE_TOKEN=<原静态令牌>
```

> 多 `kid` 用逗号分隔：`k1=<b64>,k2=<b64>`。公钥不是秘密，但仍不进日志/文档。

---

## 4. 上线顺序（先验签、后签发）

1. **Python 先就绪**：配置 `AI_WORKER_TOKEN_MODE=ed25519` + 新公钥 → 滚动重启 worker；
   此时 Java 仍发静态令牌，worker 会拒绝 → 不可接受，因此**不能先切 Python 的强制校验**。
   *实际安全顺序*：先让 Python 同时**接受**静态令牌与 Ed25519（`AI_WORKER_TOKEN_MODE=dual`，
   W3.2 实现提供 `dual` 过渡值：先验 Ed25519，失败再按静态常量时间比较）；重启 worker；
2. **切换 Java 签发**：`INTERNAL_AI_TOKEN_MODE=ed25519` → 滚动重启 app；此时 worker `dual`
   能验两种令牌，不中断；
3. **观察**：`/actuator/prometheus` 与 worker `/metrics` 中鉴权失败计数为 0，持续 ≥10 分钟
   （≥2×TTL）；
4. **收敛**：worker 改 `AI_WORKER_TOKEN_MODE=ed25519`（不再接受静态令牌）→ 重启；
5. **回滚**：任一步异常 → Java 改回 `static`、worker 改回 `dual`/`static`，两边共用原静态令牌。

> `dual` 只用于轮换过渡，生产长期必须收敛到 `ed25519`；`dual` 期间静态令牌仍是有效凭据，
> 因此过渡窗口应尽量短（建议 ≤24h），并在收敛后**轮换掉**原静态令牌。

---

## 5. 密钥轮换

TTL 300s、允许 ±60s 时钟偏移，因此旧密钥至少要保留 **10 分钟**（建议 24h 便于回滚）：

1. 生成 `k2`（私钥 + 公钥）；
2. Python 配置加入 `k2=<b64>`（保留 `k1`）→ 滚动重启 worker；
3. Java 切 `INTERNAL_AI_ED25519_KID=k2` + 新私钥 → 滚动重启 app；
4. 观察 ≥10 分钟无鉴权失败；
5. 从 Python 配置移除 `k1` → 重启 worker；私钥 `k1` 归档/销毁。

**禁止**：先删旧公钥再切 Java（会出现 5 分钟窗口内旧令牌无法验证）；用同一 `kid` 签发不同密钥
（轮换语义失效，且旧令牌会被误验）。

---

## 6. 验证与判据

| 检查 | 命令/方法 | 通过判据 |
| --- | --- | --- |
| 令牌结构 | 任意一次 Java→worker 请求的抓包（仅测试环境）或 Java 单测固定 fixture | 三段 base64url、header `alg=EdDSA`、`kid` 在公钥集合内 |
| 过期 | 单测（`exp=now-120`） | 401，日志 `reason=expired`，**不含令牌** |
| 错签名 | 单测（改 1 字节签名） | 401 `bad_signature` |
| 错 aud / sub / kid | 单测 | 401，reason 分别为 `audience`/`subject`/`unknown_kid` |
| 时钟偏移 | 单测（±59s 通过、±61s 拒绝） | 与契约一致 |
| 跨语言 | Java 固定密钥 + 固定 claims 生成固定 JWS → Python 验签 fixture | 两边断言同一个字符串 |
| 日志卫生 | 单测断言日志/错误体不含 `eyJ` | 通过 |
| 真实联调 | staging 双端 | 本会话无 staging → **NOT_VERIFIED**，上线前必须补 |

---

## 7. 失败模式与处置

| 现象 | 原因 | 处置 |
| --- | --- | --- |
| worker 全部 503 | `AI_WORKER_TOKEN_MODE=ed25519` 但公钥未配置/格式错 | 修配置（公钥 SPKI DER base64）；进程可启动但端点 fail closed，`/readyz` 503 |
| app 启动失败 | `ed25519` 模式私钥缺失/非 PKCS#8/长度错 | 修 `INTERNAL_AI_ED25519_PRIVATE_KEY`；**不**静默回退静态 |
| 401 `unknown_kid` | Python 未加新 `kid` 或 Java `kid` 写错 | 按 §5 顺序补公钥，不要跳步 |
| 401 `expired` 且时间正常 | 节点时钟漂移 >60s | 校时（NTP）；不要放大 skew 容忍 |
| 401 `audience/scope` | 端点要求的 aud/scope 与签发不匹配 | 检查端点声明的 scope；不要放宽到"任意 scope" |
| 回滚后仍 401 | worker 仍是 `ed25519` 模式 | 两侧同时回 `static`/`dual`，确认 `RERANK_SERVICE_TOKEN` 同值 |

---

## 8. 合规

- 私钥/公钥/令牌都不进日志、错误体、指标标签、Git；
- 令牌只在内存中透传（Java 签发 → worker 验签 → 原样回传 Java），不落盘、不缓存到磁盘；
- 公钥可轮换、可多把；私钥只此一份（如需多副本签发，先做密钥托管再扩）。
