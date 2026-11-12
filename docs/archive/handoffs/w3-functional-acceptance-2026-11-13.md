# W3 功能验收补充冻结（2026-11-13）

> 用户决策：硬件不换，延迟长一点可以接受；先把功能做出来，再优化。
> 本文件不重写 W3 3s 结果，只增加 **functional acceptance** 口径。

## 1. 保留的历史结论

- W3 formal run 2 的 3s 门禁：8 档 PASS，20 档 15/15 `adapter_TIMEOUT` 3003ms，
  50 档 NOT_RUN → **W3 3s FAIL**。
- 该 FAIL 不得删除、改写或标成 PASS；生产继续 `RERANK_MODE=java`。

## 2. Functional acceptance（新增）

| 项 | 冻结值 |
| --- | --- |
| 目标 | 证明 Reranker 功能可用，不证明 3s 生产 SLO |
| Capability settings | `timeoutMs=30000`，其余同 W3 3s profile（maxCandidates=50、maxLength=2048） |
| 档位 | 8 / 20 / 50 候选 |
| 每档 | warm-up 后 15 次门禁；全部通过后补 60 次观测 |
| 成功判定 | 失败率 ≤1%（timeout/5xx/非法响应均算失败） |
| 可观测 | model_latency_known ≥99%；overhead P95 ≤800ms；model/e2e 分列 |
| 1:1 | 1 次请求 = 1 次 `/rerank` 调用 |
| 延迟 | P50/P95/P99 只记录；**不以 3s 判 functional PASS/FAIL** |

## 3. 判定词

- 全部档位 functional 门禁通过 → `FUNCTIONAL_PASS / 3S_FAIL`；
- 任一档位失败 → `FUNCTIONAL_FAIL`，不得用 30s 成功掩盖 3s FAIL；
- 生产用户路径仍 `NOT_VERIFIED`，不得称“可灰度”；
- W6 只能据此考虑 **default-off 实现 readiness**，不得据此启用生产。

## 4. 资源与收尾

- 用 staging `box-aae54f` + 本地 RTX 4060；staging 锁 B 独占。
- 不改模型、不改硬件、不缩候选、不改 3s 阈值。
- 结束后删除 30s profile 或保持 disabled，恢复默认开关，释放 staging。
