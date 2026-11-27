package com.shitulelv.aicollab.agent.job;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code memory.job.enabled=true} 时补偿任务的上下文装配切片测试（不需要 Docker、不连 Redis）。
 *
 * <p>staging 曾因 {@link AiTaskCompensationJob} 有两个构造器且都没标 {@code @Autowired} 而启动失败；
 * 默认测试上下文里开关为 false，这个 Bean 从未被 Spring 创建过。这里按真实装配加载
 * {@link AiJobConfiguration}（属性绑定 + RedissonClient 条件装配）与 {@link AiTaskCompensationJob}，
 * {@link AiJobService} 与 {@link RedissonClient} 用 mock：预先注册的 mock RedissonClient 让
 * {@code @ConditionalOnMissingBean} 跳过 {@code Redisson.create}，因此不会建立任何 Redis 连接。</p>
 */
class AiTaskCompensationJobContextTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final AiJobService jobs = mock(AiJobService.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AiJobService.class, () -> jobs)
            .withBean(RedissonClient.class, () -> redisson)
            .withUserConfiguration(AiJobConfiguration.class, AiTaskCompensationJob.class);

    @Test
    void enabledContextCreatesCompensationJobThroughTwoArgConstructor() {
        runner.withPropertyValues("memory.job.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("aiTaskCompensationJob");
            assertThat(context).hasSingleBean(AiTaskCompensationJob.class);
            assertThat(context).hasSingleBean(RedissonClient.class);
            assertThat(context.getBean(RedissonClient.class)).isSameAs(redisson);

            AiTaskCompensationJob job = context.getBean("aiTaskCompensationJob", AiTaskCompensationJob.class);
            // 双参构造器固定委托 Clock.systemUTC()；三参构造器需要的 Clock 在上下文里不存在。
            assertThat(ReflectionTestUtils.getField(job, "clock")).isEqualTo(Clock.systemUTC());
            assertThat(ReflectionTestUtils.getField(job, "jobs")).isSameAs(jobs);
            assertThat(ReflectionTestUtils.getField(job, "redisson")).isSameAs(redisson);
        });
    }

    @Test
    void disabledContextDoesNotCreateCompensationJob() {
        runner.withPropertyValues("memory.job.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("aiTaskCompensationJob");
            assertThat(context).doesNotHaveBean(AiTaskCompensationJob.class);
        });
    }
}
