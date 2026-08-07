package com.shitulelv.aicollab.agent.application.view;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 不可变修订记录 View。
 * 记录每次提案修订的完整历史。
 */
public record AgentProposalRevisionView(
    UUID id,
    UUID projectId,
    UUID approvalId,
    UUID sourceRunId,
    int revision,
    JsonNode beforeArguments,
    JsonNode afterArguments,
    JsonNode diff,
    OffsetDateTime createdAt
) {}
