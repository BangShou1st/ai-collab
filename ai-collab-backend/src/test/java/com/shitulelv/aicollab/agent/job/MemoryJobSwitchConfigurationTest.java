package com.shitulelv.aicollab.agent.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 默认关闭与显式开启时 Memory Job 调度器/恢复器的装配边界。 */
class MemoryJobSwitchConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    AiJobDispatcher.class, AiJobDispatchScheduler.class, AiJobRecoveryJob.class)
            .withBean(AiJobRepository.class, () -> mock(AiJobRepository.class))
            .withBean(AiJobService.class, () -> mock(AiJobService.class))
            .withBean(AiJobTokenService.class, () -> mock(AiJobTokenService.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void defaultOffAssemblesNoDispatcherOrRecoveryBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AiJobDispatcher.class);
            assertThat(context).doesNotHaveBean(AiJobDispatchScheduler.class);
            assertThat(context).doesNotHaveBean(AiJobRecoveryJob.class);
        });
    }

    @Test
    void enabledAssemblesDispatcherButRecoveryStillNeedsItsOwnSwitch() {
        runner.withPropertyValues("memory.job.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AiJobDispatcher.class);
            assertThat(context).hasSingleBean(AiJobDispatchScheduler.class);
            assertThat(context).doesNotHaveBean(AiJobRecoveryJob.class);
        });
    }

    @Test
    void recoveryRequiresBothMasterAndRecoverySwitches() {
        runner.withPropertyValues(
                        "memory.job.enabled=true",
                        "memory.job.recovery-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiJobRecoveryJob.class);
                });
    }
}
