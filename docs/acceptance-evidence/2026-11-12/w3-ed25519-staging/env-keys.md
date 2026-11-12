# 双端环境键值与模式（staging `box-aae54f`）

> 所有敏感值一律 `<redacted>`。本文件不含私钥、公钥内容或任何令牌。
> 采集方式：`docker exec <container> env`（仅列相关键），每个阶段重建后采集。

## 公钥指纹（唯一保留的密钥材料）

| kid | SPKI DER SHA-256 |
| --- | --- |
| k1 | `f145784b2552741173fdc0328b14ae23a3547d7e86cfe14626a5cf2ff391a9bb` |
| k2 | `329cf83bb7354c3f1e42facb135de6b8eee6bfd043e25ceee383592f96762ef8` |

## 阶段矩阵

| 阶段 | app `INTERNAL_AI_TOKEN_MODE` | app `INTERNAL_AI_ED25519_KID` | app 私钥 | app `INTERNAL_AI_ED25519_PUBLIC_KEYS` | worker `AI_WORKER_TOKEN_MODE` | worker 公钥集合 |
| --- | --- | --- | --- | --- | --- | --- |
| A | `ed25519` | `k1` | k1 | 空 | `ed25519` | `k1` |
| fixture | `ed25519` | `k1` | k1 | 空 | `ed25519` | `k1`（fixture 固定公钥） |
| B | `ed25519` | `k1` | k1 | `k2` | `ed25519` | `k1,k2` |
| C | `ed25519` | `k2` | k2 | 空 | `ed25519` | `k2` |
| rot1 | `dual` | `k1` | k1 | 空 | `dual` | `k1` |
| rot2 | `dual` | `k1` | k1 | 空 | `dual` | `k1,k2` |
| rot3 | `dual` | `k2` | k2 | `k1` | `dual` | `k1,k2` |
| converge | `dual` | `k2` | k2 | `k1` | `ed25519` | `k2` |
| static 回滚 | `static` | — | — | — | `static` | 空 |
| supplement | `ed25519` | `k3` | k3（临时） | 空 | `ed25519` | `k3`（临时，测后删除） |

固定参数（ed25519/dual 阶段）：

| 键 | 值 |
| --- | --- |
| `INTERNAL_AI_TOKEN_TTL_SECONDS` | `300` |
| `INTERNAL_AI_TOKEN_CLOCK_SKEW_SECONDS` | `60` |
| `RERANK_MODE` | `java` |
| `DECIDE_MODE` | 未显式设置 → `java`（application.yml 默认） |
| `PLANNING_DRAFT_MODE` | 未显式设置 → `java`（application.yml 默认） |
| `MEMORY_JOB_ENABLED` | 未显式设置 → `false`（application.yml 默认） |

## 最终状态（`2026-11-12T05:07Z` 采集）

| 服务 | 键 | 值 |
| --- | --- | --- |
| app | `INTERNAL_AI_TOKEN_MODE` | `static` |
| app | `RERANK_MODE` | `java` |
| app | `DECIDE_MODE` | `java` |
| app | `PLANNING_DRAFT_MODE` | `java` |
| app | `MEMORY_JOB_ENABLED` | `false` |
| app | `RERANK_SERVICE_TOKEN` | `<redacted>`（已配置） |
| app | `INTERNAL_AI_ED25519_PRIVATE_KEY` / `_KID` / `_PUBLIC_KEYS` | 不存在 |
| worker | `AI_WORKER_TOKEN_MODE` | `static` |
| worker | `AI_WORKER_SERVICE_TOKEN` | `<redacted>`（已配置） |
| worker | `AI_WORKER_ED25519_PUBLIC_KEYS` | 不存在 |

- 最终健康：app `8081/actuator/health` = `UP`；worker `/readyz` = ready。
- 最终探针：静态令牌 worker `400` / Java `422`（认证通过）；两容器日志 `grep eyJ` = 0。
- 清理：`/data/.secrets/ed25519/`、`/data/home/w3s2/app-token.env`、`/data/home/w3s2/worker-token.env`、
  `/data/home/w3s2/compose.s2.yml` 均已删除；仅保留无密钥的 `compose.final.yml`。
- item 1 补测（05:41–05:43Z）使用临时 kid `k3`，测后已回落 static 并删除 k3 私钥/公钥与 override；
  补测结束时（05:43Z）最终 env 与上表一致。
