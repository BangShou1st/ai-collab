package com.shitulelv.aicollab.agent.infrastructure.decide;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置绑定冒烟测试：默认值必须"行为不变"（java / 零外呼），越界按边界收敛，
 * python 与未知 mode 必须让上下文启动失败（而不是静默回退）。
 */
class DecidePropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DecideConfiguration.class);

    @Test
    void defaultsAreJavaAndDisableEveryShadowCost() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            DecideProperties properties = context.getBean(DecideProperties.class);
            assertThat(properties.mode()).isEqualTo(DecideMode.JAVA);
            assertThat(properties.workerBaseUrl()).isEqualTo("http://ai-worker-api:9100");
            assertThat(properties.deadlineMs()).isEqualTo(30000);
            assertThat(properties.maxConcurrency()).isEqualTo(8);
            assertThat(properties.maxTools()).isEqualTo(16);
            assertThat(properties.snapshotMaxChars()).isEqualTo(32000);
        });
    }

    @Test
    void overridesAreBoundAndModeIsCaseInsensitive() {
        contextRunner
                .withPropertyValues(
                        "decide.mode=ShAdOw",
                        "decide.worker-base-url=http://worker:9100/",
                        "decide.deadline-ms=12000",
                        "decide.max-concurrency=4",
                        "decide.max-tools=9",
                        "decide.snapshot-max-chars=16000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DecideProperties properties = context.getBean(DecideProperties.class);
                    assertThat(properties.mode()).isEqualTo(DecideMode.SHADOW);
                    assertThat(properties.workerBaseUrl()).isEqualTo("http://worker:9100");
                    assertThat(properties.deadlineMs()).isEqualTo(12000);
                    assertThat(properties.maxConcurrency()).isEqualTo(4);
                    assertThat(properties.maxTools()).isEqualTo(9);
                    assertThat(properties.snapshotMaxChars()).isEqualTo(16000);
                });
    }

    @Test
    void pythonModeFailsStartupBecauseItIsNotImplemented() {
        contextRunner.withPropertyValues("decide.mode=python").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("decide.mode")
                    .hasStackTraceContaining("python 模式 W4 未实现");
        });
    }

    @Test
    void unknownModeFailsStartupInsteadOfSilentlyFallingBack() {
        contextRunner.withPropertyValues("decide.mode=neural").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("decide.mode")
                    .hasStackTraceContaining("neural");
        });
    }

    @Test
    void outOfRangeValuesAreClampedToBoundaries() {
        contextRunner
                .withPropertyValues(
                        "decide.deadline-ms=0",
                        "decide.max-concurrency=999",
                        "decide.max-tools=999",
                        "decide.snapshot-max-chars=1")
                .run(context -> {
                    DecideProperties properties = context.getBean(DecideProperties.class);
                    assertThat(properties.deadlineMs()).isEqualTo(1);
                    assertThat(properties.maxConcurrency()).isEqualTo(64);
                    assertThat(properties.maxTools()).isEqualTo(32);
                    assertThat(properties.snapshotMaxChars()).isEqualTo(4000);
                });

        contextRunner
                .withPropertyValues(
                        "decide.deadline-ms=9999999",
                        "decide.max-concurrency=0",
                        "decide.max-tools=0",
                        "decide.snapshot-max-chars=99999999")
                .run(context -> {
                    DecideProperties properties = context.getBean(DecideProperties.class);
                    assertThat(properties.deadlineMs()).isEqualTo(600000);
                    assertThat(properties.maxConcurrency()).isEqualTo(1);
                    assertThat(properties.maxTools()).isEqualTo(1);
                    assertThat(properties.snapshotMaxChars()).isEqualTo(200000);
                });
    }

    @Test
    void modeParsingAcceptsOnlyJavaAndShadow() {
        assertThat(DecideMode.from("JaVa")).isEqualTo(DecideMode.JAVA);
        assertThat(DecideMode.from("SHADOW")).isEqualTo(DecideMode.SHADOW);
        assertThatThrownBy(() -> DecideMode.from("python"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("python 模式 W4 未实现");
        assertThatThrownBy(() -> DecideMode.from("neural"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decide.mode");
        assertThatThrownBy(() -> DecideMode.from(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decide.mode");
    }
}
