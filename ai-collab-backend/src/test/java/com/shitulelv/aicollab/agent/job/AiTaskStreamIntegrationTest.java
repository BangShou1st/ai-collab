package com.shitulelv.aicollab.agent.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.internal.ai.InternalAiTokenFilter;
import com.shitulelv.aicollab.internal.ai.task.InternalAiTaskController;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenVerifier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.shitulelv.aicollab.agent.job.AiTaskTestFixtures.epoch;
import static com.shitulelv.aicollab.agent.job.AiTaskTestFixtures.status;
import static com.shitulelv.aicollab.agent.job.AiTaskTestFixtures.streamEntries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Stream 派发 + 任务回调真库测试（契约 §4.1–§4.3、R1 §9.2/§9.3、§6；Testcontainers PostgreSQL 17 + pgvector、Redis 7）。
 *
 * <p>Spring 事务与事件都是真的：{@code AiJobService} 经 {@code @EnableTransactionManagement} 代理，
 * {@code AiTaskStreamPublisher} 是真正的 {@code @TransactionalEventListener(AFTER_COMMIT)}；HTTP 面走真实的
 * {@code InternalAiTokenFilter} + {@code InternalAiTaskController}（Ed25519 任务令牌）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class AiTaskStreamIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = AiTaskTestFixtures.postgres();

    @Container
    static final GenericContainer<?> REDIS = AiTaskTestFixtures.redis();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final KeyPair KEYS = keyPair();

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static StringRedisTemplate redis;
    private static AiJobService service;
    private static AiTaskStreamPublisher publisher;
    private static InternalTokenVerifier verifier;
    private static UUID user;
    private static UUID project;

    @BeforeAll
    static void setUp() {
        DataSource dataSource = AiTaskTestFixtures.migratedDataSource(POSTGRES);
        LettuceConnectionFactory lettuce = AiTaskTestFixtures.lettuce(REDIS);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(RedisConnectionFactory.class, () -> lettuce);
        context.register(TaskTestConfiguration.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        redis = context.getBean(StringRedisTemplate.class);
        service = context.getBean(AiJobService.class);
        publisher = context.getBean(AiTaskStreamPublisher.class);
        verifier = new InternalTokenVerifier(Map.of("k1", KEYS.getPublic()), 60);
        user = AiTaskTestFixtures.user(jdbc, "stream");
        project = AiTaskTestFixtures.project(jdbc, user, "任务派发");
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    // ------------------------------------------------------------------
    // §4.2 生产端：AFTER_COMMIT 才 XADD
    // ------------------------------------------------------------------

    @Test
    void rolledBackTaskIsNeverDispatchedAndCommittedTaskIsDispatchedOnce() {
        TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        UUID[] rolledBack = new UUID[1];
        tx.executeWithoutResult(status -> {
            rolledBack[0] = service.create(project, "{}", user).id();
            assertThat(streamEntries(redis, rolledBack[0])).as("提交前不得 XADD").isEmpty();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE id=?", Integer.class, rolledBack[0]))
                .isZero();
        assertThat(streamEntries(redis, rolledBack[0])).isEmpty();

        AiJobRecord job = service.create(project, "{}", user);

        List<Map<Object, Object>> entries = streamEntries(redis, job.id());
        assertThat(entries).hasSize(1);
        Map<Object, Object> fields = entries.getFirst();
        assertThat(fields.keySet()).containsExactlyInAnyOrder("taskType", "taskId", "epoch", "token");
        assertThat(fields.get("taskType")).isEqualTo("MEMORY_EXTRACTION");
        assertThat(fields.get("epoch")).isEqualTo("0");
        String token = (String) fields.get("token");
        assertThat(verifier.verify(token, "backend", Set.of("task:" + job.id())).valid()).isTrue();
        JsonNode claims = payload(token);
        assertThat(claims.path("tid").asText()).isEqualTo(job.id().toString());
        assertThat(claims.path("ep").asLong()).isZero();
        assertThat(claims.path("uid").asText()).isEqualTo(user.toString());
        assertThat(claims.path("pid").asText()).isEqualTo(project.toString());
        assertThat(claims.path("scope").asText().split(" "))
                .contains("task:" + job.id(), "credentials:lease", "usage:report");
        assertThat(claims.path("purp").toString()).isEqualTo("[\"MEMORY_EXTRACTION\"]");
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isLessThanOrEqualTo(300);
        assertThat(redis.opsForStream().groups(AiTaskStreamPublisher.STREAM_KEY).stream()
                .anyMatch(group -> "ai-worker".equals(group.groupName()))).isTrue();
    }

    @Test
    void dispatchFailureIsCountedAndNeverThrows() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        dead.afterPropertiesSet();
        dead.start();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            AiTaskStreamPublisher broken = new AiTaskStreamPublisher(new StringRedisTemplate(dead),
                    context.getBean(InternalTokenIssuer.class), meters);
            AiJobRecord job = service.create(project, "{}", user);

            assertThat(broken.publish(job)).isFalse();
            assertThat(meters.counter(AiTaskStreamPublisher.METRIC_DISPATCH_FAILURES).count()).isEqualTo(1.0);
            assertThat(status(jdbc, job.id())).as("派发失败不回滚业务").isEqualTo("QUEUED");
        } finally {
            dead.destroy();
        }
    }

    // ------------------------------------------------------------------
    // §4.3 claim：20 线程并发只有 1 个成功
    // ------------------------------------------------------------------

    @Test
    void twentyConcurrentClaimsYieldExactlyOneWinner() throws Exception {
        AiJobRecord job = service.create(project, "{}", user);
        String token = publisher.taskToken(job);
        int threads = 20;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < threads; index++) {
                String workerId = "worker-" + index;
                futures.add(pool.submit(() -> {
                    MockMvc mvc = mvc();
                    barrier.await(10, TimeUnit.SECONDS);
                    return mvc.perform(post("/internal/ai/tasks/" + job.id() + "/claim")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(claimBody(0, workerId)))
                            .andReturn().getResponse().getStatus();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses).filteredOn(code -> code == 200).hasSize(1);
            assertThat(statuses).filteredOn(code -> code == 409).hasSize(threads - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(status(jdbc, job.id())).isEqualTo("LEASED");
        assertThat(epoch(jdbc, job.id())).as("claim 不改变 epoch").isZero();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM ai_job WHERE id=?", Integer.class, job.id()))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job_attempt WHERE job_id=?", Integer.class,
                job.id())).isOne();
    }

    // ------------------------------------------------------------------
    // §4.3 全链路 + 终态幂等
    // ------------------------------------------------------------------

    @Test
    void fullCallbackFlowAndTerminalReplaysAreIdempotent(CapturedOutput output) throws Exception {
        MockMvc mvc = mvc();
        AiJobRecord job = service.create(project, "{}", user);
        String streamToken = publisher.taskToken(job);

        JsonNode claim = json(mvc.perform(post(path(job, "claim")).header("Authorization", "Bearer " + streamToken)
                .contentType(MediaType.APPLICATION_JSON).content(claimBody(0, "worker-a"))).andReturn(), 200);
        String leaseToken = claim.path("leaseToken").asText();
        String token = claim.path("token").asText();
        assertThat(leaseToken).isNotBlank();
        assertThat(OffsetDateTime.parse(claim.path("leaseExpiresAt").asText()))
                .isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(50));
        assertThat(payload(token).path("ep").asLong()).isZero();

        JsonNode heartbeat = json(mvc.perform(post(path(job, "heartbeat")).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(leaseBody(0, leaseToken))).andReturn(), 200);
        assertThat(heartbeat.path("cancelRequested").asBoolean()).isFalse();
        assertThat(heartbeat.path("token").asText()).isNotBlank();
        assertThat(status(jdbc, job.id())).as("首次 heartbeat LEASED → RUNNING").isEqualTo("RUNNING");

        JsonNode snapshot = json(mvc.perform(get(path(job, "snapshot")).header("Authorization", "Bearer " + token))
                .andReturn(), 200);
        assertThat(snapshot.path("schemaVersion").asInt()).isOne();
        assertThat(snapshot.path("jobId").asText()).isEqualTo(job.id().toString());
        assertThat(snapshot.path("attempt").asInt()).isOne();
        assertThat(snapshot.path("leaseEpoch").asLong()).isZero();
        assertThat(snapshot.path("facts").get(0).path("ref").asText()).isEqualTo("fact:project");

        String result = resultBody(0, leaseToken, "fact:project");
        JsonNode first = json(mvc.perform(post(path(job, "result")).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(result)).andReturn(), 200);
        assertThat(first.path("status").asText()).isEqualTo("SUCCEEDED");
        int version = jdbc.queryForObject("SELECT version FROM ai_job WHERE id=?", Integer.class, job.id());

        JsonNode replay = json(mvc.perform(post(path(job, "result")).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(result)).andReturn(), 200);
        assertThat(replay.path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode failReplay = json(mvc.perform(post(path(job, "fail")).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(failBody(0, leaseToken, true))).andReturn(), 200);
        assertThat(failReplay.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT version FROM ai_job WHERE id=?", Integer.class, job.id()))
                .as("重复提交不改变任何状态").isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job_result WHERE job_id=?", Integer.class, job.id()))
                .isOne();
        assertThat(mvc.perform(post(path(job, "heartbeat")).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(leaseBody(0, leaseToken)))
                .andReturn().getResponse().getStatus()).as("终态后心跳 → 租约已失效").isEqualTo(409);

        assertThat(output.getAll()).doesNotContain(streamToken).doesNotContain(token)
                .doesNotContain(leaseToken).doesNotContain("eyJ");
    }

    @Test
    void staleEpochCallbacksAre409AndFreshEpochCanBeClaimed() throws Exception {
        MockMvc mvc = mvc();
        AiJobRecord job = service.create(project, "{}", user);
        String oldToken = publisher.taskToken(job);
        JsonNode claim = json(mvc.perform(post(path(job, "claim")).header("Authorization", "Bearer " + oldToken)
                .contentType(MediaType.APPLICATION_JSON).content(claimBody(0, "worker-a"))).andReturn(), 200);
        String oldLease = claim.path("leaseToken").asText();
        String leaseScopedToken = claim.path("token").asText();

        service.compensate(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(120), 100);
        assertThat(epoch(jdbc, job.id())).isOne();
        int version = jdbc.queryForObject("SELECT version FROM ai_job WHERE id=?", Integer.class, job.id());

        assertError(mvc.perform(post(path(job, "heartbeat")).header("Authorization", "Bearer " + leaseScopedToken)
                .contentType(MediaType.APPLICATION_JSON).content(leaseBody(0, oldLease))).andReturn(),
                409, "TASK_EPOCH_STALE");
        assertError(mvc.perform(post(path(job, "result")).header("Authorization", "Bearer " + leaseScopedToken)
                .contentType(MediaType.APPLICATION_JSON).content(resultBody(0, oldLease, "fact:project"))).andReturn(),
                409, "TASK_EPOCH_STALE");
        assertError(mvc.perform(post(path(job, "fail")).header("Authorization", "Bearer " + leaseScopedToken)
                .contentType(MediaType.APPLICATION_JSON).content(failBody(0, oldLease, true))).andReturn(),
                409, "TASK_EPOCH_STALE");
        assertError(mvc.perform(get(path(job, "snapshot")).header("Authorization", "Bearer " + leaseScopedToken))
                .andReturn(), 409, "TASK_EPOCH_STALE");
        assertError(mvc.perform(post(path(job, "claim")).header("Authorization", "Bearer " + oldToken)
                .contentType(MediaType.APPLICATION_JSON).content(claimBody(0, "worker-b"))).andReturn(),
                409, "TASK_CLAIM_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT version FROM ai_job WHERE id=?", Integer.class, job.id()))
                .as("旧 epoch 回调不改变任何状态").isEqualTo(version);

        AiJobRecord requeued = new AiJobRepository(jdbc).require(job.id());
        String freshToken = publisher.taskToken(requeued);
        // 新令牌 + 旧 leaseToken：仍然 409。
        assertError(mvc.perform(post(path(job, "result")).header("Authorization", "Bearer " + freshToken)
                .contentType(MediaType.APPLICATION_JSON).content(resultBody(1, oldLease, "fact:project"))).andReturn(),
                409, "TASK_EPOCH_STALE");
        JsonNode reclaim = json(mvc.perform(post(path(job, "claim")).header("Authorization", "Bearer " + freshToken)
                .contentType(MediaType.APPLICATION_JSON).content(claimBody(1, "worker-b"))).andReturn(), 200);
        assertThat(payload(reclaim.path("token").asText()).path("ep").asLong()).isOne();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM ai_job WHERE id=?", Integer.class, job.id()))
                .isEqualTo(2);
    }

    @Test
    void canceledTaskSignalsCancelOnHeartbeatAndFailIsIdempotent() throws Exception {
        MockMvc mvc = mvc();
        AiJobRecord job = service.create(project, "{}", user);
        JsonNode claim = json(mvc.perform(post(path(job, "claim"))
                .header("Authorization", "Bearer " + publisher.taskToken(job))
                .contentType(MediaType.APPLICATION_JSON).content(claimBody(0, "worker-a"))).andReturn(), 200);
        String leaseToken = claim.path("leaseToken").asText();
        String token = claim.path("token").asText();
        jdbc.update("UPDATE ai_job SET status='CANCELED', version=version+1 WHERE id=?", job.id());

        JsonNode heartbeat = json(mvc.perform(post(path(job, "heartbeat")).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(leaseBody(0, leaseToken))).andReturn(), 200);
        assertThat(heartbeat.path("cancelRequested").asBoolean()).isTrue();
        JsonNode fail = json(mvc.perform(post(path(job, "fail")).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(failBody(0, leaseToken, false))).andReturn(), 200);
        assertThat(fail.path("status").asText()).isEqualTo("CANCELED");
        assertThat(status(jdbc, job.id())).isEqualTo("CANCELED");
    }

    // ------------------------------------------------------------------

    private static MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new InternalAiTaskController(service, publisher))
                .addFilters(new InternalAiTokenFilter(verifier, JSON))
                .build();
    }

    private static String path(AiJobRecord job, String op) {
        return "/internal/ai/tasks/" + job.id() + "/" + op;
    }

    private static String claimBody(long epoch, String workerId) {
        return "{\"taskType\":\"MEMORY_EXTRACTION\",\"epoch\":" + epoch + ",\"workerId\":\"" + workerId + "\"}";
    }

    private static String leaseBody(long epoch, String leaseToken) {
        return "{\"epoch\":" + epoch + ",\"leaseToken\":\"" + leaseToken + "\"}";
    }

    private static String failBody(long epoch, String leaseToken, boolean retryable) {
        return "{\"epoch\":" + epoch + ",\"leaseToken\":\"" + leaseToken
                + "\",\"errorCode\":\"CANCELED\",\"retryable\":" + retryable + "}";
    }

    private static String resultBody(long epoch, String leaseToken, String sourceRef) {
        return """
                {"epoch":%d,"leaseToken":"%s","payload":{"schemaVersion":1,"candidates":[
                  {"kind":"DECISION","title":"发布窗口","content":"周五发布","confidence":0.8,
                   "sourceRef":"%s","dedupeKey":"%s"}]}}
                """.formatted(epoch, leaseToken, sourceRef, "d".repeat(64));
    }

    private static JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(expectedStatus);
        if (expectedStatus == 200 && result.getRequest().getRequestURI().matches(".*/(claim|heartbeat|snapshot)$")) {
            assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        }
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(json(result, status).path("code").asText()).isEqualTo(code);
    }

    private static JsonNode payload(String token) {
        try {
            return JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TaskTestConfiguration {

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        InternalTokenIssuer internalTokenIssuer() {
            return new InternalTokenIssuer(KEYS.getPrivate(), "k1", 300);
        }

        @Bean
        AiJobRepository aiJobRepository(JdbcTemplate jdbc) {
            return new AiJobRepository(jdbc);
        }

        @Bean
        AiTaskStreamPublisher aiTaskStreamPublisher(StringRedisTemplate redis, InternalTokenIssuer issuer,
                MeterRegistry meters) {
            return new AiTaskStreamPublisher(redis, issuer, meters);
        }

        @Bean
        AiJobService aiJobService(AiJobRepository repository, MeterRegistry meters,
                ApplicationEventPublisher events) {
            return new AiJobService(repository, new AiJobProperties(true, 3, 60), meters, new ObjectMapper(), events);
        }
    }
}
