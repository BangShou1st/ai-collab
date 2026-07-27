# Phase 08 Evidence-Driven Repair Progress

## 当前分支和提交

- **分支**: `feat/phase-08-ai-task-planning`
- **HEAD**: `249aeb6` docs: align Phase 08 OpenAPI architecture and database
- **工作区**: 2 untracked (`docs/superpowers/plans/...`, `fix/`)

## 当前真实失败

### STARTUP-BLOCKER: OpenAiCompatibleChatModelGateway Bean 实例化失败

- **命令**: `.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local`
- **最内层异常**: `BeanCreationException: OpenAiCompatibleChatModelGateway - No default constructor found`
- **根因假设**: 类有两个构造器（单参数 `ChatModelProperties` 和双参数 `ChatModelProperties + boolean`），均无 `@Autowired`。Spring 在多构造器情况下尝试无参构造器，找不到即失败。
- **证据**:
  - `OpenAiCompatibleChatModelGateway.java:35-47` 定义了两个 public 构造器
  - 无 `@Autowired` 注解
  - `@Component` 注解触发 component scan 创建 bean
  - Spring 4.3+ 只在单构造器时自动注入；多构造器需显式标注

## 已关闭

（待修复后填写）

## 进行中

- [ ] STARTUP-BLOCKER: 修复 OpenAiCompatibleChatModelGateway 构造器歧义
- [ ] F1-F6: 第三轮复核阻断问题
- [ ] H1-H9: 高风险问题

## 未关闭

- [ ] F1: 骨架校验矛盾
- [ ] F2: DETAIL Prompt Schema 错误
- [ ] F3: 取消覆盖 SKELETON/DETAIL/REPAIR
- [ ] F4: 详情响应 null NPE
- [ ] F5: 限流顺序
- [ ] F6: FAILED confirmation 新 key 409
- [ ] H1: 终态清理 activeAttemptId
- [ ] H2: 队列拒绝按阶段归类
- [ ] H3: markRunning CAS
- [ ] H4: DetailModelOutput tempKey 校验
- [ ] H5: milestone description
- [ ] H6: 来源和依赖校验
- [ ] H7: PLANNING_ENABLED=false
- [ ] H8: 项目删除 stable 语义
- [ ] H9: 人工保存成员一致性
- [ ] OpenAPI 同步
- [ ] architecture.md / database.md 更新
- [ ] 真实 Spring Boot 启动验证
- [ ] 后端测试 0 Failures 0 Errors 0 Skipped
- [ ] 前端 typecheck/test/build

## 下一步唯一动作

修复 OpenAiCompatibleChatModelGateway 构造器歧义 → Context RED 测试 → 真实启动验证
