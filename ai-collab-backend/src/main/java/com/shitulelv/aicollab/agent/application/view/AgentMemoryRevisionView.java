package com.shitulelv.aicollab.agent.application.view;

import java.time.OffsetDateTime;
import java.util.UUID;

/** agent_memory_revision 修订历史条目(AC07-P1 第二波,管理视图追溯用)。 */
public record AgentMemoryRevisionView(
        UUID id, UUID projectId, UUID memoryId, String operation,
        String beforeContent, String afterContent,
        String beforeState, String afterState,
        String verificationStateBefore, String verificationStateAfter,
        UUID supersededByTargetId, String reason, UUID actorId,
        int prevVersion, OffsetDateTime createdAt) {
}
