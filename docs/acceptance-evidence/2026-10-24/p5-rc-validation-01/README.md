# P5-RC-VALIDATION-01 验收证据（2026-10-24）

| 文件 | 内容 | 脱敏说明 |
| --- | --- | --- |
| `pr-ci-40103355487-backend-job.log` | PR CI Backend Job 完整日志（1688 tests, 0 failures; container test guard passed; BUILD SUCCESS） | 无凭据内容 |
| `pr-ci-40103355487-frontend-job.log` | PR CI Frontend Job 完整日志（51 test files passed; lint 0 errors） | 无凭据内容 |
| `pr-ci-40103355487-image-job.log` | PR CI Image build & container smoke 完整日志（镜像构建 BUILD SUCCESS、actuator/env=401 metrics=401 info=401、revision 断言通过） | 冒烟凭据均为合成值（smoke-only*），非真实凭据 |

关联 Run：https://github.com/BangShou1st/ai-collab/actions/runs/40103355487 （event=pull_request，attempt 1，conclusion=success）
