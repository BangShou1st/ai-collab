package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.application.runtime.AgentCancellationService;
import com.shitulelv.aicollab.agent.application.runtime.AgentImmediateWakeListener;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** AGENT_RUNTIME_ALLOWED=false 时，执行器、定时入口和即时唤醒入口必须一并物理移除。 */
class AgentRuntimeCutoffConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    AgentRunExecutionConfiguration.class,
                    AgentRuntimeJob.class,
                    AgentImmediateWakeListener.class)
            .withBean(AgentRecoveryJob.class, () -> mock(AgentRecoveryJob.class))
            .withBean(AgentWorker.class, () -> mock(AgentWorker.class))
            .withBean(AgentCancellationService.class, () -> mock(AgentCancellationService.class))
            .withBean(AgentApprovalRepository.class, () -> mock(AgentApprovalRepository.class));

    @Test
    void runtimeCutoffRemovesEveryExecutionAndWakeBean() {
        runner.withPropertyValues("agent.runtime-allowed=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("agentRunTaskExecutor");
            assertThat(context).doesNotHaveBean(AgentRuntimeJob.class);
            assertThat(context).doesNotHaveBean(AgentImmediateWakeListener.class);
        });
    }

    @Test
    void runtimeAllowedKeepsExecutionAndWakeBeans() {
        runner.withPropertyValues("agent.runtime-allowed=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("agentRunTaskExecutor");
            assertThat(context).hasSingleBean(AgentRuntimeJob.class);
            assertThat(context).hasSingleBean(AgentImmediateWakeListener.class);
        });
    }
}
