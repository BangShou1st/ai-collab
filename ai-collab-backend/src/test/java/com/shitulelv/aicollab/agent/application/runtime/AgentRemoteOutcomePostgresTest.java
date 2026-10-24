package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.tool.*;
import com.shitulelv.aicollab.agent.domain.model.*;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.agent.infrastructure.repository.*;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Isolated PostgreSQL: actual committed invocation, run, step and event state. */
@Testcontainers
class AgentRemoteOutcomePostgresTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static ObjectMapper json;
    static AgentRepository repository;
    static TransactionTemplate tx;
    static AgentEventService events;
    AgentRunView run;
    ModelToolCall call;

    @BeforeAll static void migrate() {
        var ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE fake_remote_operation(invocation_id uuid PRIMARY KEY,operation_id uuid NOT NULL)");
        json = new ObjectMapper().findAndRegisterModules();
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        events = new AgentEventService(proxied(new AgentEventRepository(jdbc, json), manager),
                org.mockito.Mockito.mock(AgentEventStreamService.class));
        var recorder = new AgentRunEventRecorder(jdbc, json);
        org.springframework.test.util.ReflectionTestUtils.setField(recorder, "events", events);
        repository = proxied(new AgentRepository(jdbc, json, proxied(recorder, manager)), manager);
    }

    @SuppressWarnings("unchecked")
    static <T> T proxied(T target, org.springframework.transaction.PlatformTransactionManager manager) {
        var proxy = new org.springframework.aop.framework.ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE app_user CASCADE");
        UUID user = UUID.randomUUID(), project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,'test-hash','Outcome')", user, "out-" + user.toString().substring(0, 8));
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'Outcome',?,?)", project, user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')", project, user);
        var session = repository.createSession(project, user, "Outcome");
        var created = repository.createRun(project, session.id(), user, "Outcome", false, "ITERATION_PLANNING", null);
        // Other fixtures may remain queued; directly establish an isolated valid claim.
        jdbc.update("UPDATE agent_run SET status='RUNNING',lease_owner='test',lease_expires_at=now()+interval '6 minutes',claim_version=1 WHERE id=?", created.id());
        run = repository.findRun(project, created.id()).orElseThrow();
        call = new ModelToolCall("original", "list_tasks", json.createObjectNode().put("value", 1));
        run = tx.execute(s -> repository.recordModelTurn(run, turn(call)));
    }

    static ModelTurnResult turn(ModelToolCall... calls) {
        return new ModelTurnResult("", List.of(calls), ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "fake", 1L);
    }

    void result(String status) {
        var input = json.createObjectNode().put("toolCallId", call.id()).set("arguments", call.arguments());
        run = tx.execute(s -> repository.recordToolResult(run, call.name(), input,
                json.createObjectNode().put("status", status).put("retryable", false), !"SUCCEEDED".equals(status)));
    }

    @Test void unknownCannotBeOverwrittenByLateSuccess() {
        result("UNKNOWN");
        assertThatThrownBy(() -> result("SUCCEEDED")).isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_step WHERE run_id=? AND reason='TOOL_SUCCESS'", Integer.class, run.id())).isZero();
    }

    @Test void batchSettlementMustPreserveUnknown() {
        result("UNKNOWN");
        tx.executeWithoutResult(s -> repository.recordFailure(run, "TOOL_OUTCOME_UNKNOWN", false));
        assertThat(status()).isEqualTo("UNKNOWN");
        assertThat(repository.findRun(run.projectId(), run.id()).orElseThrow().status().name()).isEqualTo("FAILED");
        assertThat(repository.knownInvocationResult(run, call).orElseThrow().path("status").asText()).isEqualTo("UNKNOWN");
    }

    @Test void newToolCallIdMustNotRepeatAnUnconfirmedEffect() {
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        effects.incrementAndGet(); // Independent remote acceptance before the lost response.
        result("UNKNOWN");
        call = new ModelToolCall("new-id", "list_tasks", call.arguments());
        run = tx.execute(s -> repository.recordModelTurn(run, turn(call)));
        var tool = new com.shitulelv.aicollab.agent.domain.tool.AgentTool() {
            public String name() { return "list_tasks"; }
            public boolean writesBusinessData() { return false; }
            public com.shitulelv.aicollab.agent.domain.tool.AgentToolResult execute(
                    com.shitulelv.aicollab.agent.domain.tool.AgentToolContext ctx, com.fasterxml.jackson.databind.JsonNode args) {
                effects.incrementAndGet();
                return new com.shitulelv.aicollab.agent.domain.tool.AgentToolResult(json.createObjectNode(), List.of(), List.of());
            }
        };
        var registry = new com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry(List.of(tool));
        var scheduler = new AgentToolScheduler();
        try {
            var executor = new AgentToolCallExecutor(repository, registry, new AgentCancellationService(repository),
                    new AgentLoopGuard(), null, null,
                    new com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer(json), json,
                    org.mockito.Mockito.mock(AgentEventService.class), scheduler);
            var limits = com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits.defaults();
            var ctx = new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(run.id(), run.sessionId(),
                    run.projectId(), run.requesterId(), "OWNER", false,
                    com.shitulelv.aicollab.agent.domain.model.AgentPageContext.empty(), limits, 0, List.of());
            executor.executeCalls(run, ctx, new com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry().require("ITERATION_PLANNING"),
                    turn(call), List.of(tool.definition()), repository.listSteps(run.projectId(), run.id()));
        } finally { scheduler.close(); }
        assertThat(effects).hasValue(1);
        assertThat(repository.findRun(run.projectId(), run.id()).orElseThrow().status().name()).isEqualTo("FAILED");
    }

    String status() {
        return jdbc.queryForObject("SELECT status FROM agent_tool_invocation WHERE invocation_id=?", String.class, repository.invocationId(run, call));
    }

    AgentToolContext toolContext() {
        return new AgentToolContext(run.id(), run.projectId(), run.requesterId(), "OWNER", false, 0,
                repository.invocationId(run, call));
    }

    int epoch() { return jdbc.queryForObject("SELECT claim_version FROM agent_run WHERE id=?", Integer.class, run.id()); }

    void dispatch(AgentToolExecutionSafety safety) {
        try (var lease = new AgentLeaseScope(epoch())) { repository.recordInvocationDispatch(run, call, safety); }
    }

    AgentTool fake(AgentToolExecutionSafety safety, java.util.function.Function<AgentToolContext, AgentToolResult> action) {
        String name = call.name();
        return new AgentTool() {
            public String name() { return name; }
            public boolean writesBusinessData() { return false; }
            public AgentToolExecutionSafety executionSafety() { return safety; }
            public AgentToolResult execute(AgentToolContext ctx, com.fasterxml.jackson.databind.JsonNode args) { return action.apply(ctx); }
        };
    }

    AgentToolResult success() { return new AgentToolResult(json.createObjectNode().put("accepted", true), List.of(), List.of()); }

    com.shitulelv.aicollab.agent.application.AgentWorkerOutcome execute(AgentTool tool, Duration timeout, AgentToolScheduler scheduler) {
        var registry = new AgentToolRegistry(List.of(tool));
        var executor = new AgentToolCallExecutor(repository, registry, new AgentCancellationService(repository),
                new AgentLoopGuard(), null, org.mockito.Mockito.mock(RoutingAgentModelExecutor.class),
                new AgentToolResultSanitizer(json), json, events, scheduler);
        var limits = new AgentRuntimeLimits(24, 8, 16, 4, Duration.ofMinutes(5), timeout, timeout, 32768, null, null);
        var ctx = new AgentExecutionContext(run.id(), run.sessionId(), run.projectId(), run.requesterId(), "OWNER", false,
                AgentPageContext.empty(), limits, 0, List.of());
        try (var lease = new AgentLeaseScope(epoch())) {
            return executor.executeCalls(run, ctx, new AgentSkillRegistry().require("ITERATION_PLANNING"), turn(call),
                    List.of(tool.definition()), repository.listSteps(run.projectId(), run.id()), true);
        } finally { run = repository.findRun(run.projectId(), run.id()).orElseThrow(); }
    }

    com.shitulelv.aicollab.agent.application.AgentWorkerOutcome execute(AgentTool tool) {
        var scheduler = new AgentToolScheduler();
        try { return execute(tool, Duration.ofSeconds(10), scheduler); }
        finally { scheduler.close(); }
    }

    void assertUnknown(int effects, java.util.concurrent.atomic.AtomicInteger counter) {
        assertThat(counter).hasValue(effects);
        assertThat(status()).isEqualTo("UNKNOWN");
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(repository.knownInvocationResult(run, call).orElseThrow().path("retryable").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run_event WHERE run_id=? AND type='TOOL_CALL_FAILED' AND payload_json->>'status'='UNKNOWN'", Integer.class, run.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_step WHERE run_id=? AND reason='TOOL_SUCCESS'", Integer.class, run.id())).isZero();
    }

    @Test void t01NeverStartedTimeoutHasNoEffect() {
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        var scheduler = new AgentToolScheduler();
        try {
            execute(fake(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT, ctx -> { effects.incrementAndGet(); return success(); }), Duration.ZERO, scheduler);
        } finally { scheduler.close(); }
        assertThat(effects).hasValue(0);
        assertThat(status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT dispatch_started_at IS NULL FROM agent_tool_invocation WHERE run_id=?", Boolean.class, run.id())).isTrue();
        assertThat(run.status()).isEqualTo(AgentRunStatus.QUEUED);
    }

    @Test void t02SuccessfulRemoteEffectWithLostResponseIsUnknown() {
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        execute(fake(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT, ctx -> {
            effects.incrementAndGet(); throw new IllegalStateException("simulated response lost after commit");
        }));
        assertUnknown(1, effects);
    }

    @Test void t03ExplicitRemoteRejectionIsNotUnknown() {
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        execute(fake(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT, ctx ->
                new AgentToolResult(null, List.of(), List.of(), AgentToolResult.Status.REJECTED,
                        new AgentToolResult.ToolError("REMOTE_REJECTED", "request rejected before acceptance", false))));
        assertThat(effects).hasValue(0);
        assertThat(status()).isEqualTo("REJECTED");
        assertThat(run.status()).isEqualTo(AgentRunStatus.QUEUED);
    }

    @Test void t04CrashAfterDispatchNeverRedispatchesSameInvocation() {
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        dispatch(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT);
        effects.incrementAndGet(); // Independent remote commit, no local result.
        var tool = fake(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT, ctx -> { effects.incrementAndGet(); return success(); });
        execute(tool);
        assertUnknown(1, effects);
        assertThatThrownBy(() -> dispatch(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT)).isInstanceOf(RuntimeException.class);
        assertThat(effects).hasValue(1);
    }

    @Test void t06TimeoutThenLateRemoteSuccessDoesNotOverwriteUnknown() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var exited = new java.util.concurrent.CountDownLatch(1);
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        var scheduler = new AgentToolScheduler();
        try {
            execute(fake(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT, ctx -> {
                entered.countDown();
                boolean done = false;
                while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { }
                effects.incrementAndGet(); exited.countDown(); return success();
            }), Duration.ofSeconds(2), scheduler);
            assertThat(entered.getCount()).isZero();
            assertThat(status()).isEqualTo("UNKNOWN");
            assertThat(scheduler.snapshot().active()).isEqualTo(1);
            release.countDown();
            assertThat(exited.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertUnknown(1, effects);
        } finally { release.countDown(); scheduler.close(); }
    }

    @Test void t06CancelAfterDispatchPreservesUncertainEffect() {
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> execute(fake(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT, ctx -> {
            effects.incrementAndGet(); repository.requestCancel(run.projectId(), run.id()); return success();
        }))).isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class);
        repository.recordCanceled(run);
        run = repository.findRun(run.projectId(), run.id()).orElseThrow();
        assertThat(effects).hasValue(1);
        assertThat(status()).isEqualTo("UNKNOWN");
        assertThat(run.status()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_step WHERE run_id=? AND reason='TOOL_SUCCESS'", Integer.class, run.id())).isZero();
    }

    @Test void t07OldClaimIsFencedAndTakeoverDoesNotResend() {
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        int old = epoch();
        dispatch(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT);
        effects.incrementAndGet();
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '1 second' WHERE id=?", run.id());
        repository.claimNext("replacement", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        run = repository.findRun(run.projectId(), run.id()).orElseThrow();
        try (var lease = new AgentLeaseScope(old)) {
            assertThatThrownBy(() -> result("SUCCEEDED")).isInstanceOf(IllegalStateException.class);
        }
        execute(fake(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT, ctx -> { effects.incrementAndGet(); return success(); }));
        assertUnknown(1, effects);
    }

    @Test void t08PauseBeforeDispatchKeepsPendingAndResumeExecutesOnce() {
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        var tool = fake(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT, ctx -> { effects.incrementAndGet(); return success(); });
        repository.requestPause(run.projectId(), run.id());
        run = repository.findRun(run.projectId(), run.id()).orElseThrow();
        execute(tool);
        assertThat(effects).hasValue(0);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(run.status()).isEqualTo(AgentRunStatus.PAUSED);
        repository.requestResume(run.projectId(), run.id());
        repository.claimNext("resumed", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        run = repository.findRun(run.projectId(), run.id()).orElseThrow();
        execute(tool);
        assertThat(effects).hasValue(1);
        assertThat(status()).isEqualTo("SUCCEEDED");
    }

    @Test void t10McpWithoutTrustedIdempotencyDoesNotResend() {
        call = new ModelToolCall("mcp", "mcp.local.write_like_read", call.arguments());
        run = repository.recordModelTurn(run, turn(call));
        var effects = new java.util.concurrent.atomic.AtomicInteger(1);
        dispatch(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT);
        // Missing/currently unavailable MCP cannot be re-authorized by model names or readOnlyHint.
        execute(fake(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT, ctx -> { effects.incrementAndGet(); return success(); }));
        assertUnknown(1, effects);
    }

    @Test void t09AcceptedPlanningOperationIsReconciledByOriginalInvocation() {
        call = new ModelToolCall("planning", "start_task_plan", json.createObjectNode()
                .put("title", "Outcome plan").put("goal", "Safe acceptance")
                .put("planStartDate", "2026-10-25").put("planDueDate", "2026-11-25").put("maxTaskCount", 10));
        run = repository.recordModelTurn(run, turn(call));
        var commands = org.mockito.Mockito.mock(com.shitulelv.aicollab.planning.application.TaskPlanCommandService.class);
        var service = new com.shitulelv.aicollab.agent.application.AgentPlanningOperationService(jdbc, json,
                org.mockito.Mockito.mock(com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard.class), commands,
                org.mockito.Mockito.mock(com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository.class),
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        UUID planId = UUID.randomUUID(), attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_task_plan(id,project_id,title,goal,plan_start_date,plan_due_date,max_task_count,status,generation_seq,created_by)
                VALUES (?,?,'Outcome plan','Safe acceptance',date '2026-10-25',date '2026-11-25',10,'SKELETON_GENERATING',1,?)
                """, planId, run.projectId(), run.requesterId());
        jdbc.update("""
                INSERT INTO ai_task_plan_attempt(id,plan_id,attempt_no,generation_seq,stage,status,created_by)
                VALUES (?,?,1,1,'SKELETON','QUEUED',?)
                """, attemptId, planId, run.requesterId());
        jdbc.update("UPDATE ai_task_plan SET active_attempt_id=? WHERE id=?", attemptId, planId);
        var plan = org.mockito.Mockito.mock(com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord.class);
        org.mockito.Mockito.when(plan.id()).thenReturn(planId);
        org.mockito.Mockito.when(plan.generationSeq()).thenReturn(1L);
        org.mockito.Mockito.when(plan.activeAttemptId()).thenReturn(attemptId);
        org.mockito.Mockito.when(commands.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(plan);
        dispatch(AgentToolExecutionSafety.IDEMPOTENT_OR_RECONCILABLE);
        var accepted = tx.execute(s -> service.mutate(toolContext(), call.name(), call.arguments()));
        var repeated = tx.execute(s -> service.mutate(toolContext(), call.name(), call.arguments()));
        assertThat(repeated.path("operationId")).isEqualTo(accepted.path("operationId"));
        // Real DB unique constraint, independent of service's existing-result lookup.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO agent_planning_operation(invocation_id,project_id,requester_id,session_id,origin_run_id,goal_revision,kind,request_json,plan_id,attempt_id,generation_seq)
                SELECT invocation_id,project_id,requester_id,session_id,origin_run_id,goal_revision,kind,request_json,plan_id,attempt_id,generation_seq
                FROM agent_planning_operation WHERE invocation_id=?
                """, toolContext().invocationId())).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        var config = new com.shitulelv.aicollab.agent.infrastructure.tool.PlanningAgentTools(service,
                org.mockito.Mockito.mock(com.shitulelv.aicollab.planning.application.TaskPlanQueryService.class), json);
        AgentTool planning = org.springframework.test.util.ReflectionTestUtils.invokeMethod(config, "startTaskPlan");
        execute(planning);
        assertThat(status()).isEqualTo("SUCCEEDED");
        assertThat(run.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_planning_operation WHERE invocation_id=?", Integer.class, toolContext().invocationId())).isEqualTo(1);
        assertThat(repository.knownInvocationResult(run, call).orElseThrow().path("data").path("operationId")).isEqualTo(accepted.path("operationId"));
        org.mockito.Mockito.verify(commands, org.mockito.Mockito.times(1)).create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test void t11SafeReadFailureRetainsBoundedRetry() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var tool = fake(AgentToolExecutionSafety.SAFE_READ, ctx -> {
            calls.incrementAndGet(); return AgentToolResult.failed("TEMPORARY_READ_FAILURE", "retryable read", true);
        });
        execute(tool);
        for (int i = 0; i < 2; i++) {
            repository.claimNext("read-retry-" + i, java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
            run = repository.findRun(run.projectId(), run.id()).orElseThrow();
            call = new ModelToolCall("read-retry-" + i, call.name(), call.arguments());
            run = repository.recordModelTurn(run, turn(call));
            execute(tool);
        }
        assertThat(calls).hasValue(2); // Initial read + one permitted retry; third is rejected.
        assertThat(status()).isEqualTo("REJECTED");
        assertThat(repository.recoveryCounters(run).get("TOOL_RETRY")).isEqualTo(1);
    }

    @Test void t12PartialBatchKeepsSuccessAndUnknownDistinct() {
        var first = call;
        var second = new ModelToolCall("second", call.name(), json.createObjectNode().put("value", 2));
        run = repository.recordModelTurn(run, turn(first, second));
        dispatch(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT);
        result("SUCCEEDED");
        call = second;
        dispatch(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT);
        var effects = new java.util.concurrent.atomic.AtomicInteger(2);
        execute(fake(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT, ctx -> { effects.incrementAndGet(); return success(); }));
        assertThat(effects).hasValue(2);
        assertThat(status()).isEqualTo("UNKNOWN");
        assertThat(repository.knownInvocationResult(run, first).orElseThrow().path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(repository.countSettledInvocations(run, List.of(first, second))).isEqualTo(2);
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
    }

    @Test void t13ResultTransactionConflictCannotLeaveFalseSuccess() {
        dispatch(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT);
        var effects = new java.util.concurrent.atomic.AtomicInteger(1);
        jdbc.update("UPDATE agent_run SET version=version+1 WHERE id=?", run.id());
        assertThatThrownBy(() -> result("SUCCEEDED")).isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_step WHERE run_id=? AND reason='TOOL_SUCCESS'", Integer.class, run.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run_event WHERE run_id=? AND type='TOOL_CALL_COMPLETED'", Integer.class, run.id())).isZero();
        run = repository.findRun(run.projectId(), run.id()).orElseThrow();
        execute(fake(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT, ctx -> { effects.incrementAndGet(); return success(); }));
        assertUnknown(1, effects);
    }

    @Test void t14UnknownBlocksFinalSuccessAndModelContinuation() {
        result("UNKNOWN");
        assertThatThrownBy(() -> repository.recordFinal(run, "all done", List.of())).isInstanceOf(IllegalStateException.class);
        var model = org.mockito.Mockito.mock(RoutingAgentModelExecutor.class);
        var coordinator = new AgentRuntimeCoordinator(repository, null, null, null, null,
                new AgentCancellationService(repository), null, null, model, new AgentToolResultSanitizer(json), json);
        assertThat(coordinator.advance(run).status()).isEqualTo(AgentRunStatus.FAILED);
        org.mockito.Mockito.verifyNoInteractions(model);
        assertThat(status()).isEqualTo("UNKNOWN");
    }

    @Test void cancelBeforeDispatchNeverStartsTool() {
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        repository.requestCancel(run.projectId(), run.id());
        run = repository.findRun(run.projectId(), run.id()).orElseThrow();
        assertThatThrownBy(() -> execute(fake(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT,
                ctx -> { effects.incrementAndGet(); return success(); })))
                .isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class);
        repository.recordCanceled(run);
        assertThat(effects).hasValue(0);
        assertThat(status()).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT dispatch_started_at IS NULL FROM agent_tool_invocation WHERE run_id=?", Boolean.class, run.id())).isTrue();
    }

    @Test void conflictingResultIdentityCannotCreateSuccessOrSpendBudget() {
        dispatch(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT);
        int used = run.toolCallsUsed();
        var wrong = json.createObjectNode().put("toolCallId", call.id())
                .set("arguments", json.createObjectNode().put("value", 999));
        assertThatThrownBy(() -> repository.recordToolResult(run, call.name(), wrong,
                json.createObjectNode().put("status", "SUCCEEDED"), false)).isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(repository.findRun(run.projectId(), run.id()).orElseThrow().toolCallsUsed()).isEqualTo(used);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run_event WHERE run_id=? AND type='TOOL_CALL_COMPLETED'", Integer.class, run.id())).isZero();
    }

    @Test void requeueCannotTurnUnconfirmedDispatchIntoSkippedOrRetryableFailure() {
        dispatch(AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT);
        repository.requeueRun(run);
        run = repository.findRun(run.projectId(), run.id()).orElseThrow();
        assertThat(status()).isEqualTo("UNKNOWN");
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(repository.recoveryCounters(run).get("MODEL_RETRY")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run_event WHERE run_id=? AND type='TOOL_CALL_FAILED' AND payload_json->>'status'='UNKNOWN'", Integer.class, run.id())).isEqualTo(1);
    }

    @Test void acceptedResultCanBeQueriedWhileOriginalResponseIsStillBlocked() throws Exception {
        call = new ModelToolCall("accepted-timeout", "start_task_plan", json.createObjectNode());
        run = repository.recordModelTurn(run, turn(call));
        var release = new java.util.concurrent.CountDownLatch(1);
        var exited = new java.util.concurrent.CountDownLatch(1);
        UUID operation = UUID.randomUUID();
        AgentTool tool = new ControlledWriteAgentTool() {
            public String name() { return "start_task_plan"; }
            public AgentToolExecutionSafety executionSafety() { return AgentToolExecutionSafety.IDEMPOTENT_OR_RECONCILABLE; }
            public AgentToolResult execute(AgentToolContext ctx, com.fasterxml.jackson.databind.JsonNode args) {
                jdbc.update("INSERT INTO fake_remote_operation VALUES (?,?)", ctx.invocationId(), operation);
                boolean done = false;
                while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { }
                exited.countDown(); return new AgentToolResult(json.createObjectNode().put("operationId", operation.toString()), List.of(), List.of());
            }
            public Optional<AgentToolResult> reconcile(AgentToolContext ctx, com.fasterxml.jackson.databind.JsonNode args) {
                return jdbc.queryForList("SELECT operation_id FROM fake_remote_operation WHERE invocation_id=?", UUID.class, ctx.invocationId())
                        .stream().findFirst().map(id -> new AgentToolResult(json.createObjectNode().put("operationId", id.toString()), List.of(), List.of()));
            }
        };
        var scheduler = new AgentToolScheduler();
        try {
            execute(tool, Duration.ofSeconds(2), scheduler);
            assertThat(status()).isEqualTo("SUCCEEDED");
            assertThat(run.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
            assertThat(scheduler.snapshot().active()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fake_remote_operation WHERE invocation_id=?", Integer.class, toolContext().invocationId())).isEqualTo(1);
            release.countDown();
            assertThat(exited.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(status()).isEqualTo("SUCCEEDED");
            assertThat(run.toolCallsUsed()).isEqualTo(1);
        } finally { release.countDown(); scheduler.close(); }
    }
}
