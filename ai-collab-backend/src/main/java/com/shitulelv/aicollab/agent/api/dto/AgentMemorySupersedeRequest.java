package com.shitulelv.aicollab.agent.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** SUPERSEDE 替代请求(AC07-P1 第二波):把旧记忆指向同项目 ACTIVE 替代者,仅管理员可用。 */
public record AgentMemorySupersedeRequest(
        @NotNull UUID supersededBy,
        @Size(max=500) String reason) {
}
