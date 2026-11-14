package com.shitulelv.aicollab.agent.job;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI Job 状态机纯函数测试：逐对校验 W5 冻结 §3.2 的合法/非法跃迁（含终态）。
 */
class AiJobStateMachineTest {

    private static final Map<AiJobStatus, Set<AiJobStatus>> EXPECTED = Map.of(
            AiJobStatus.QUEUED, EnumSet.of(AiJobStatus.LEASED, AiJobStatus.CANCELED,
                    AiJobStatus.EXPIRED),
            AiJobStatus.LEASED, EnumSet.of(AiJobStatus.RUNNING, AiJobStatus.FAILED_RETRYABLE,
                    AiJobStatus.FAILED, AiJobStatus.CANCELED, AiJobStatus.EXPIRED),
            AiJobStatus.RUNNING, EnumSet.of(AiJobStatus.SUCCEEDED, AiJobStatus.FAILED_RETRYABLE,
                    AiJobStatus.FAILED, AiJobStatus.CANCELED, AiJobStatus.EXPIRED),
            AiJobStatus.FAILED_RETRYABLE, EnumSet.of(AiJobStatus.QUEUED, AiJobStatus.FAILED,
                    AiJobStatus.CANCELED, AiJobStatus.EXPIRED),
            AiJobStatus.SUCCEEDED, Set.of(),
            AiJobStatus.FAILED, Set.of(),
            AiJobStatus.CANCELED, Set.of(),
            AiJobStatus.EXPIRED, Set.of());

    @Test
    void validatesEveryLegalTransition() {
        EXPECTED.forEach((from, targets) -> {
            assertThat(AiJobStateMachine.allowedTargets(from)).containsExactlyInAnyOrderElementsOf(targets);
            for (AiJobStatus target : targets) {
                assertThat(AiJobStateMachine.canTransition(from, target))
                        .as("%s → %s", from, target)
                        .isTrue();
                assertThat(AiJobStateMachine.allowedTargets(from)).contains(target);
            }
        });
    }

    @Test
    void rejectsEveryIllegalTransition() {
        for (AiJobStatus from : AiJobStatus.values()) {
            for (AiJobStatus target : AiJobStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(target);
                assertThat(AiJobStateMachine.canTransition(from, target))
                        .as("%s → %s", from, target)
                        .isEqualTo(expected);
                if (!expected) {
                    assertThatThrownBy(() -> AiJobStateMachine.requireTransition(from, target))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining(from.name())
                            .hasMessageContaining(target.name());
                }
            }
        }
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        for (AiJobStatus terminal : new AiJobStatus[]{AiJobStatus.SUCCEEDED,
                AiJobStatus.FAILED, AiJobStatus.CANCELED, AiJobStatus.EXPIRED}) {
            assertThat(terminal.terminal()).isTrue();
            assertThat(AiJobStateMachine.allowedTargets(terminal)).isEmpty();
        }
        for (AiJobStatus nonTerminal : new AiJobStatus[]{AiJobStatus.QUEUED,
                AiJobStatus.LEASED, AiJobStatus.RUNNING, AiJobStatus.FAILED_RETRYABLE}) {
            assertThat(nonTerminal.terminal()).isFalse();
            assertThat(AiJobStateMachine.allowedTargets(nonTerminal)).isNotEmpty();
        }
    }

    @Test
    void nullsAndSelfTransitionsAreIllegal() {
        assertThat(AiJobStateMachine.canTransition(null, AiJobStatus.QUEUED)).isFalse();
        assertThat(AiJobStateMachine.canTransition(AiJobStatus.QUEUED, null)).isFalse();
        assertThat(AiJobStateMachine.allowedTargets(null)).isEmpty();
        for (AiJobStatus status : AiJobStatus.values()) {
            assertThat(AiJobStateMachine.canTransition(status, status))
                    .as("%s → %s", status, status)
                    .isFalse();
            assertThat(AiJobStateMachine.canTransition(status, status)).isFalse();
        }
    }
}
