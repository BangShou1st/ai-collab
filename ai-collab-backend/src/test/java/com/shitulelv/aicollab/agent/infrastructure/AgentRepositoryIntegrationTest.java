package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentScheduleRepository;
import com.shitulelv.aicollab.agent.domain.policy.AgentApprovalPolicy;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AgentRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static AgentRepository repository;
    static AgentApprovalRepository approvals;
    static AgentScheduleRepository schedules;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        repository = new AgentRepository(jdbc, json);
        approvals = new AgentApprovalRepository(jdbc, json);
        schedules = new AgentScheduleRepository(jdbc, repository);
    }

    @BeforeEach
    void clearAgentFixtures() {
        jdbc.update("DELETE FROM agent_session");
    }

    @Test
    void sessionAndRunReadsRequireProjectScope() {
        Fixture fixture = fixture();
        var session = repository.createSession(
                fixture.project(), fixture.user(), "项目协作");
        var run = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "检查进度", false);

        assertThat(repository.findSession(fixture.project(), session.id())).isPresent();
        assertThat(repository.findSession(UUID.randomUUID(), session.id())).isEmpty();
        assertThat(repository.findRun(fixture.project(), run.id())).isPresent();
        assertThat(repository.findRun(UUID.randomUUID(), run.id())).isEmpty();
    }

    @Test
    void sessionRenameAndDeleteRequireCreatorScope() {
        Fixture fixture = fixture();
        UUID otherUser = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                otherUser, "other-" + otherUser.toString().substring(0, 8),
                "test-only-hash", "Other member");
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'MEMBER')",
                fixture.project(), otherUser);
        var session = repository.createSession(
                fixture.project(), fixture.user(), "原会话名称");

        assertThat(repository.renameSession(
                fixture.project(), session.id(), otherUser, "越权修改")).isEmpty();
        assertThat(repository.renameSession(
                fixture.project(), session.id(), fixture.user(), "浏览器验收"))
                .get()
                .extracting(value -> value.title())
                .isEqualTo("浏览器验收");
        assertThat(repository.deleteSession(
                fixture.project(), session.id(), otherUser)).isFalse();
        assertThat(repository.deleteSession(
                fixture.project(), session.id(), fixture.user())).isTrue();
        assertThat(repository.findSession(fixture.project(), session.id())).isEmpty();
    }

    @Test
    void concurrentWorkersClaimQueuedRunOnce() throws Exception {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "领取测试");
        var run = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "检查项目", false);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> repository.claimNext("worker-a", now, Duration.ofMinutes(1)));
            var second = executor.submit(() -> repository.claimNext("worker-b", now, Duration.ofMinutes(1)));
            long claimed = java.util.stream.Stream.of(first.get(), second.get())
                    .filter(java.util.Optional::isPresent)
                    .count();

            assertThat(claimed).isOne();
            assertThat(repository.findRun(fixture.project(), run.id()).orElseThrow().status())
                    .isEqualTo(AgentRunStatus.RUNNING);
        }
    }

    @Test
    void expiredRunningLeaseCanBeReclaimed() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "恢复测试");
        var run = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "恢复运行", false);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        repository.claimNext("crashed-worker", now, Duration.ofSeconds(1));

        assertThat(repository.claimNext(
                "recovery-worker", now.plusSeconds(2), Duration.ofMinutes(1)))
                .get()
                .extracting(claimed -> claimed.id())
                .isEqualTo(run.id());
    }

    @Test
    void finalDecisionPersistsMessageAndCompletesRunAtomically() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "完成测试");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "总结项目", false);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        repository.recordFinal(
                running,
                new ChatCompletionResult("{}", "fake", "model", 20, 5, 10),
                new AgentDecision.FinalAnswer(
                        "项目进展稳定", new ObjectMapper().createArrayNode(),
                        new ObjectMapper().createArrayNode()));

        assertThat(repository.findRun(fixture.project(), queued.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(repository.listMessages(fixture.project(), session.id(), 10))
                .extracting(message -> message.role() + ":" + message.content())
                .containsExactly("USER:总结项目", "ASSISTANT:项目进展稳定");
    }

    @Test
    void toolResultPersistsAndRequeuesRun() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "工具测试");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "列出任务", false);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        var arguments = new ObjectMapper().createObjectNode().put("limit", 5);

        repository.recordToolResult(
                running,
                new ChatCompletionResult("{}", "fake", "model", 20, 5, 10),
                new AgentDecision.CallTool("list_tasks", arguments, "需要任务事实"),
                new ObjectMapper().createObjectNode().putArray("items"));

        var requeued = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(requeued.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(requeued.toolCallsUsed()).isOne();
        assertThat(repository.listSteps(fixture.project(), queued.id()))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.toolName()).isEqualTo("list_tasks");
                    assertThat(step.output().path("items")).isEmpty();
                });
    }

    @Test
    void approvalAndScheduleArePersisted() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "advanced");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "propose", false);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        var arguments = new ObjectMapper().createObjectNode().put("title", "approved task");
        UUID approvalId = UUID.randomUUID();
        AgentApprovalPolicy policy = new AgentApprovalPolicy();
        var approval = approvals.createProposal(
                approvalId, running,
                new ChatCompletionResult("{}", "fake", "model", 10, 5, 5),
                new AgentDecision.CallTool("create_task_after_approval", arguments, "proposal"),
                arguments, new ObjectMapper().createObjectNode().put("operation", "CREATE"),
                policy.nonceHash(arguments.toString()),
                policy.nonceHash(approvalId.toString()), OffsetDateTime.now().plusHours(1));

        assertThat(approval.status()).isEqualTo("PENDING");
        assertThat(repository.findRun(fixture.project(), queued.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.WAITING_FOR_APPROVAL);
        assertThat(approvals.matchesNonceHash(
                fixture.project(), approvalId, policy.nonceHash(approvalId.toString()))).isTrue();

        var scheduled = schedules.create(
                fixture.project(), fixture.user(), session.id(), "daily", "report",
                "DAILY", "Asia/Shanghai", java.time.LocalTime.of(9, 0), null,
                OffsetDateTime.now().minusMinutes(1));
        assertThat(schedules.fire(scheduled, OffsetDateTime.now().plusDays(1))).isPresent();
    }

    private static Fixture fixture() {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "repo-" + user.toString().substring(0, 8), "test-only-hash", "Repository");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "Agent repository", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        return new Fixture(user, project);
    }

    private record Fixture(UUID user, UUID project) {
    }
}
