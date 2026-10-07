package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentCitation;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
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
    static org.springframework.transaction.support.TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        repository = new AgentRepository(jdbc, json, new AgentRunEventRecorder(jdbc, json));
        approvals = new AgentApprovalRepository(jdbc, json);
    }

    @BeforeEach
    void clearAgentFixtures() {
        // 按外键依赖顺序删除数据
        jdbc.update("DELETE FROM agent_approval_revision");
        jdbc.update("DELETE FROM agent_tool_invocation");
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
    void budgetPartialAnswerPreservesMessageWithoutSuccessOrWrites() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "预算未完成");
        var queued = repository.createRun(fixture.project(), session.id(), fixture.user(), "读取资料", false, null, null);
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        transactions.executeWithoutResult(tx -> repository.recordBudgetPartialAnswer(running, "预算不足；已读片段保留，未完成全文核查。"));
        assertThat(repository.findRun(fixture.project(), queued.id()).orElseThrow().status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(repository.listMessages(fixture.project(), session.id(), 10)).extracting(message -> message.role() + ":" + message.content())
                .containsExactly("USER:读取资料", "ASSISTANT:预算不足；已读片段保留，未完成全文核查。");
        assertThat(jdbc.queryForObject("select count(*) from agent_run_event where run_id=? and type='RUN_SUCCEEDED'", Integer.class, queued.id())).isZero();
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
        // SEPARATED 预算语义：工具结果只计工具额度，不再逐项占用推进步
        assertThat(run2.stepsUsed()).isEqualTo(1);

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
        assertThat(run5.stepsUsed()).isEqualTo(2); // 两个模型轮各计一次推进（工具结果不占推进步）
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
        // SEPARATED：工具结果不占推进步
        assertThat(run1.stepsUsed()).isZero();

        // Second tool result
        repository.recordToolResult(run1, "milestone.list",
                new ObjectMapper().createObjectNode(), new ObjectMapper().createObjectNode(), false);
        var run2 = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(run2.toolCallsUsed()).isEqualTo(2);
        assertThat(run2.stepsUsed()).isZero();
    }

    /** COMBINED 旧语义（既有运行默认）：工具结果仍逐项占用推进步，恢复/续跑保持旧记账不重解释。 */
    @Test
    void legacyCombinedSemanticsStillBumpsStepsOnToolResults() {
        Fixture fixture = fixture();
        var session = repository.createSession(fixture.project(), fixture.user(), "combined-legacy");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), "旧语义运行", false, null, null);
        // 模拟既有（历史）运行：budget_semantics 保持 COMBINED
        jdbc.update("UPDATE agent_run SET budget_semantics='COMBINED' WHERE id=?", queued.id());
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        var running = repository.findRun(fixture.project(), queued.id()).orElseThrow();

        repository.recordModelTurn(running, new ModelTurnResult(
                "t1", List.of(new ModelToolCall("tc1", "task.search",
                        new ObjectMapper().createObjectNode().put("limit", 5))),
                ModelFinishReason.TOOL_CALLS,
                new ModelUsage(100, 50), "p", "m", 200));
        var afterTurn = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(afterTurn.stepsUsed()).isEqualTo(1);

        repository.recordToolResult(afterTurn, "task.search",
                new ObjectMapper().createObjectNode(), new ObjectMapper().createObjectNode(), false);
        var afterTool = repository.findRun(fixture.project(), queued.id()).orElseThrow();
        assertThat(afterTool.toolCallsUsed()).isEqualTo(1);
        // COMBINED：工具结果仍占用推进步（旧含义不静默重解释）
        assertThat(afterTool.stepsUsed()).isEqualTo(2);
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
        // SEPARATED：非法工具调用也不占推进步
        assertThat(run1.stepsUsed()).isZero();
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

    @Test
    void refreshedOptimisticVersionCannotBypassOldClaimEpoch() {
        Fixture fixture=fixture();
        var session=repository.createSession(fixture.project(),fixture.user(),"fence");
        var queued=repository.createRun(fixture.project(),session.id(),fixture.user(),"synthetic",false,null,null);
        var first=repository.claimNext("first",OffsetDateTime.now(),Duration.ofMinutes(1)).orElseThrow();
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '1 second' WHERE id=?",queued.id());
        var replacement=repository.claimNext("second",OffsetDateTime.now(),Duration.ofMinutes(1)).orElseThrow();
        var refreshed=repository.findRun(fixture.project(),queued.id()).orElseThrow();
        try (var scope=new com.shitulelv.aicollab.agent.infrastructure.repository.AgentLeaseScope(first.version())) {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> repository.recordFinal(refreshed,"late",List.of())))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(repository.listSteps(fixture.project(),queued.id())).isEmpty();
        try (var scope=new com.shitulelv.aicollab.agent.infrastructure.repository.AgentLeaseScope(replacement.version())) {
            transactions.executeWithoutResult(status -> repository.recordFinal(refreshed,"owned",List.of()));
        }
        assertThat(repository.findRun(fixture.project(),queued.id()).orElseThrow().status()).isEqualTo(AgentRunStatus.SUCCEEDED);
    }

    @Test
    void durableTurnRestoresKnownResultsAndRejectsChangedArguments() {
        Fixture fixture=fixture();
        var session=repository.createSession(fixture.project(),fixture.user(),"recover");
        var queued=repository.createRun(fixture.project(),session.id(),fixture.user(),"synthetic",false,null,null);
        repository.claimNext("worker",OffsetDateTime.now(),Duration.ofMinutes(1));
        var run=repository.findRun(fixture.project(),queued.id()).orElseThrow();
        ObjectMapper json=new ObjectMapper();
        var call=new ModelToolCall("provider-call","list_tasks",json.createObjectNode());
        var turn=new ModelTurnResult("",List.of(call),ModelFinishReason.TOOL_CALLS,null,"test","test",1);
        var modeled=transactions.execute(status -> repository.recordModelTurn(run,turn));
        assertThat(repository.pendingModelTurn(modeled)).contains(turn);
        var input=json.createObjectNode().put("toolCallId",call.id()); input.set("arguments",call.arguments());
        var result=json.createObjectNode().put("status","SUCCEEDED").put("count",1);
        var completed=transactions.execute(status -> repository.recordToolResult(modeled,call.name(),input,result,false));
        assertThat(repository.knownInvocationResult(completed,call)).contains(result);
        assertThatThrownBy(() -> repository.knownInvocationResult(completed,new ModelToolCall(call.id(),call.name(),json.createObjectNode().put("changed",true))))
                .isInstanceOf(IllegalStateException.class);
        transactions.executeWithoutResult(status -> repository.requeueRun(completed));
        assertThat(repository.pendingModelTurn(completed)).isEmpty();
    }

    @Test
    void userSupplementPreservesGoalAndExplicitReplacementKeepsHistory() {
        Fixture fixture=fixture(); var session=repository.createSession(fixture.project(),fixture.user(),"context");
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"原目标",false,null,null));
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"补充截止日期",false,null,null));
        var state=repository.workingState(fixture.project(),session.id());
        assertThat(state.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(state.path("activeGoal").asText()).isEqualTo("原目标");
        // v2：补充消息不含可识别约束，不再积累进 constraints
        assertThat(state.path("constraints").size()).isZero();
        assertThat(state.path("stateRevision").asInt()).isEqualTo(2);
        // 显式替换：新目标生效，旧目标保留历史，stateRevision 继续递增
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"/replace 新目标",false,null,null));
        var replaced=repository.workingState(fixture.project(),session.id());
        assertThat(replaced.path("activeGoal").asText()).isEqualTo("/replace 新目标");
        assertThat(replaced.path("goalHistory").size()).isEqualTo(1);
        assertThat(replaced.path("goalHistory").get(0).path("goal").asText()).isEqualTo("原目标");
        assertThat(replaced.path("stateRevision").asInt()).isEqualTo(3);
    }

    @Test
    void recognizedPersistentConstraintsStayActiveAcrossOrdinaryMessages() {
        Fixture fixture=fixture(); var session=repository.createSession(fixture.project(),fixture.user(),"constraints");
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"整理项目任务，最多十项，不改日期",false,null,null));
        // 20 条普通问答之后，持续约束仍然有效且来源消息已关联
        for (int i=0;i<20;i++) {
            final int round=i;
            transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"第"+round+"轮：这个任务现在是什么状态？",false,null,null));
        }
        var state=repository.workingState(fixture.project(),session.id());
        JsonNode constraints=state.path("constraints");
        assertThat(constraints.size()).isEqualTo(2);
        assertThat(constraints.get(0).path("scope").asText()).isEqualTo("TASK_COUNT_MAX");
        assertThat(constraints.get(1).path("scope").asText()).isEqualTo("DATE_LOCK");
        for (JsonNode entry : constraints) {
            assertThat(entry.path("status").asText()).isEqualTo("active");
            assertThat(entry.hasNonNull("sourceMessageId")).isTrue();
        }
    }

    @Test
    void minAndMaxTaskCountConstraintsCoexistWithoutSupersedingEachOther() {
        Fixture fixture=fixture(); var session=repository.createSession(fixture.project(),fixture.user(),"min-max");
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"生成任务清单，至少三项",false,null,null));
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"最多十项",false,null,null));
        var state=repository.workingState(fixture.project(),session.id());
        JsonNode constraints=state.path("constraints");
        assertThat(constraints.size()).isEqualTo(2);
        assertThat(constraints.get(0).path("scope").asText()).isEqualTo("TASK_COUNT_MIN");
        assertThat(constraints.get(0).path("status").asText()).isEqualTo("active");
        assertThat(constraints.get(1).path("scope").asText()).isEqualTo("TASK_COUNT_MAX");
        assertThat(constraints.get(1).path("status").asText()).isEqualTo("active");
    }

    @Test
    void activeConstraintsSurviveConstraintChurnWithoutEviction() {
        Fixture fixture=fixture(); var session=repository.createSession(fixture.project(),fixture.user(),"churn");
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"整理任务，不改日期",false,null,null));
        // 反复修改数量上限 25 次：旧的日期约束不得被条数上限淘汰
        for (int i=1;i<=25;i++) {
            final int count=i;
            transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"改成最多"+count+"项",false,null,null));
        }
        var state=repository.workingState(fixture.project(),session.id());
        JsonNode constraints=state.path("constraints");
        boolean dateActive=false;
        String activeCountValue=null;
        for (JsonNode entry : constraints) {
            if ("DATE_LOCK".equals(entry.path("scope").asText()) && "active".equals(entry.path("status").asText())) dateActive=true;
            if ("TASK_COUNT_MAX".equals(entry.path("scope").asText()) && "active".equals(entry.path("status").asText())) {
                activeCountValue=entry.path("value").asText();
            }
        }
        assertThat(dateActive).isTrue();
        // 只有最后一条数量约束仍是 active，其余被替代（历史保留可追溯）
        assertThat(activeCountValue).isEqualTo("最多25项");
        int supersededCount=0;
        for (JsonNode entry : constraints) {
            if ("superseded".equals(entry.path("status").asText())) supersededCount++;
        }
        // 超限归档只删 superseded 历史条目，active 不受限
        assertThat(supersededCount).isLessThanOrEqualTo(20);
    }

    @Test
    void constraintUpdateSupersedesOldEntryWithinSameScope() {
        Fixture fixture=fixture(); var session=repository.createSession(fixture.project(),fixture.user(),"constraint-update");
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"生成任务清单，最多十项",false,null,null));
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"改成最多八项",false,null,null));
        var state=repository.workingState(fixture.project(),session.id());
        JsonNode constraints=state.path("constraints");
        assertThat(constraints.size()).isEqualTo(2);
        assertThat(constraints.get(0).path("status").asText()).isEqualTo("superseded");
        assertThat(constraints.get(0).path("supersededReason").asText()).isEqualTo("SCOPE_UPDATED");
        String supersededBy=constraints.get(0).path("supersededBy").asText();
        assertThat(constraints.get(1).path("status").asText()).isEqualTo("active");
        assertThat(constraints.get(1).path("id").asText()).isEqualTo(supersededBy);
    }

    @Test
    void ordinaryQuestionsAndTurnRequirementsDoNotAccumulateAsConstraints() {
        Fixture fixture=fixture(); var session=repository.createSession(fixture.project(),fixture.user(),"no-accumulate");
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"谢谢",false,null,null));
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"解释详细一点",false,null,null));
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"这个任务完成了吗？",false,null,null));
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"只回答标题和状态",false,null,null));
        var state=repository.workingState(fixture.project(),session.id());
        assertThat(state.path("constraints").size()).isZero();
        // 本轮表达要求当轮生效、下次请求重算，不跨轮累积
        assertThat(state.path("turnRequirements").size()).isEqualTo(1);
        assertThat(state.path("turnRequirements").get(0).asText()).isEqualTo("只回答标题和状态");
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"继续查详情",false,null,null));
        assertThat(repository.workingState(fixture.project(),session.id()).path("turnRequirements").size()).isZero();
    }

    @Test
    void legacyWorkingStateIsUpgradedOnWriteWithoutLosingData() {
        Fixture fixture=fixture(); var session=repository.createSession(fixture.project(),fixture.user(),"legacy-upgrade");
        // 直接写入 v1 旧格式
        jdbc.update("UPDATE agent_session SET working_state=?::jsonb WHERE id=?",
                "{\"goal\":\"旧目标\",\"constraints\":[\"旧约束一\",\"旧约束二\"],\"latestRequest\":\"旧请求\",\"goalVersion\":3}", session.id());
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"新请求",false,null,null));
        var state=repository.workingState(fixture.project(),session.id());
        assertThat(state.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(state.path("activeGoal").asText()).isEqualTo("旧目标");
        assertThat(state.path("constraints").size()).isEqualTo(2);
        assertThat(state.path("constraints").get(0).path("value").asText()).isEqualTo("旧约束一");
        assertThat(state.path("constraints").get(0).path("status").asText()).isEqualTo("active");
        assertThat(state.path("latestRequest").asText()).isEqualTo("新请求");
        assertThat(state.path("stateRevision").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void appendUserAssociatesRealMessageIdWithinTransaction() {
        Fixture fixture=fixture(); var session=repository.createSession(fixture.project(),fixture.user(),"message-id");
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"查任务，不改日期",false,null,null));
        var state=repository.workingState(fixture.project(),session.id());
        String sourceMessageId=state.path("constraints").get(0).path("sourceMessageId").asText();
        Integer count=jdbc.queryForObject(
                "SELECT count(*) FROM agent_message WHERE id=?::uuid AND role='USER'",
                Integer.class, sourceMessageId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void protectiveConstraintsForDifferentObjectsCoexistInsteadOfOverwriting() {
        Fixture fixture=fixture(); var session=repository.createSession(fixture.project(),fixture.user(),"object-scope");
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"任务A的日期不变，不要改",false,null,null));
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"任务B的日期也不变，别改日期",false,null,null));
        var state=repository.workingState(fixture.project(),session.id());
        JsonNode constraints=state.path("constraints");
        assertThat(constraints.size()).isEqualTo(2);
        for (JsonNode entry : constraints) {
            assertThat(entry.path("scope").asText()).isEqualTo("DATE_LOCK");
            // 对象无法确定性区分：不同表述并存，前一条不得因类型相同被替代
            assertThat(entry.path("status").asText()).isEqualTo("active");
        }
        // 负责人保护同理：不同对象并存
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"任务A负责人保持不变",false,null,null));
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"任务B负责人不要改",false,null,null));
        var updated=repository.workingState(fixture.project(),session.id());
        int activeAssigneeLocks=0;
        for (JsonNode entry : updated.path("constraints")) {
            if ("ASSIGNEE_LOCK".equals(entry.path("scope").asText()) && "active".equals(entry.path("status").asText())) activeAssigneeLocks++;
        }
        assertThat(activeAssigneeLocks).isEqualTo(2);
        // 日期保护条目依然全部 active
        for (JsonNode entry : updated.path("constraints")) {
            if ("DATE_LOCK".equals(entry.path("scope").asText())) {
                assertThat(entry.path("status").asText()).isEqualTo("active");
            }
        }
    }

    @Test
    void summaryAttemptSettlesTokensToOwningRunExactlyOnce() {
        Fixture fixture=fixture(); var session=repository.createSession(fixture.project(),fixture.user(),"summary-settle");
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"查任务，不改日期",false,null,null));
        var run=repository.findRun(fixture.project(), jdbc.queryForObject(
                "SELECT id FROM agent_run WHERE session_id=? ORDER BY created_at LIMIT 1", UUID.class, session.id())).orElseThrow();
        // 摘要出站请求有持久化准入边界（暂停/状态/租约）：与生产一致置于 RUNNING 状态
        jdbc.update("UPDATE agent_run SET status='RUNNING' WHERE id=?", run.id());

        // 开始尝试：持久化标记落库，运行数值尚未变化
        UUID attemptId=repository.beginSummaryAttempt(run);
        assertThat(jdbc.queryForObject("SELECT output_json->>'status' FROM agent_step WHERE id=?", String.class, attemptId))
                .isEqualTo("ATTEMPTED");
        assertThat(jdbc.queryForObject("SELECT input_tokens_used FROM agent_run WHERE id=?", Integer.class, run.id())).isZero();

        // 完成尝试：token 计入所属运行（attemptId 是步骤 ID，运行 ID 从步骤取回）
        repository.completeSummaryAttempt(attemptId,"COMMITTED","test-model",
                new AgentRunEventRecorder.UsageSettlement(500,60,
                        AgentRunEventRecorder.UsageSettlement.PROVIDER,
                        AgentRunEventRecorder.UsageSettlement.PROVIDER,10L),null);
        assertThat(jdbc.queryForObject("SELECT output_json->>'status' FROM agent_step WHERE id=?", String.class, attemptId))
                .isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject("SELECT prompt_tokens FROM agent_step WHERE id=?", Integer.class, attemptId)).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT completion_tokens FROM agent_step WHERE id=?", Integer.class, attemptId)).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT input_tokens_used FROM agent_run WHERE id=?", Integer.class, run.id())).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT output_tokens_used FROM agent_run WHERE id=?", Integer.class, run.id())).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT token_usage_estimated FROM agent_run WHERE id=?", Boolean.class, run.id())).isFalse();

        // 重复完成不得重复扣费：步骤已离开 ATTEMPTED，第二次调用不产生任何变化
        repository.completeSummaryAttempt(attemptId,"COMMITTED","test-model",
                new AgentRunEventRecorder.UsageSettlement(999,999,
                        AgentRunEventRecorder.UsageSettlement.PROVIDER,
                        AgentRunEventRecorder.UsageSettlement.PROVIDER,20L),null);
        assertThat(jdbc.queryForObject("SELECT input_tokens_used FROM agent_run WHERE id=?", Integer.class, run.id())).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT output_tokens_used FROM agent_run WHERE id=?", Integer.class, run.id())).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT prompt_tokens FROM agent_step WHERE id=?", Integer.class, attemptId)).isEqualTo(500);
    }

    @Test
    void summaryCasCommitOnlyUpdatesSummaryNodeAndRejectsStaleRevision() {
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        Fixture fixture=fixture(); var session=repository.createSession(fixture.project(),fixture.user(),"summary-cas");
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"查任务，不改日期",false,null,null));
        var before=repository.workingState(fixture.project(),session.id());
        int revision=before.path("stateRevision").asInt();
        int goalRevision=before.path("goalRevision").asInt();

        var summary=json.createObjectNode();
        summary.put("schemaVersion",1);
        summary.put("text","早期对话摘要：用户要求不改日期。");
        summary.put("sourceFrom",UUID.randomUUID().toString());
        summary.put("sourceThrough",before.path("lastProcessedMessageId").asText());
        boolean committed=transactions.execute(status ->
                repository.commitConversationSummary(fixture.project(),session.id(),revision,goalRevision,summary));
        assertThat(committed).isTrue();

        var after=repository.workingState(fixture.project(),session.id());
        // 只写 summary 节点 + 原子递增 stateRevision：activeGoal/constraints 原样保留
        assertThat(after.path("summary").path("text").asText()).contains("不改日期");
        assertThat(after.path("activeGoal").asText()).isEqualTo(before.path("activeGoal").asText());
        assertThat(after.path("stateRevision").asInt()).isEqualTo(revision+1);
        assertThat(after.path("constraints").size()).isEqualTo(before.path("constraints").size());

        // 同一状态上的第二次摘要提交（revision 未随之匹配）必须失败
        var duplicate=json.createObjectNode();
        duplicate.put("schemaVersion",1);
        duplicate.put("text","并发期间的另一份摘要");
        duplicate.put("sourceThrough",before.path("lastProcessedMessageId").asText());
        boolean duplicateCommitted=transactions.execute(status ->
                repository.commitConversationSummary(fixture.project(),session.id(),revision,goalRevision,duplicate));
        assertThat(duplicateCommitted).isFalse();

        // 声明的覆盖边界不属于本会话消息时，提交必须失败
        var forged=json.createObjectNode();
        forged.put("schemaVersion",1);
        forged.put("text","边界不存在的摘要");
        forged.put("sourceThrough",UUID.randomUUID().toString());
        boolean forgedCommitted=transactions.execute(status ->
                repository.commitConversationSummary(fixture.project(),session.id(),revision,goalRevision,forged));
        assertThat(forgedCommitted).isFalse();

        // revision 前进（新请求）后，旧 revision 的摘要提交必须失败
        transactions.executeWithoutResult(status -> repository.createRun(fixture.project(),session.id(),fixture.user(),"继续",false,null,null));
        var stale=json.createObjectNode();
        stale.put("schemaVersion",1);
        stale.put("text","过期摘要");
        boolean staleCommitted=transactions.execute(status ->
                repository.commitConversationSummary(fixture.project(),session.id(),revision,goalRevision,stale));
        assertThat(staleCommitted).isFalse();
        assertThat(repository.workingState(fixture.project(),session.id()).path("summary").path("text").asText())
                .contains("不改日期");
    }

    @Test
    void summaryResumesBeyondSixtySegmentsAndRecentWindowAfterDatabaseReload() {
        var f=fixture(); var session=repository.createSession(f.project(),f.user(),"summary-long");
        var run=repository.createRun(f.project(),session.id(),f.user(),"当前需求",false,null,null);
        // 摘要出站请求有持久化准入边界（暂停/状态/租约）：与生产一致置于 RUNNING 状态
        jdbc.update("UPDATE agent_run SET status='RUNNING' WHERE id=?", run.id());
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        UUID old=UUID.randomUUID();
        jdbc.update("INSERT INTO agent_message(id,session_id,run_id,role,content,created_at) VALUES (?,?,?,'USER',?,now()-interval '2 days')",old,session.id(),run.id(),"甲".repeat(3000)+"尾部约束");
        for (int i=0;i<45;i++) jdbc.update("INSERT INTO agent_message(session_id,run_id,role,content,created_at) VALUES (?,?,'ASSISTANT',?,now()-interval '1 day'+?*interval '1 second')",session.id(),run.id(),"近期记录"+i,i);
        var state=(com.fasterxml.jackson.databind.node.ObjectNode)repository.workingState(f.project(),session.id());
        var summary=state.putObject("summary"); summary.put("text","既有摘要");
        var segments=summary.putArray("segments");
        for(int i=0;i<60;i++) segments.addObject().put("messageId",old.toString()).put("from",i*10).put("to",(i+1)*10);
        summary.putArray("uncoveredMessageIds").add(old.toString());
        jdbc.update("UPDATE agent_session SET working_state=?::jsonb WHERE id=?",state.toString(),session.id());
        var executor=mock(com.shitulelv.aicollab.agent.application.runtime.RoutingAgentModelExecutor.class);
        org.mockito.Mockito.when(executor.callModelWithoutTools(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ModelTurnResult("有效摘要",List.of(),ModelFinishReason.STOP,new ModelUsage(100,20),"test", "test",1L));
        var memories=mock(com.shitulelv.aicollab.agent.application.AgentMemoryService.class);
        var composer=new com.shitulelv.aicollab.agent.application.runtime.AgentModelMessageComposer(repository,memories,json);
        var skill=mock(com.shitulelv.aicollab.agent.domain.model.AgentSkill.class);
        org.mockito.Mockito.when(skill.instruction()).thenReturn("测试"); org.mockito.Mockito.when(skill.outputContract()).thenReturn("回答");
        var ctx=new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(run.id(),session.id(),f.project(),f.user(),"OWNER",false,com.shitulelv.aicollab.agent.domain.model.AgentPageContext.empty(),com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits.defaults(),0,List.of());
        var composition=composer.composeV2(run,ctx,skill,com.shitulelv.aicollab.agent.domain.model.AgentPlan.create("测试",List.of()),List.of(),3000, 1, false);
        assertThat(composition.failureReason()).isNull();
        assertThat(composition.summaryCandidates()).anyMatch(m->m.id().equals(old));
        new com.shitulelv.aicollab.agent.application.runtime.AgentContextSummarizer(repository,executor,json).maybeSummarize(run,composition,10000);
        var persisted=repository.workingState(f.project(),session.id()).path("summary");
        assertThat(persisted.path("segments")).anySatisfy(s->{ if(s.path("messageId").asText().equals(old.toString())) assertThat(s.path("to").asInt()).isEqualTo(1200); });
        var reloaded=new AgentRepository(jdbc,json,new AgentRunEventRecorder(jdbc,json));
        var next=reloaded.createRun(f.project(),session.id(),f.user(),"继续",false,null,null);
        jdbc.update("UPDATE agent_run SET status='RUNNING' WHERE id=?", next.id());
        var loaded=reloaded.listSummaryCandidates(next,java.util.Set.of(),20);
        var second=new com.shitulelv.aicollab.agent.application.runtime.AgentModelMessageComposer.Composition(List.of(),com.shitulelv.aicollab.agent.application.runtime.AgentModelMessageComposer.CompositionStats.empty(),null,loaded);
        new com.shitulelv.aicollab.agent.application.runtime.AgentContextSummarizer(reloaded,executor,json).maybeSummarize(next,second,10000);
        var requests=org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(executor,org.mockito.Mockito.times(2)).callModelWithoutTools(org.mockito.ArgumentMatchers.any(),requests.capture());
        assertThat(requests.getAllValues().get(1).toString()).contains("片段 1200-1800").doesNotContain("片段 600-1200");
        jdbc.update("DELETE FROM agent_message WHERE id=?",old);
        assertThat(reloaded.listSummaryCandidates(next,java.util.Set.of(),20)).noneMatch(m->m.id().equals(old));
        jdbc.update("DELETE FROM project_member WHERE project_id=? AND user_id=?",f.project(),f.user());
        assertThatThrownBy(()->reloaded.listSummaryCandidates(next,java.util.Set.of(),20)).isInstanceOf(BusinessException.class);
    }

    @Test void nativeFinalPersistsOnlyLiveSourcesActuallyReadInThisRun() {
        var json=new ObjectMapper();
        var own=fixture(); var foreign=fixture();
        var session=repository.createSession(own.project(),own.user(),"source-flow");
        var queued=repository.createRun(own.project(),session.id(),own.user(),"读取资料",false,null,null);
        repository.claimNext("worker",OffsetDateTime.now(ZoneOffset.UTC),Duration.ofMinutes(1));
        var running=repository.findRun(own.project(),queued.id()).orElseThrow();
        var sources=new java.util.ArrayList<UUID>();
        for(int i=0;i<4;i++) {
            var f=i==2?foreign:own; UUID doc=UUID.randomUUID(),chunk=UUID.randomUUID(); sources.add(chunk);
            jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,uploaded_by) VALUES (?,?,'source','source.txt','text/plain',10,?,'FAILED',?)",doc,f.project(),doc.toString(),f.user());
            jdbc.update("INSERT INTO document_body(document_id,snapshot_id,original_content_hash,parse_version) VALUES (?,?,?,'test')",doc,UUID.randomUUID(),"a".repeat(64));
            jdbc.update("INSERT INTO document_body_chunk(id,document_id,chunk_no,content,content_hash) VALUES (?,?,0,'actual source',?)",chunk,doc,"b".repeat(64));
            var result=json.createObjectNode(); result.putArray("citations").addObject().put("documentId",doc.toString()).put("chunkId",chunk.toString()).put("quote","actual source").put("filename","source.txt");
            running=repository.recordToolResult(running,"read_document_section",json.createObjectNode(),result,i==3);
            if(i==0) running=repository.recordToolResult(running,"read_document_section",json.createObjectNode(),result,false); // duplicated replay
            if(i==1) jdbc.update("DELETE FROM document_body_chunk WHERE id=?",chunk);
        }
        repository.recordFinal(running,"资料总结",List.of());
        var message=repository.listMessages(own.project(),session.id(),100).getLast();
        assertThat(message.citations()).hasSize(1);
        assertThat(message.citations().get(0).path("chunkId").asText()).isEqualTo(sources.getFirst().toString());
        assertThat(repository.listSteps(own.project(),queued.id()).getLast().output().path("citations")).isEqualTo(message.citations());
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
