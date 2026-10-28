# P0 部署与安全基础验收

**当前结论：PARTIAL_ACCEPTANCE；PRODUCTION_DEPLOYMENT_NOT_AUTHORIZED。** 本轮实现并测试了可在本机隔离环境执行的部分；目标生产环境、外部模型、异机恢复和真实告警未验收，不能签署 `PRODUCTION_GO`。

开始基线为 `dev` / `87817b54245be98bc3a3bd2b1781a6a3ecd098dc`，远端 `dev` 当时相同，工作区干净；`main` 基线 `9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`。历史同 SHA CI 为成功，属于 `HISTORICAL_ONLY`，本轮新增修改不继承其结果。仓库要求日常变更直接在 `dev`，故未按外部材料建议另建分支；没有更新远端、合并 `main` 或部署生产。

| 工作流 | 本轮证据摘要 | 状态 |
| --- | --- | --- |
| P0-00 基线 | 对照源码、历史 P5/P7 门禁与远端提交；Embedding 历史 G-1 保持已关闭 | VERIFIED |
| P0-01 网络/代理 | 独立 Compose、固定代理信任、合成 TLS、真实两个客户端 IP 和伪造头 | VERIFIED（隔离环境）；公网及目标网络 BLOCKED_EXTERNAL |
| P0-02 身份/密钥 | 用户要求开放注册；隔离环境已验证注册、普通用户权限、限流、合成 TLS、Cookie、Origin、RBAC 和签名下载；真实秘密和证书未下发 | 部分 VERIFIED；生产 BLOCKED_EXTERNAL |
| P0-03 备份/恢复 | 真实隔离恢复 54 张表、3 条文档引用与 3 个对象对账；模拟查询/对账失败 | VERIFIED（隔离环境）；异机 BLOCKED_EXTERNAL |
| P0-04 CI/监控 | 本地 CI 契约、镜像构建、告警模拟及日志扫描；真实告警和本轮远端 CI 未运行 | PARTIAL |
| P0-05 E2E | 真实隔离 HTTPS 身份/权限、SSE 续传及依赖重启；浏览器仅通过本机桥接观察页面 | PARTIAL；真实模型、目标 TLS 等 BLOCKED_EXTERNAL |

细节见 [基线与变更](baseline-and-changes.md)、[逐文件清单](change-inventory.md)、[测试矩阵](test-matrix.md) 和 [剩余门禁与回滚](open-items-and-rollback.md)。截图是使用 browser-skill 在本机 HTTP 测试桥上获取的 [登录页](login-page-http-bridge.png) 和 [开放注册页](registration-open-http-bridge.png)，不是受信任生产 TLS 的证明。
