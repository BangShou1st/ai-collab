# 管理员工作区重构与全站 AI 供应商共享架构设计

> 状态：APPROVED
> 设计者：主 Agent / 系统架构负责人
> 对应迁移版本：Flyway V77

---

## 1. 架构总览与角色分流

### 1.1 路由体系与工作区隔离
- **管理员专属工作区**：以 `AdminShell.vue` 作为根布局，与普通用户的 `AppShell.vue` 物理解耦。
- **登录角色分流（`router.ts` & `auth-store.ts`）**：
  - 管理员登录：重定向至 `/admin`（平台概览）。
  - 普通用户登录：重定向至 `/home`（协作工作台）。
  - 路由守卫：普通用户访问任何 `/admin/**` 路由直接拒绝并引导至 `/home`；管理员访问管理端拥有独立上下文，普通项目树不在管理员侧栏显示。
- **管理员扁平一级导航（5 个一级入口）**：
  1. `平台概览` (`/admin` 或 `/admin/overview`)：健康度指标、账号统计、共享供应商状态、系统核心开关快捷展示。
  2. `账号管理` (`/admin/users`)：用户检索、账号创建、启用/停用。
  3. `AI 供应商` (`/admin/ai-providers`)：
     - Tab 1: 全站 AI 供应商（添加、编辑、测试、发布为全站共享、撤销共享）。
     - Tab 2: 系统 Embedding 与知识库索引重建。
  4. `通知中心` (`/admin/notifications`)：系统管理员专属事件流（共享发布、异常告警、系统维护）。
  5. `系统设置` (`/admin/settings`)：系统运行模式、全局 Agent/Chat 开关、高级 Worker 并发与调度、RAG 检索参数。
  - 底部：管理员账号菜单及退出登录。

---

## 2. 共享 AI 供应商（Shared AI Provider）

### 2.1 数据库模式设计（Flyway V77）
```sql
-- V77: shared AI provider and admin notifications

-- 全站共享 AI 供应商表
CREATE TABLE shared_ai_provider (
    id UUID PRIMARY KEY,
    source_provider_id UUID NOT NULL,
    publisher_user_id UUID NOT NULL REFERENCES app_user(id),
    name VARCHAR(80) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    api_path VARCHAR(512) NOT NULL DEFAULT '/v1/chat/completions',
    encrypted_api_key TEXT NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED', -- 'PUBLISHED', 'REVOKED'
    temperature DOUBLE PRECISION NOT NULL DEFAULT 0.7,
    max_output_tokens INTEGER NOT NULL DEFAULT 4096,
    capabilities VARCHAR(500) NOT NULL DEFAULT 'CHAT,STREAMING,USAGE',
    capability_config_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_shared_ai_provider_source UNIQUE (source_provider_id),
    CONSTRAINT ck_shared_ai_provider_type CHECK (
        provider_type IN ('OPENAI_COMPATIBLE', 'ANTHROPIC', 'GEMINI')
    ),
    CONSTRAINT ck_shared_ai_provider_status CHECK (
        status IN ('PUBLISHED', 'REVOKED')
    )
);

CREATE INDEX idx_shared_ai_provider_status ON shared_ai_provider(status, enabled);

-- 调整用户用途绑定表：解除仅能绑定到个人 provider 的外键约束，引入 is_shared 标识
ALTER TABLE user_model_purpose_assignment
    DROP CONSTRAINT IF EXISTS user_model_purpose_assignment_provider_id_fkey;

ALTER TABLE user_model_purpose_assignment
    ADD COLUMN IF NOT EXISTS is_shared BOOLEAN NOT NULL DEFAULT FALSE;

-- 管理员专属系统通知表
CREATE TABLE admin_notification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category VARCHAR(50) NOT NULL,
    level VARCHAR(20) NOT NULL DEFAULT 'INFO',
    title VARCHAR(200) NOT NULL,
    content TEXT,
    target_path VARCHAR(255),
    dedupe_key VARCHAR(120),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_admin_notification_level CHECK (level IN ('INFO', 'WARNING', 'ERROR'))
);

CREATE INDEX idx_admin_notification_unread ON admin_notification (is_read, created_at DESC);
CREATE INDEX idx_admin_notification_dedupe ON admin_notification (dedupe_key);
```

### 2.2 发布幂等与并发控制
- 管理员发布供应商时，以 `source_provider_id` 进行关联查找：
  - 若已存在，则在同一个事务中原子更新该记录的名称、Base URL、ApiPath、加密 Key、模型、能力及配置版本号，推进 `capability_config_version + 1`，将 `status` 恢复为 `PUBLISHED`，`enabled = true`。
  - 无论发布 1 次还是 10 次，`shared_ai_provider` 中永远保持唯一对应行，不会生成重复项。
- 撤销全站共享：将 `status` 更新为 `REVOKED`，保留记录供用户端感知“已撤销不可用”。

### 2.3 普通用户自主选用规则
1. **优先使用用途明确绑定**：若当前用途（KNOWLEDGE_CHAT, PLANNING, AGENT）在 `user_model_purpose_assignment` 存在记录：
   - 若 `is_shared == true`：解析对应的 `shared_ai_provider`；若其已被撤销或停用，抛出 `AI_PROVIDER_UNAVAILABLE` 并提示重新选择。
   - 若 `is_shared == false`：解析个人的 `user_ai_provider`。
2. **次优先使用个人默认模型**：用户未对特定用途做覆盖时，走用户的 `is_default` 个人模型。
3. **未配置时保持原有行为**：不自动静默切换到管理员发布的共享供应商。
4. **安全隔离**：
   - 普通用户获取共享供应商列表的接口只返回脱敏视图（`id`, `name`, `modelName`, `providerType`, `capabilities`, `status`），绝不泄露 API Key、Base URL 等敏感内网地址。
   - 普通用户调用测试接口、修改接口直接被后端 403 拦截。

### 2.4 模型路由安全与解密
- 在 `RoutingChatModelGateway`、`RoutingModelTurnGateway`、`PlanningModelConfigurationStore`、`AgentModelConfigurationStore` 中：
  - 识别 `is_shared` 或 `shared_ai_provider` 来源。
  - 对 SHARED Provider：调用者的权限校验仅验证调用者为系统合法已认证账号，直接以服务端主密钥解密共享 Provider 的 Key；
  - 放行 snapshot / pinned 校验，不再断言 `snapshot.userId().equals(callerUserId)`。

---

## 3. 管理员专属通知

- **数据隔离**：`admin_notification` 与普通项目 `notification` 物理分表。
- **事件生成**：
  - `AI_PROVIDER`：共享供应商发布、撤销、配置变更、测试失败。
  - `EMBEDDING`：Embedding 索引重建触发、完成或失败。
  - `ACCOUNT`：账号停用/启用、密码重置请求等。
  - `SYSTEM`：系统维护模式开关。
- **去重策略**：通过 `dedupe_key` 避免短时间内连续重复刷屏。
- **操作接口**：
  - `GET /api/v1/admin/notifications`
  - `POST /api/v1/admin/notifications/{id}/read`
  - `POST /api/v1/admin/notifications/read-all`
