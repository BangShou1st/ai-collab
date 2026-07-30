package com.shitulelv.aicollab.agent.domain;

import com.shitulelv.aicollab.agent.domain.policy.AgentApprovalPolicy;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentApprovalPolicyTest {
    private final AgentApprovalPolicy policy = new AgentApprovalPolicy();

    @Test
    void nonceIsBoundToApprovalAndComparedAsHash() {
        UUID approvalId = UUID.randomUUID();
        String hash = policy.nonceHash(approvalId.toString());

        assertThat(policy.matchesNonce(hash, approvalId.toString())).isTrue();
        assertThat(policy.matchesNonce(hash, UUID.randomUUID().toString())).isFalse();
    }

    @Test
    void expiredApprovalCannotExecute() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThatThrownBy(() -> policy.requirePending(
                "PENDING", now.minusSeconds(1), now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("过期");
    }

    @Test
    void resolvedApprovalCannotExecuteAgain() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThatThrownBy(() -> policy.requirePending(
                "APPROVED", now.plusMinutes(5), now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已处理");
    }
}
