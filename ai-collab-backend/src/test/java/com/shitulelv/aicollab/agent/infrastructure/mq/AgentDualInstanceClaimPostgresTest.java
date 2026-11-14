package com.shitulelv.aicollab.agent.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentRecoveryJob;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双实例并发 claim 竞争集成测试（M2 §3.1 第 3 行多实例正确性核查，真实 PostgreSQL）。
 *
 * <p>模拟两个后端实例：两个线程各持独立 workerId（模拟实例 A/B 的 embedded/mq-wake
 * worker）对同一批 QUEUED 运行并发调用 {@code AgentRecoveryJob.claim}——
 * {@code claimNext} 的 {@code FOR UPDATE SKIP LOCKED} 保证同一运行恰好被一个实例
 * 领取，其余实例空手而归；这正是 MQ 双轨制下"任一实例空闲即可承接"的数据库级保证，
 * 也是双实例验收（设计文档 §3.3）的自动化断言。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentDualInstanceClaimPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    static JdbcTemplate jdbc;
    static AgentRepository repository;
    static AgentRecoveryJob recovery;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var recorder = new AgentRunEventRecorder(jdbc, new ObjectMapper().findAndRegisterModules());
        repository = new AgentRepository(jdbc, new ObjectMapper().findAndRegisterModules(), recorder);
        recovery = new AgentRecoveryJob(repository);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM app_user");
    }

    /** 建一个用户/项目/会话，返回 projectId；随后按 sessionId 直插 QUEUED 运行。 */
    private UUID seedSession() {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, username, password_hash, display_name) VALUES (?, ?, ?, ?)",
                ownerId, "owner-" + ownerId.toString().substring(0, 8), "test-hash", "竞争测试用户");
        UUID projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id, name, owner_id, status, created_by) VALUES (?, ?, ?, 'PREPARING', ?)",
                projectId, "双实例竞争测试", ownerId, ownerId);
        jdbc.update("INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, 'OWNER')",
                projectId, ownerId);
        return projectId;
    }

    @Test
    void twoInstancesConcurrentlyClaimEachRunExactlyOnce() throws Exception {
        UUID projectId = seedSession();
        UUID sessionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_session(id,project_id,creator_id,title) VALUES (?,?,?,?)",
                sessionId, projectId, ownerIdOf(projectId), "竞争测试会话");
        int runCount = 12;
        List<UUID> runIds = new ArrayList<>();
        for (int i = 0; i < runCount; i++) {
            UUID runId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO agent_run(id,session_id,project_id,requester_id,goal,status)
                    SELECT ?,s.id,s.project_id,?,'竞争运行 %d','QUEUED'
                    FROM agent_session s WHERE s.id=?
                    """.formatted(i), runId, ownerIdOf(projectId), sessionId);
            runIds.add(runId);
        }

        // 两个"实例"同一点火，各自循环 claim 直到无运行可领
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger leaseFailures = new AtomicInteger();
        List<String> workerIds = List.of("instance-a-mq-wake", "instance-b-embedded");
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (String workerId : workerIds) {
                futures.add(pool.submit(() -> {
                    start.await();
                    int claimed = 0;
                    for (int i = 0; i < runCount * 2; i++) {
                        var claimedRun = recovery.claim(workerId, Duration.ofMinutes(6));
                        if (claimedRun.isEmpty()) {
                            break;
                        }
                        claimed++;
                        // 领取语义完整性：租约归属本 worker、状态 RUNNING、版本前移（CAS 生效）
                        ClaimedAgentRun run = claimedRun.get();
                        Integer claimedVersion = jdbc.queryForObject(
                                "SELECT claim_version FROM agent_run WHERE id=?", Integer.class, run.id());
                        String status = jdbc.queryForObject(
                                "SELECT status FROM agent_run WHERE id=?", String.class, run.id());
                        if (!"RUNNING".equals(status) || claimedVersion == null || claimedVersion < 1) {
                            leaseFailures.incrementAndGet();
                        }
                    }
                    return claimed;
                }));
            }
            start.countDown();
            int instanceAClaims = futures.get(0).get(60, TimeUnit.SECONDS);
            int instanceBClaims = futures.get(1).get(60, TimeUnit.SECONDS);

            // 关键断言：恰好每个运行被一个实例领取一次，总计 = 运行数，无一例双领/漏领
            assertThat(instanceAClaims + instanceBClaims).isEqualTo(runCount);
            assertThat(leaseFailures.get()).isZero();
            Integer runningCount = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_run WHERE status='RUNNING'", Integer.class);
            assertThat(runningCount).isEqualTo(runCount);
            Integer doubleClaimed = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_run WHERE claim_version > 1", Integer.class);
            assertThat(doubleClaimed).isZero();
            // 两个实例都参与了竞争（不允许单实例独吞全部领取——SKIP LOCKED 公平竞争）
            assertThat(instanceAClaims).isGreaterThanOrEqualTo(1);
            assertThat(instanceBClaims).isGreaterThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredLeaseTakeoverAcrossInstances() throws Exception {
        // 租约过期接管的两实例语义：实例 A 领取后"崩溃"（租约到期），实例 B 凭
        // RUNNING + 过期租约条件接管；接管前实例 B 的 claim 不得抢走活跃租约。
        UUID projectId = seedSession();
        UUID sessionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_session(id,project_id,creator_id,title) VALUES (?,?,?,?)",
                sessionId, projectId, ownerIdOf(projectId), "接管测试会话");
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_run(id,session_id,project_id,requester_id,goal,status)
                SELECT ?,s.id,s.project_id,?,'接管运行','QUEUED'
                FROM agent_session s WHERE s.id=?
                """, runId, ownerIdOf(projectId), sessionId);

        // 实例 A 领取（6 分钟租约）
        var firstClaim = repository.claimNext("instance-a", Duration.ofMinutes(6));
        assertThat(firstClaim).isPresent();
        // 实例 B 在租约活跃期内 claim：不得接管（RUNNING 且租约未过期）
        var duringLease = repository.claimNext("instance-b", Duration.ofMinutes(6));
        assertThat(duringLease).isEmpty();
        // 实例 B 在租约过期后 claim：接管成功（SKIP LOCKED + 租约过期条件的跨实例语义）
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '1 second' WHERE id=?", runId);
        var afterExpiry = repository.claimNext("instance-b", Duration.ofMinutes(6));
        assertThat(afterExpiry).isPresent();
        assertThat(afterExpiry.get().id()).isEqualTo(runId);
        String leaseOwner = jdbc.queryForObject(
                "SELECT lease_owner FROM agent_run WHERE id=?", String.class, runId);
        assertThat(leaseOwner).isEqualTo("instance-b");
    }

    private UUID ownerIdOf(UUID projectId) {
        return jdbc.queryForObject(
                "SELECT owner_id FROM project WHERE id=?", UUID.class, projectId);
    }
}
