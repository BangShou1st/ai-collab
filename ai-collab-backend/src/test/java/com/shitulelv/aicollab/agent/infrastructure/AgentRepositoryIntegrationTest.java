package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentCitation;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.domain.policy.AgentApprovalPolicy;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.domain.tool.ApprovalWriteAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class AgentRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static AgentRepository repository;
    static AgentApprovalRepository approvals;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        repository = new AgentRepository(jdbc, json);
        approvals = new AgentApprovalRepository(jdbc, json);
    }

    @BeforeEach
    void clearAgentFixtures() {
        // 按外键依赖顺序删除数据
        jdbc.update("DELETE FROM agent_approval_revision");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_run_event");
        jdbc.update("DELETE FROM agent_approval");
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
    }

    @Test
    void sessionAndRunReadsRequireProjectScope() {
        Fixture fixture = fixture();
        var session = repository.createSession(
                fixture.project(), fixture.user(), "项目协作");
        var run = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "检查进度", false, null, null);

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
    void sessionWithProposalRevisionCanBeDeleted() {
        Fixture fixture = fixture();
        var session = repository.createSession(
                fixture.project(), fixture.user(), "带提案修订的会话");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "创建任务", false,
                "ITERATION_PLANNING", null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        UUID approvalId = UUID.randomUUID();
        AgentApprovalPolicy policy = new AgentApprovalPolicy();
        var arguments = new ObjectMapper().createObjectNode().put("title", "修复登录白屏");
        approvals.createProposal(
                approvalId, running,
                new ChatCompletionResult("{}", "fake", "model", 10, 5, 5),
                new AgentDecision.CallTool(
                        "create_task_after_approval", arguments, "创建任务提案"),
                arguments, new ObjectMapper().createObjectNode().put("operation", "CREATE"),
                policy.nonceHash(arguments.toString()),
                policy.nonceHash(approvalId.toString()), OffsetDateTime.now().plusHours(1));
        approvals.recordRevision(
                fixture.project(), approvalId, running.id(), 2,
                arguments, arguments.deepCopy().put("priority", "HIGH"),
                new ObjectMapper().createObjectNode().put("priority", "HIGH"));

        assertThat(repository.deleteSession(fixture.project(), session.id(), fixture.user())).isTrue();
        assertThat(repository.findSession(fixture.project(), session.id())).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_approval_revision WHERE approval_id=?",
                Integer.class, approvalId)).isZero();
    }

    @Test
    void concurrentWorkersClaimQueuedRunOnce() throws Exception {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "领取测试");
        var run = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "检查项目", false, null, null);
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
                fixture.project(), session.id(), fixture.user(), "恢复运行", false, null, null);
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
                fixture.project(), session.id(), fixture.user(), "总结项目", false, null, null);
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
                fixture.project(), session.id(), fixture.user(), "列出任务", false, null, null);
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
    void approvalIsPersisted() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "advanced");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "propose", false, null, null);
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
        var afterProposal = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(afterProposal.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(afterProposal.stepsUsed()).isEqualTo(running.stepsUsed());
        assertThat(afterProposal.toolCallsUsed()).isEqualTo(running.toolCallsUsed());
        assertThat(approvals.matchesNonceHash(
                fixture.project(), approvalId, policy.nonceHash(approvalId.toString()))).isTrue();
    }

    @Test
    void proposalRunCanReturnSuccessWhileApprovalRemainsPending() {
        Fixture fixture = fixture();
        ObjectMapper json = new ObjectMapper();
        var session = repository.createSession(fixture.project(), fixture.user(), "proposal-success");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "创建任务", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        var arguments = json.createObjectNode().put("title", "修复登录页白屏");
        var modelCall = new ModelToolCall("call-1", "create_task_after_approval", arguments);
        var turn = new ModelTurnResult("", List.of(modelCall), ModelFinishReason.TOOL_CALLS,
                new ModelUsage(12, 6), "fake", "model", 8L);

        running = repository.recordModelTurn(running, turn);
        UUID approvalId = UUID.randomUUID();
        AgentApprovalPolicy policy = new AgentApprovalPolicy();
        approvals.createProposal(
                approvalId, running, new ChatCompletionResult("", "fake", "model", 12, 6, 8),
                new AgentDecision.CallTool(modelCall.name(), arguments, "proposal"),
                arguments, json.createObjectNode().put("operation", "CREATE"),
                policy.nonceHash(arguments.toString()), policy.nonceHash(approvalId.toString()),
                OffsetDateTime.now().plusHours(1));
        running = repository.recordToolResult(
                running, modelCall.name(), arguments,
                json.createObjectNode().put("status", "APPROVAL_CREATED"), false);
        repository.recordFinal(running, "任务提案已创建，等待审批。", List.of());

        assertThat(repository.findRun(fixture.project(), queued.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(approvals.find(fixture.project(), approvalId).orElseThrow().status())
                .isEqualTo("PENDING");
        assertThat(repository.listSteps(fixture.project(), queued.id()))
                .extracting(AgentStepView::type)
                .containsExactly(
                        AgentStepType.MODEL_TURN,
                        AgentStepType.APPROVAL_REQUESTED,
                        AgentStepType.TOOL_CALL_COMPLETED,
                        AgentStepType.FINAL_ANSWER);
    }

    // Issue 1: planUpdateThenFinalSucceeds
    @Test
    void planUpdateThenFinalSucceeds() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "version-consistency");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "检查项目", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        int initialVersion = running.version();

        // updatePlan increments version
        repository.updatePlan(fixture.project(), running.id(), running.version(),
                "{\"version\":1,\"objective\":\"test\",\"steps\":[]}");
        var afterPlan = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(afterPlan.version()).isEqualTo(initialVersion + 1);

        // recordFinal must use updated version (not stale initialVersion)
        repository.recordFinal(afterPlan, "项目进展稳定", List.of());
        var finalRun = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(finalRun.status()).isEqualTo(AgentRunStatus.SUCCEEDED);

        // answer stored as valid JSONB with Chinese content
        var steps = repository.listSteps(fixture.project(), queued.id());
        assertThat(steps).isNotEmpty();
        assertThat(steps.getLast().output()).isNotNull();

        // No orphan step - run is SUCCEEDED
        assertThat(finalRun.errorCode()).isNull();

        // Run should not be claimable again
        var claimedAgain = repository.claimNext("worker2",
                OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        assertThat(claimedAgain).isEmpty();
    }

    // Issue 1: planUpdateThenRequeueSucceeds
    @Test
    void planUpdateThenRequeueSucceeds() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "requeue-consistency");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "列出任务", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        int initialVersion = running.version();

        // updatePlan increments version
        repository.updatePlan(fixture.project(), running.id(), running.version(),
                "{\"version\":1,\"objective\":\"test\",\"steps\":[]}");
        var afterPlan = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(afterPlan.version()).isEqualTo(initialVersion + 1);

        // recordModelTurn on updated run
        repository.recordModelTurn(afterPlan, new ModelTurnResult(
                "calling tool", List.of(new ModelToolCall("tc1", "task.search",
                        new ObjectMapper().createObjectNode().put("overdueOnly", false).put("limit", 5))),
                ModelFinishReason.TOOL_CALLS,
                new ModelUsage(100, 50),
                "test-provider", "test-model", 200));
        var afterTurn = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        // recordToolResult
        repository.recordToolResult(afterTurn, "task.search",
                new ObjectMapper().createObjectNode().put("overdueOnly", false).put("limit", 5),
                new ObjectMapper().createObjectNode().putArray("items"), false);
        var afterTool = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        // requeueRun
        repository.requeueRun(afterTool);
        var requeued = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(requeued.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(requeued.version()).isGreaterThan(initialVersion);
    }

    // Issue 1: staleVersionRollsBackEverything
    @Test
    void staleVersionRollsBackEverything() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "stale-rollback");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "测试版本冲突", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        int version = running.version();

        // First updatePlan succeeds
        repository.updatePlan(fixture.project(), running.id(), version,
                "{\"version\":1,\"objective\":\"test\",\"steps\":[]}");
        var afterPlan = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        // recordModelTurn on correct version succeeds
        repository.recordModelTurn(afterPlan, new ModelTurnResult(
                "text", List.of(), ModelFinishReason.STOP,
                new ModelUsage(10, 5),
                "p", "m", 100));
        var afterTurn = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        int afterTurnVersion = afterTurn.version();

        // recordFinal with stale version (before updatePlan) must fail
        // Using the stale running object (version == initial version)
        try {
            repository.recordFinal(running, "should fail", List.of());
            // Should not reach here
            throw new AssertionError("recordFinal with stale version should have failed");
        } catch (IllegalStateException e) {
            // Expected: "Agent 运行已被其他 worker 修改"
        }

        // Run still SUCCEEDED from afterTurn's state? No, afterTurn is still RUNNING
        var afterFinalAttempt = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(afterFinalAttempt.status()).isEqualTo(AgentRunStatus.RUNNING);

        // No orphan steps or messages from failed attempt
        var messages = repository.listMessages(fixture.project(), session.id(), 100);
        assertThat(messages).extracting(m -> m.role()).doesNotContain("ASSISTANT");
    }

    // Issue 1: finalAnswerIsValidJsonb
    @Test
    void finalAnswerIsValidJsonb() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "jsonb-validation");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "回答问题", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        // Save answer with special characters
        String chineseAnswer = "项目已完成 50%。包含\"双引号\"、\\反斜杠、\n换行和\t制表符。";
        repository.recordFinal(running, chineseAnswer, List.of());

        // Read back and verify
        var finalRun = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(finalRun.status()).isEqualTo(AgentRunStatus.SUCCEEDED);

        var steps = repository.listSteps(fixture.project(), queued.id());
        assertThat(steps).isNotEmpty();
        AgentStepView finalStep = steps.getLast();
        assertThat(finalStep.type()).isEqualTo(AgentStepType.FINAL_ANSWER);
        assertThat(finalStep.output()).isNotNull();
        // Output is a JSON object {"answer":"...","citations":[],"inferences":[]}
        assertThat(finalStep.output().get("answer").asText()).isEqualTo(chineseAnswer);

        // Message should also contain the answer
        var messages = repository.listMessages(fixture.project(), session.id(), 100);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).content()).isEqualTo(chineseAnswer);
    }

    // Issue 2: modelTurnPersistsTokenUsage
    @Test
    void modelTurnPersistsTokenUsage() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "budget-test");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "预算测试", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        assertThat(running.inputTokensUsed()).isZero();
        assertThat(running.outputTokensUsed()).isZero();
        assertThat(running.stepsUsed()).isZero();

        // Record model turn with token usage
        repository.recordModelTurn(running, new ModelTurnResult(
                "thinking", List.of(), ModelFinishReason.STOP,
                new ModelUsage(150, 75),
                "test-provider", "test-model", 300));

        var afterTurn = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(afterTurn.inputTokensUsed()).isEqualTo(150);
        assertThat(afterTurn.outputTokensUsed()).isEqualTo(75);
        assertThat(afterTurn.stepsUsed()).isEqualTo(1);
        assertThat(afterTurn.tokenUsageEstimated()).isFalse();
    }

    // Issue 2: budgetAccumulatesAcrossSteps
    @Test
    void budgetAccumulatesAcrossSteps() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "budget-accum");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "累计测试", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        // First model turn
        repository.recordModelTurn(running, new ModelTurnResult(
                "t1", List.of(new ModelToolCall("tc1", "task.search",
                        new ObjectMapper().createObjectNode().put("overdueOnly", false).put("limit", 5))),
                ModelFinishReason.TOOL_CALLS,
                new ModelUsage(100, 50),
                "p", "m", 200));
        var run1 = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(run1.inputTokensUsed()).isEqualTo(100);
        assertThat(run1.outputTokensUsed()).isEqualTo(50);
        assertThat(run1.stepsUsed()).isEqualTo(1);

        // Record tool result
        repository.recordToolResult(run1, "task.search",
                new ObjectMapper().createObjectNode(), new ObjectMapper().createObjectNode(), false);
        var run2 = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(run2.toolCallsUsed()).isEqualTo(1);
        assertThat(run2.stepsUsed()).isEqualTo(2);

        // Requeue (status is RUNNING after recordToolResult, so requeueRun works)
        repository.requeueRun(run2);
        var run3 = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(run3.status()).isEqualTo(AgentRunStatus.QUEUED);

        // Claim again (simulating next tick picking up the QUEUED run)
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var run4 = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(run4.status()).isEqualTo(AgentRunStatus.RUNNING);

        // Second model turn (simulating next tick)
        repository.recordModelTurn(run4, new ModelTurnResult(
                "final answer", List.of(), ModelFinishReason.STOP,
                new ModelUsage(200, 100),
                "p", "m", 300));
        var run5 = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(run5.inputTokensUsed()).isEqualTo(300); // 100 + 200
        assertThat(run5.outputTokensUsed()).isEqualTo(150); // 50 + 100
        assertThat(run5.stepsUsed()).isEqualTo(3); // 1 + 1 + 1
    }

    // Issue 2: toolCallsAccumulateAcrossTicks
    @Test
    void toolCallsAccumulateAcrossTicks() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "tool-budget");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "工具预算", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        assertThat(running.toolCallsUsed()).isZero();

        // First tool result
        repository.recordToolResult(running, "task.search",
                new ObjectMapper().createObjectNode(), new ObjectMapper().createObjectNode(), false);
        var run1 = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(run1.toolCallsUsed()).isEqualTo(1);
        assertThat(run1.stepsUsed()).isEqualTo(1);

        // Second tool result
        repository.recordToolResult(run1, "milestone.list",
                new ObjectMapper().createObjectNode(), new ObjectMapper().createObjectNode(), false);
        var run2 = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(run2.toolCallsUsed()).isEqualTo(2);
        assertThat(run2.stepsUsed()).isEqualTo(2);
    }

    // Issue 2: invalidToolCallStillConsumesToolBudget
    @Test
    void invalidToolCallStillConsumesToolBudget() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "invalid-tool-budget");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "非法工具预算", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        // Error tool result (invalid tool call)
        repository.recordToolResult(running, "unknown_tool",
                new ObjectMapper().createObjectNode(),
                new ObjectMapper().createObjectNode().put("error", "TOOL_NOT_FOUND"), true);
        var run1 = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        // Even invalid tool calls should consume tool_calls_used budget
        assertThat(run1.toolCallsUsed()).isEqualTo(1);
        assertThat(run1.stepsUsed()).isEqualTo(1);
    }

    // Issue 2: budgetExceededRunCannotBeClaimedAgain
    @Test
    void budgetExceededRunCannotBeClaimedAgain() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "budget-exceeded");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "预算超限", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        // Mark as budget exceeded
        repository.recordBudgetExceeded(running);
        var exceeded = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(exceeded.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);

        // Try to claim again - should not be claimable
        var claimedAgain = repository.claimNext("worker2",
                OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        assertThat(claimedAgain).isEmpty();
    }

    // Issue 2: canceledRunCannotBeClaimedAgain
    @Test
    void canceledRunCannotBeClaimedAgain() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "canceled-claim");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "取消测试", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        // Mark as canceled
        repository.recordCanceled(running);
        var canceled = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(canceled.status()).isEqualTo(AgentRunStatus.CANCELED);

        // Try to claim again - should not be claimable
        var claimedAgain = repository.claimNext("worker2",
                OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        assertThat(claimedAgain).isEmpty();
    }

    @Test
    void runningCancelCanFinalizeWithClaimedVersionAfterRequestIncrementedVersion() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "cancel-version-race");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "取消版本竞争", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var claimedSnapshot = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        assertThat(repository.requestCancel(fixture.project(), queued.id()))
                .isEqualTo(AgentRunStatus.RUNNING);

        repository.recordCanceled(claimedSnapshot);

        var canceled = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(canceled.status()).isEqualTo(AgentRunStatus.CANCELED);
        int versionAfterCancellation = canceled.version();
        repository.recordCanceled(repository.findRun(fixture.project(), queued.id()).orElseThrow());
        assertThat(repository.findRun(fixture.project(), queued.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.CANCELED);
        assertThat(repository.findRun(fixture.project(), queued.id()).orElseThrow().version())
                .isEqualTo(versionAfterCancellation);
    }

    @Test
    void retryableFailureCanBeCanceledWithoutAnotherWorkerClaim() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "cancel-retryable");
        var run = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "取消待重试运行", false, null, null);
        jdbc.update("UPDATE agent_run SET status='FAILED_RETRYABLE' WHERE id=?", run.id());

        assertThat(repository.requestCancel(fixture.project(), run.id()))
                .isEqualTo(AgentRunStatus.CANCELED);
        assertThat(repository.findRun(fixture.project(), run.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.CANCELED);
    }

    @Test
    void canceledWaitingRunCannotExecuteItsPendingApproval() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "cancel-approval-race");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "取消审批竞争", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        UUID approvalId = UUID.randomUUID();
        AgentApprovalPolicy policy = new AgentApprovalPolicy();
        var arguments = new ObjectMapper().createObjectNode().put("title", "must-not-write");
        approvals.createProposal(
                approvalId, running,
                new ChatCompletionResult("{}", "fake", "model", 10, 5, 5),
                new AgentDecision.CallTool("write_after_approval", arguments, "proposal"),
                arguments, new ObjectMapper().createObjectNode().put("operation", "CREATE"),
                policy.nonceHash(arguments.toString()),
                policy.nonceHash(approvalId.toString()), OffsetDateTime.now().plusHours(1));
        assertThat(repository.requestCancel(fixture.project(), queued.id()))
                .isEqualTo(AgentRunStatus.RUNNING);
        repository.recordCanceled(repository.findRun(fixture.project(), queued.id()).orElseThrow());
        assertThat(repository.findRun(fixture.project(), queued.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.CANCELED);

        AtomicInteger writes = new AtomicInteger();
        ApprovalWriteAgentTool tool = new ApprovalWriteAgentTool() {
            @Override public String name() { return "write_after_approval"; }
            @Override public boolean writesBusinessData() { return true; }
            @Override public com.fasterxml.jackson.databind.JsonNode normalize(
                    AgentToolContext context, com.fasterxml.jackson.databind.JsonNode value) { return value; }
            @Override public com.fasterxml.jackson.databind.JsonNode diff(
                    AgentToolContext context, com.fasterxml.jackson.databind.JsonNode value) { return value; }
            @Override public void revalidate(
                    AgentToolContext context, com.fasterxml.jackson.databind.JsonNode value) { }
            @Override public AgentToolResult execute(
                    AgentToolContext context, com.fasterxml.jackson.databind.JsonNode value) {
                writes.incrementAndGet();
                return new AgentToolResult(value, List.of(), List.of());
            }
        };
        var access = mock(com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard.class);
        var service = new AgentApprovalService(
                approvals, new AgentToolRegistry(List.of(tool)), access,
                new ObjectMapper(), java.time.Clock.systemUTC(), mock(AgentEventService.class));

        assertThatThrownBy(() -> service.approve(
                fixture.project(), approvalId, fixture.user(), approvalId.toString(), UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.AGENT_RUN_CANCELED));
        assertThat(writes).hasValue(0);
        assertThat(approvals.find(fixture.project(), approvalId).orElseThrow().status())
                .isEqualTo("PENDING");
    }

    // Issue 2: waitingForApprovalRunCannotBeClaimedAgain
    @Test
    void proposalCreationKeepsRunningLeaseUntilFinalResponse() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "waiting-approval");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "等待审批", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        // 创建提案不会暂停 Run；Runtime 随后还要写入工具结果和最终答复。
        UUID approvalId = UUID.randomUUID();
        AgentApprovalPolicy policy = new AgentApprovalPolicy();
        var arguments = new ObjectMapper().createObjectNode().put("title", "test task");
        approvals.createProposal(
                approvalId, running,
                new ChatCompletionResult("{}", "fake", "model", 10, 5, 5),
                new AgentDecision.CallTool("create_task_after_approval", arguments, "proposal"),
                arguments, new ObjectMapper().createObjectNode().put("operation", "CREATE"),
                policy.nonceHash(arguments.toString()),
                policy.nonceHash(approvalId.toString()), OffsetDateTime.now().plusHours(1));

        var stillRunning = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(stillRunning.status()).isEqualTo(AgentRunStatus.RUNNING);

        // 原 worker 的 lease 仍在，其他 worker 不得重复领取。
        var claimedAgain = repository.claimNext("worker2",
                OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        assertThat(claimedAgain).isEmpty();
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
