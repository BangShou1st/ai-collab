package com.shitulelv.aicollab.agent.job;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link AiIndexPruneJob} 在 {@code memory.job.enabled=true/false} 下的装配切片测试（不连 Redis、不依赖 Docker）。
 */
class AiIndexPruneJobContextTest {

    private final AiIndexPruneService pruneService = mock(AiIndexPruneService.class);
    private final RedissonClient redisson = mock(RedissonClient.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AiIndexPruneService.class, () -> pruneService)
            .withBean(RedissonClient.class, () -> redisson)
            .withUserConfiguration(AiJobConfiguration.class, AiIndexPruneJob.class);

    @Test
    void enabledContextCreatesPruneJob() {
        runner.withPropertyValues("memory.job.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AiIndexPruneJob.class);
        });
    }

    @Test
    void disabledContextDoesNotRequireRedissonOrCreatePruneJob() {
        new ApplicationContextRunner()
                .withBean(AiIndexPruneService.class, () -> pruneService)
                .withUserConfiguration(AiJobConfiguration.class, AiIndexPruneJob.class)
                .withPropertyValues("memory.job.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AiIndexPruneJob.class);
                });
    }
}
