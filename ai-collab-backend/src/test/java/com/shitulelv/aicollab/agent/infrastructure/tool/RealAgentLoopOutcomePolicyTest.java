package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.ExternalProviderFailure;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealAgentLoopOutcomePolicyTest {
    @Test
    void onlySucceededIsARealAgentLoopSuccess() {
        assertThat(RealAgentLoopPostgresTest.isSuccessfulStatus("SUCCEEDED")).isTrue();
        for (String status : new String[] {"FAILED", "BUDGET_EXCEEDED", "CANCELED", "BLOCKED_EXTERNAL"}) {
            assertThat(RealAgentLoopPostgresTest.isSuccessfulStatus(status))
                    .as("%s must not count as a business success", status).isFalse();
        }
    }

    @Test
    void onlyTypedProviderFailuresCanBeRecordedAsExternalBlocks() {
        assertThat(RealAgentLoopPostgresTest.classifyUpstream(
                new ExternalProviderFailure(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED,
                        ExternalProviderFailure.Kind.RATE_LIMITED, 429)))
                .isEqualTo("RATE_LIMITED");
        assertThat(RealAgentLoopPostgresTest.classifyUpstream(
                new ExternalProviderFailure(ErrorCode.AI_PROVIDER_ERROR,
                        ExternalProviderFailure.Kind.UPSTREAM_5XX, 503)))
                .isEqualTo("UPSTREAM_5XX");
        assertThat(RealAgentLoopPostgresTest.classifyUpstream(
                new BusinessException(ErrorCode.AI_PROVIDER_ERROR))).isNull();
        assertThat(RealAgentLoopPostgresTest.classifyUpstream(new IllegalStateException("fixture failure"))).isNull();
    }

    @Test
    void modelAndTimeBudgetsApplyBeforeEachProviderCallAndOnExhaustion() {
        RealAgentLoopBudget budget = new RealAgentLoopBudget(1_000L, 100L, 2);
        assertThat(budget.beginModelCall(1_010L)).isEqualTo(90L);
        assertThat(budget.beginModelCall(1_020L)).isEqualTo(80L);
        assertThatThrownBy(() -> budget.beginModelCall(1_030L))
                .isInstanceOf(RealAgentLoopBudget.LimitExceededException.class)
                .hasMessageContaining("model-call limit");
        assertThatThrownBy(() -> budget.remainingNanos(1_100L))
                .isInstanceOf(RealAgentLoopBudget.LimitExceededException.class)
                .hasMessageContaining("wall-clock limit");
        assertThat(budget.modelCalls()).isEqualTo(2);
    }

    @Test
    void successAssertionRejectsFailureStatusesWithoutCallingARealModel() {
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("runStatus", "FAILED");
        assertThatThrownBy(() -> RealAgentLoopPostgresTest.assertSuccessfulStatus(failed))
                .isInstanceOf(AssertionError.class);
        failed.put("runStatus", "SUCCEEDED");
        assertThat(RealAgentLoopPostgresTest.assertSuccessfulStatus(failed)).isTrue();
    }

    @Test
    void answerAlternativesMatchAsOneGroupedPattern() {
        assertThat(RealAgentLoopPostgresTest.answerMatches(
                "已完成的导出文件在临时存储区保存**二十四小时**，过期后自动清理。",
                "二十四小时|24\\s*小时")).isTrue();
        assertThat(RealAgentLoopPostgresTest.answerMatches(
                "未找到相关依据，因此无法给出具体年限。",
                "资料不足|未找到|无法确定|没有依据")).isTrue();
    }
}
