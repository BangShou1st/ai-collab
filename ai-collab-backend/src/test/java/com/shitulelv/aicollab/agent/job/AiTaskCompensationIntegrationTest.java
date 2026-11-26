package com.shitulelv.aicollab.agent.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.shitulelv.aicollab.agent.job.AiTaskTestFixtures.epoch;
import static com.shitulelv.aicollab.agent.job.AiTaskTestFixtures.status;
import static com.shitulelv.aicollab.agent.job.AiTaskTestFixtures.streamEntries;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 补偿扫描真库测试（契约 §4.4、§6；Testcontainers PostgreSQL 17 + pgvector、Redis 7）。
 *
 * <ul>
 *   <li>QUEUED 超过 60s 未被认领 → 用原 epoch 重新 XADD；</li>
 *   <li>租约过期 → epoch+1 回到 QUEUED 并重新 XADD（新消息携带新 epoch）；</li>
 *   <li>超过最大次数 → FAILED，不再派发；FAILED_RETRYABLE 到期 → 重排；超过 deadline → EXPIRED；</li>
 *   <li>两个扫描器（各自独立的 RedissonClient，真抢同一把锁）并发执行，同一任务只被推进一次；</li>
 *   <li>绕过锁、两个扫描器直接并发扫描 30 个过期任务，每个任务仍只被推进一次（条件更新兜底）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class AiTaskCompensationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = AiTaskTestFixtures.postgres();

    @Container
    static final GenericContainer<?> REDIS = AiTaskTestFixtures.redis();

    private static JdbcTemplate jdbc;
    private static LettuceConnectionFactory lettuce;
    private static StringRedisTemplate redis;
    private static AiTaskStreamPublisher publisher;
    private static AiJobService service;
    private static UUID user;
    private static UUID project;

    @BeforeAll
    static void setUp() throws Exception {
        jdbc = new JdbcTemplate(AiTaskTestFixtures.migratedDataSource(POSTGRES));
        lettuce = AiTaskTestFixtures.lettuce(REDIS);
        redis = new StringRedisTemplate(lettuce);
        InternalTokenIssuer issuer = new InternalTokenIssuer(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate(), "k1", 300);
        publisher = new AiTaskStreamPublisher(redis, issuer, new SimpleMeterRegistry());
        service = new AiJobService(new AiJobRepository(jdbc), new AiJobProperties(true, 3, 60),
                new SimpleMeterRegistry(), new ObjectMapper(), event -> {
                    if (event instanceof AiJobQueuedEvent queued) {
                        publisher.onQueued(queued);
                    }
                }, Clock.systemUTC());
        user = AiTaskTestFixtures.user(jdbc, "compensation");
        project = AiTaskTestFixtures.project(jdbc, user, "补偿扫描");
    }

    @AfterAll
    static void tearDown() {
        if (lettuce != null) {
            lettuce.destroy();
        }
    }

    @BeforeEach
    void isolate() {
        jdbc.update("DELETE FROM ai_job");
    }

    @Test
    void staleQueuedTaskIsRedispatchedWithItsOriginalEpoch() {
        AiJobRecord job = service.create(project, "{}", user);
        assertThat(streamEntries(redis, job.id())).hasSize(1);

        OffsetDateTime later = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(120);
        assertThat(service.compensate(later, 100).redispatched()).isOne();
        // 同一时刻再扫：updated_at 刚被触碰，不再重复派发。
        assertThat(service.compensate(later, 100).redispatched()).isZero();

        List<Map<Object, Object>> entries = streamEntries(redis, job.id());
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(fields -> fields.get("epoch")).containsOnly("0");
        assertThat(status(jdbc, job.id())).isEqualTo("QUEUED");
        assertThat(epoch(jdbc, job.id())).isZero();
    }

    @Test
    void expiredLeaseBumpsEpochRequeuesAndRedispatchesWithNewEpoch() {
        AiJobRecord job = service.create(project, "{}", user);
        AiJobService.Lease lease = service.claim(job.id(), AiJobService.TASK_TYPE_MEMORY_EXTRACTION, 0L);
        service.heartbeat(job.id(), 0L, lease.leaseToken());

        AiJobService.CompensationSummary summary =
                service.compensate(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(120), 100);

        assertThat(summary.requeued()).isOne();
        assertThat(status(jdbc, job.id())).isEqualTo("QUEUED");
        assertThat(epoch(jdbc, job.id())).isOne();
        assertThat(jdbc.queryForObject("SELECT lease_token FROM ai_job WHERE id=?", UUID.class, job.id()))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM ai_job WHERE id=?", Integer.class, job.id()))
                .isOne();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ai_job_attempt WHERE job_id=? AND attempt_no=1", String.class, job.id()))
                .isEqualTo("FAILED_RETRYABLE");
        List<Map<Object, Object>> entries = streamEntries(redis, job.id());
        assertThat(entries).extracting(fields -> fields.get("epoch")).containsExactly("0", "1");

        // 新 epoch 可以再次认领，attempt 计数继续累加。
        AiJobService.Lease second = service.claim(job.id(), AiJobService.TASK_TYPE_MEMORY_EXTRACTION, 1L);
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.leaseEpoch()).isOne();
    }

    @Test
    void taskExceedingMaxAttemptsFailsInsteadOfRequeueing() {
        AiJobRecord job = service.create(project, "{}", user);
        jdbc.update("UPDATE ai_job SET max_attempts=1 WHERE id=?", job.id());
        service.claim(job.id(), AiJobService.TASK_TYPE_MEMORY_EXTRACTION, 0L);

        AiJobService.CompensationSummary summary =
                service.compensate(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(120), 100);

        assertThat(summary.exhausted()).isOne();
        assertThat(status(jdbc, job.id())).isEqualTo("FAILED");
        assertThat(epoch(jdbc, job.id())).as("收回租约同样 epoch+1，旧回调全部 409").isOne();
        assertThat(streamEntries(redis, job.id())).as("耗尽后不再派发").hasSize(1);
    }

    @Test
    void dueRetryIsRequeuedAndPastDeadlineExpires() {
        AiJobRecord retry = service.create(project, "{}", user);
        AiJobService.Lease lease = service.claim(retry.id(), AiJobService.TASK_TYPE_MEMORY_EXTRACTION, 0L);
        service.fail(retry.id(), 0L, lease.leaseToken(), true, "provider_timeout");
        assertThat(status(jdbc, retry.id())).isEqualTo("FAILED_RETRYABLE");

        AiJobRecord late = service.create(project, "{}", user);
        jdbc.update("UPDATE ai_job SET deadline_at=now() - interval '1 second' WHERE id=?", late.id());

        AiJobService.CompensationSummary summary =
                service.compensate(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(600), 100);

        assertThat(summary.requeued()).isOne();
        assertThat(summary.expired()).isOne();
        assertThat(status(jdbc, retry.id())).isEqualTo("QUEUED");
        assertThat(epoch(jdbc, retry.id())).isOne();
        assertThat(streamEntries(redis, retry.id())).extracting(fields -> fields.get("epoch"))
                .containsExactly("0", "1");
        assertThat(status(jdbc, late.id())).isEqualTo("EXPIRED");
        assertThat(epoch(jdbc, late.id())).isOne();
    }

    @Test
    void twoScannersRacingForTheRedissonLockAdvanceTheTaskOnlyOnce() throws Exception {
        AiJobRecord job = service.create(project, "{}", user);
        service.claim(job.id(), AiJobService.TASK_TYPE_MEMORY_EXTRACTION, 0L);
        Clock later = Clock.fixed(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(120).toInstant(), ZoneOffset.UTC);

        RedissonClient first = Redisson.create(AiJobConfiguration.redissonConfig(redisProperties()));
        RedissonClient second = Redisson.create(AiJobConfiguration.redissonConfig(redisProperties()));
        try {
            AiTaskCompensationJob scannerA = new AiTaskCompensationJob(service, first, later);
            AiTaskCompensationJob scannerB = new AiTaskCompensationJob(service, second, later);
            for (int round = 0; round < 5; round++) {
                race(scannerA::runOnce, scannerB::runOnce);
            }
            assertThat(first.getLock(AiTaskCompensationJob.LOCK_KEY).isLocked())
                    .as("扫描结束后锁必须释放").isFalse();
        } finally {
            first.shutdown();
            second.shutdown();
        }

        assertThat(epoch(jdbc, job.id())).as("只被推进一次").isOne();
        assertThat(status(jdbc, job.id())).isEqualTo("QUEUED");
        assertThat(streamEntries(redis, job.id())).extracting(fields -> fields.get("epoch"))
                .containsExactly("0", "1");
    }

    @Test
    void scannersRacingWithoutTheLockStillAdvanceEachTaskOnce() throws Exception {
        List<UUID> tasks = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            AiJobRecord job = service.create(project, "{}", user);
            service.claim(job.id(), AiJobService.TASK_TYPE_MEMORY_EXTRACTION, 0L);
            tasks.add(job.id());
        }
        OffsetDateTime later = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(120);

        List<AiJobService.CompensationSummary> summaries =
                race(() -> service.compensate(later, 100), () -> service.compensate(later, 100));

        assertThat(summaries.stream().mapToInt(AiJobService.CompensationSummary::requeued).sum())
                .as("30 个任务合计只被收回 30 次").isEqualTo(30);
        for (UUID task : tasks) {
            assertThat(epoch(jdbc, task)).isOne();
            assertThat(status(jdbc, task)).isEqualTo("QUEUED");
            assertThat(streamEntries(redis, task)).extracting(fields -> fields.get("epoch"))
                    .containsExactly("0", "1");
        }
    }

    // ------------------------------------------------------------------

    @SafeVarargs
    private static <T> List<T> race(Callable<T>... calls) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(calls.length);
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results.stream().filter(Objects::nonNull).toList();
        } finally {
            pool.shutdownNow();
        }
    }

    private static DataRedisProperties redisProperties() {
        DataRedisProperties properties = new DataRedisProperties();
        properties.setHost(REDIS.getHost());
        properties.setPort(REDIS.getMappedPort(6379));
        return properties;
    }
}
