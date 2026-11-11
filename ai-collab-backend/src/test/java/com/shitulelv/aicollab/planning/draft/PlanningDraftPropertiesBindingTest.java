package com.shitulelv.aicollab.planning.draft;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Planning draft 配置绑定冒烟测试：默认值必须“行为不变”（java / 零外呼），越界按边界收敛，
 * python 与未知 mode 必须让上下文启动失败（而不是静默回退）。
 */
class PlanningDraftPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PlanningDraftConfiguration.class);

    @Test
    void defaultsAreJavaAndDisableEveryShadowCost() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            PlanningDraftProperties properties = context.getBean(PlanningDraftProperties.class);
            assertThat(properties.mode()).isEqualTo(PlanningDraftMode.JAVA);
            assertThat(properties.workerBaseUrl()).isEqualTo("http://ai-worker-api:9100");
            assertThat(properties.deadlineMs()).isEqualTo(60000);
            assertThat(properties.maxConcurrency()).isEqualTo(4);
            assertThat(properties.snapshotMaxChars()).isEqualTo(48000);
        });
    }

    @Test
    void overridesAreBoundAndModeIsCaseInsensitive() {
        contextRunner
                .withPropertyValues(
                        "planning.draft.mode=ShAdOw",
                        "planning.draft.worker-base-url=http://worker:9100/",
                        "planning.draft.deadline-ms=12000",
                        "planning.draft.max-concurrency=2",
                        "planning.draft.snapshot-max-chars=16000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PlanningDraftProperties properties = context.getBean(PlanningDraftProperties.class);
                    assertThat(properties.mode()).isEqualTo(PlanningDraftMode.SHADOW);
                    assertThat(properties.workerBaseUrl()).isEqualTo("http://worker:9100");
                    assertThat(properties.deadlineMs()).isEqualTo(12000);
                    assertThat(properties.maxConcurrency()).isEqualTo(2);
                    assertThat(properties.snapshotMaxChars()).isEqualTo(16000);
                });
    }

    @Test
    void pythonModeFailsStartupBecauseItIsNotImplemented() {
        contextRunner.withPropertyValues("planning.draft.mode=python").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("planning.draft.mode")
                    .hasStackTraceContaining("python 模式 W5 未实现");
        });
    }

    @Test
    void unknownModeFailsStartupInsteadOfSilentlyFallingBack() {
        contextRunner.withPropertyValues("planning.draft.mode=neural").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("planning.draft.mode")
                    .hasStackTraceContaining("neural");
        });
    }

    @Test
    void outOfRangeValuesAreClampedToBoundaries() {
        contextRunner
                .withPropertyValues(
                        "planning.draft.deadline-ms=0",
                        "planning.draft.max-concurrency=999",
                        "planning.draft.snapshot-max-chars=1")
                .run(context -> {
                    PlanningDraftProperties properties = context.getBean(PlanningDraftProperties.class);
                    assertThat(properties.deadlineMs()).isEqualTo(1);
                    assertThat(properties.maxConcurrency()).isEqualTo(64);
                    assertThat(properties.snapshotMaxChars()).isEqualTo(4000);
                });

        contextRunner
                .withPropertyValues(
                        "planning.draft.deadline-ms=9999999",
                        "planning.draft.max-concurrency=0",
                        "planning.draft.snapshot-max-chars=99999999")
                .run(context -> {
                    PlanningDraftProperties properties = context.getBean(PlanningDraftProperties.class);
                    assertThat(properties.deadlineMs()).isEqualTo(120000);
                    assertThat(properties.maxConcurrency()).isEqualTo(1);
                    assertThat(properties.snapshotMaxChars()).isEqualTo(200000);
                });
    }

    @Test
    void modeParsingAcceptsOnlyJavaAndShadow() {
        assertThat(PlanningDraftMode.from("JaVa")).isEqualTo(PlanningDraftMode.JAVA);
        assertThat(PlanningDraftMode.from("SHADOW")).isEqualTo(PlanningDraftMode.SHADOW);
        assertThatThrownBy(() -> PlanningDraftMode.from("python"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("python 模式 W5 未实现");
        assertThatThrownBy(() -> PlanningDraftMode.from("neural"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planning.draft.mode");
        assertThatThrownBy(() -> PlanningDraftMode.from(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planning.draft.mode");
    }
}
