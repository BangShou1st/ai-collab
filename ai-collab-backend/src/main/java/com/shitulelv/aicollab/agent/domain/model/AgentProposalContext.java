package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 提供给 Runtime 的可信提案摘要。
 * 从数据库加载最新提案，不依赖截断聊天文本。
 */
public record AgentProposalContext(
    UUID approvalId,
    AgentProposalFamily proposalFamily,
    UUID subjectKey,
    String status,
    int revision,
    JsonNode arguments,
    JsonNode result,
    JsonNode latestDiff
) {}
