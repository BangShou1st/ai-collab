package com.shitulelv.aicollab.agent.api.dto;

import jakarta.validation.constraints.Size;

/** 候选审核意见；拒绝时 reason 必须非空，批准时可选。 */
public record AgentMemoryCandidateDecisionRequest(
        @Size(max = 500) String reason) {
}
