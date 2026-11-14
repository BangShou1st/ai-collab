package com.shitulelv.aicollab.retrieval.rerank;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置绑定冒烟测试：默认值必须"行为不变"（java / 零外呼），取值大小写不敏感，
 * 未知 mode 必须让上下文启动失败（而不是静默回退）。
 */
class RerankPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RerankConfiguration.class);

    @Test
    void defaultsAreJavaAndDoNotEnableAnyPythonCall() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            RerankProperties properties = context.getBean(RerankProperties.class);
            assertThat(properties.mode()).isEqualTo(RerankMode.JAVA);
            assertThat(properties.workerBaseUrl()).isEqualTo("http://ai-worker-api:9100");
            assertThat(properties.deadlineMs()).isEqualTo(3000);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.serviceToken()).isEmpty();
            assertThat(properties.maxCandidates()).isEqualTo(50);
            assertThat(properties.shadowTopK()).isEqualTo(8);
            assertThat(properties.shadowSampleRate()).isEqualTo(1.0);
            assertThat(properties.modelUserIdAsUuid()).isEmpty();
        });
    }

    @Test
    void overridesAreBoundAndModeIsCaseInsensitive() {
        contextRunner
                .withPropertyValues(
                        "rerank.mode=PyThOn",
                        "rerank.worker-base-url=http://worker:9100/",
                        "rerank.deadline-ms=1500",
                        "rerank.connect-timeout=1s",
                        "rerank.service-token=test-only-token",
                        "rerank.max-candidates=7",
                        "rerank.shadow-top-k=3",
                        "rerank.shadow-sample-rate=0.25",
                        "rerank.model-user-id=99999999-9999-9999-9999-999999999999")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RerankProperties properties = context.getBean(RerankProperties.class);
                    assertThat(properties.mode()).isEqualTo(RerankMode.PYTHON);
                    assertThat(properties.workerBaseUrl()).isEqualTo("http://worker:9100");
                    assertThat(properties.deadlineMs()).isEqualTo(1500);
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.serviceToken()).isEqualTo("test-only-token");
                    assertThat(properties.maxCandidates()).isEqualTo(7);
                    assertThat(properties.shadowTopK()).isEqualTo(3);
                    assertThat(properties.shadowSampleRate()).isEqualTo(0.25);
                    assertThat(properties.modelUserIdAsUuid()).isPresent();
                });
    }

    @Test
    void outOfRangeShadowSampleRateIsClampedInsteadOfFailingStartup() {
        contextRunner.withPropertyValues("rerank.shadow-sample-rate=-0.5").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RerankProperties.class).shadowSampleRate()).isZero();
        });
        contextRunner.withPropertyValues("rerank.shadow-sample-rate=2.5").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RerankProperties.class).shadowSampleRate()).isEqualTo(1.0);
        });
        assertThat(RerankProperties.defaults().shadowSampleRate()).isEqualTo(1.0);
    }

    @Test
    void unknownModeFailsStartupInsteadOfSilentlyFallingBack() {
        contextRunner.withPropertyValues("rerank.mode=neural").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("rerank.mode");
        });
    }
}
