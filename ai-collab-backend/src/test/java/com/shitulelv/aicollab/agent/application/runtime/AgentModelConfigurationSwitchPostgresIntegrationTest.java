package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentProposalOutcome;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.builtin.IterationPlanningSkill;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 运行中途按当前 AGENT 用途配置换模型的持久化行为：
 * 每轮重新解析、配置编辑立即生效、不可用时明确失败、运行进度与待处理调用保留。
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentModelConfigurationSwitchPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static AgentRepository repository;
    static AgentModelConfigurationStore store;
    static ObjectMapper json;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        json = new ObjectMapper().findAndRegisterModules();
        var cipher = new ModelSecretCipher("integration-test-master-key-0123456789");
        var providers = new UserAiProviderService(
                new UserAiProviderRepository(jdbc), cipher, new OutboundEndpointPolicy(), List.of());
        repository = new AgentRepository(jdbc, json, new AgentRunEventRecorder(jdbc, json));
        store = new AgentModelConfigurationStore(jdbc, providers);
    }

    @BeforeEach
    void clearFixtures() {
        jdbc.update("DELETE FROM user_model_purpose_assignment");
        jdbc.update("DELETE FROM user_ai_provider");
        jdbc.update("DELETE FROM agent_tool_invocation");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_run_event");
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM app_user");
    }

    @Test
    void eachRoundResolvesCurrentPurposeAssignment() {
        var fixture = fixture();
        UUID modelA = provider(fixture.user(), "model-a", true, "CHAT,NATIVE_TOOLS,USAGE");
        UUID modelB = provider(fixture.user(), "model-b", false, "CHAT,USAGE");
        var run = runningRun(fixture);

        // 无用途分配时走默认配置
        assertThat(store.require(run).id()).isEqualTo(modelA);
        assertThat(snapshotModel(run)).isEqualTo("model-a");

        // 切换 AGENT 用途分配到 B：下一轮准备即采用 B，快照仅作诊断更新
        jdbc.update("INSERT INTO user_model_purpose_assignment(user_id,purpose,provider_id) VALUES (?,'AGENT',?)",
                fixture.user(), modelB);
        UserAiProvider resolved = store.require(run);
        assertThat(resolved.id()).isEqualTo(modelB);
        assertThat(resolved.modelName()).isEqualTo("model-b");
        assertThat(snapshotModel(run)).isEqualTo("model-b");

        // 取消分配后回退默认配置
        jdbc.update("DELETE FROM user_model_purpose_assignment WHERE user_id=?", fixture.user());
        assertThat(store.require(run).id()).isEqualTo(modelA);
    }

    @Test
    void editedConfigurationAppliesInsteadOfFailingRun() {
        var fixture = fixture();
        UUID modelA = provider(fixture.user(), "model-a", true, "CHAT,NATIVE_TOOLS,USAGE");
        var run = runningRun(fixture);
        assertThat(store.require(run).modelName()).isEqualTo("model-a");

        // 编辑同一配置（模型名与更新时间变化）：下一轮读取新值，不再终止运行
        jdbc.update("UPDATE user_ai_provider SET model_name='model-a2', updated_at=now() WHERE id=?", modelA);
        UserAiProvider resolved = store.require(run);
        assertThat(resolved.modelName()).isEqualTo("model-a2");
        assertThat(resolved.id()).isEqualTo(modelA);
        assertThat(snapshotModel(run)).isEqualTo("model-a2");
    }

    @Test
    void unavailableConfigurationFailsClearly() {
        var fixture = fixture();
        UUID modelA = provider(fixture.user(), "model-a", true, "CHAT,USAGE");
        var run = runningRun(fixture);
        assertThat(store.require(run).id()).isEqualTo(modelA);

        // 配置被禁用：明确反馈，不自动选择其他模型
        jdbc.update("UPDATE user_ai_provider SET enabled=false WHERE id=?", modelA);
        assertThatThrownBy(() -> store.require(run))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE);

        // 配置被删除且无可用配置：同样明确失败
        jdbc.update("DELETE FROM user_ai_provider WHERE id=?", modelA);
        assertThatThrownBy(() -> store.require(run))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    @Test
    void modelSwitchPreservesRunProgressAndPendingCalls() {
        var fixture = fixture();
        UUID modelA = provider(fixture.user(), "model-a", true, "CHAT,NATIVE_TOOLS,USAGE");
        UUID modelB = provider(fixture.user(), "model-b", false, "CHAT,NATIVE_TOOLS,USAGE");
        var run = runningRun(fixture);

        // A 配置下的模型轮次产生待处理工具调用
        var turn = new ModelTurnResult("我先检查未完成任务，再核对里程碑。",
                List.of(new ModelToolCall("call-1", "list_tasks", json.valueToTree(Map.of("status", "open")))),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 120L);
        repository.recordModelTurn(run, turn);
        var afterTurn = repository.findRun(fixture.project(), run.id()).orElseThrow();

        // 切换到 B：待处理调用、已用额度、目标都保留，下一轮解析为 B
        jdbc.update("UPDATE user_ai_provider SET enabled=false WHERE id=?", modelA);
        jdbc.update("INSERT INTO user_model_purpose_assignment(user_id,purpose,provider_id) VALUES (?,'AGENT',?)",
                fixture.user(), modelB);

        var pending = repository.pendingModelTurn(repository.findRun(fixture.project(), run.id()).orElseThrow());
        assertThat(pending).isPresent();
        assertThat(pending.get().toolCalls()).hasSize(1);
        assertThat(pending.get().toolCalls().getFirst().id()).isEqualTo("call-1");

        var refreshed = repository.findRun(fixture.project(), run.id()).orElseThrow();
        assertThat(refreshed.goal()).isEqualTo(run.goal());
        assertThat(refreshed.inputTokensUsed()).isGreaterThan(0);
        assertThat(refreshed.stepsUsed()).isEqualTo(afterTurn.stepsUsed());
        assertThat(store.require(refreshed).id()).isEqualTo(modelB);
        assertThat(snapshotModel(refreshed)).isEqualTo("model-b");
        // 已保存轮次记录仍是原模型身份，不因换模型改写
        var provider = jdbc.queryForList("SELECT model_provider,model_name FROM agent_step WHERE run_id=? AND type='MODEL_TURN'", run.id());
        assertThat(provider.getFirst().get("model_provider")).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(provider.getFirst().get("model_name")).isEqualTo("model-a");
    }

    @Test
    void recordModelTurnEmitsNarrationPayload() {
        var fixture = fixture();
        provider(fixture.user(), "model-a", true, "CHAT,NATIVE_TOOLS,USAGE");
        var run = runningRun(fixture);

        var recorder = new AgentRunEventRecorder(jdbc, json);
        var events = mock(AgentEventService.class);
        ReflectionTestUtils.setField(recorder, "events", events);

        var turn = new ModelTurnResult("发现两项延期，我再核对负责人。",
                List.of(new ModelToolCall("call-1", "list_tasks", json.createObjectNode())),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 90L);
        var updated = recorder.recordModelTurn(run, turn);

        var captor = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(events).append(eq(fixture.project()), eq(run.id()), eq(AgentEventType.MODEL_COMPLETED), captor.capture());
        var payload = captor.getValue();
        assertThat(payload.path("content").asText()).isEqualTo("发现两项延期，我再核对负责人。");
        assertThat(payload.path("provider").asText()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(payload.path("model").asText()).isEqualTo("model-a");
        assertThat(payload.path("stepSequence").asInt()).isPositive();
        assertThat(updated.stepsUsed()).isEqualTo(run.stepsUsed() + 1);
    }

    @Test
    void legacyTurnWriteCallIsStillRejectedOnRecovery() {
        var fixture = fixture();
        UUID modelA = provider(fixture.user(), "model-a", true, "CHAT,USAGE");
        UUID modelB = provider(fixture.user(), "model-b", false, "CHAT,NATIVE_TOOLS,USAGE");
        var run = runningRun(fixture);

        // Legacy A 轮次错误生成了写调用并已落库，随后运行中断
        var turn = writeCallTurn("call-rogue", "创建越权任务");
        repository.recordModelTurn(run, turn, "LEGACY_READ_ONLY");

        // 用户把 AGENT 用途切到原生 B：恢复仍按来源轮次的 LEGACY_READ_ONLY 拒绝写调用
        jdbc.update("UPDATE user_ai_provider SET enabled=false WHERE id=?", modelA);
        jdbc.update("INSERT INTO user_model_purpose_assignment(user_id,purpose,provider_id) VALUES (?,'AGENT',?)",
                fixture.user(), modelB);

        var approvals = mock(AgentApprovalService.class);
        var modelExecutor = mock(RoutingAgentModelExecutor.class);
        when(modelExecutor.isLegacyModeForRun(any())).thenReturn(false);
        var executor = toolExecutor(approvals, modelExecutor);
        var refreshed = repository.findRun(fixture.project(), run.id()).orElseThrow();
        var pending = repository.pendingModelTurn(refreshed).orElseThrow();

        var outcome = executor.executeCalls(refreshed, context(refreshed), new IterationPlanningSkill(),
                pending, List.of(writeDefinition()), repository.listSteps(fixture.project(), run.id()), true, false);

        assertThat(outcome.errorCode()).isEqualTo("LEGACY_WRITE_TOOL_FORBIDDEN");
        org.mockito.Mockito.verify(approvals, org.mockito.Mockito.never())
                .proposeOrRevise(any(), any(), any(), any(), any());
        var status = jdbc.queryForObject(
                "SELECT status FROM agent_tool_invocation WHERE run_id=? AND tool_call_id='call-rogue'", String.class, run.id());
        // 拒绝结果（含 status=REJECTED）已按原调用身份落库，写操作未发生
        assertThat(status).isEqualTo("REJECTED");
    }

    @Test
    void nativePendingWriteCallStillRecoversAfterSwitchToLegacy() {
        var fixture = fixture();
        UUID modelA = provider(fixture.user(), "model-a", true, "CHAT,NATIVE_TOOLS,USAGE");
        UUID modelB = provider(fixture.user(), "model-b", false, "CHAT,USAGE");
        var run = runningRun(fixture);

        // 原生 A 轮次的合法待处理写调用已落库，随后中断；用户切到 Legacy B
        var turn = writeCallTurn("call-native", "创建验收任务");
        repository.recordModelTurn(run, turn, "NATIVE_TOOLS");
        jdbc.update("UPDATE user_ai_provider SET enabled=false WHERE id=?", modelA);
        jdbc.update("INSERT INTO user_model_purpose_assignment(user_id,purpose,provider_id) VALUES (?,'AGENT',?)",
                fixture.user(), modelB);

        // 当前配置检查会拒绝（Legacy），但来源轮次是原生——必须放行进入提案流程
        var approvals = mock(AgentApprovalService.class);
        when(approvals.proposeOrRevise(any(), any(), any(), any(), any())).thenReturn(proposalOutcome(fixture, run));
        var modelExecutor = mock(RoutingAgentModelExecutor.class);
        when(modelExecutor.isLegacyModeForRun(any())).thenReturn(true);
        var executor = toolExecutor(approvals, modelExecutor);
        var refreshed = repository.findRun(fixture.project(), run.id()).orElseThrow();
        var pending = repository.pendingModelTurn(refreshed).orElseThrow();

        var outcome = executor.executeCalls(refreshed, context(refreshed), new IterationPlanningSkill(),
                pending, List.of(writeDefinition()), repository.listSteps(fixture.project(), run.id()), true, false);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        org.mockito.Mockito.verify(approvals).proposeOrRevise(any(), any(), any(), any(), any());
        var status = jdbc.queryForObject(
                "SELECT status FROM agent_tool_invocation WHERE run_id=? AND tool_call_id='call-native'", String.class, run.id());
        assertThat(status).isEqualTo("SUCCEEDED");
    }

    @Test
    void completedInvocationIsRecoveredWithoutReexecution() {
        var fixture = fixture();
        provider(fixture.user(), "model-a", true, "CHAT,NATIVE_TOOLS,USAGE");
        var run = runningRun(fixture);

        // 原生轮次的写调用已经受理并保存结果（业务动作已完成）
        var turn = writeCallTurn("call-done", "创建已完成任务");
        repository.recordModelTurn(run, turn, "NATIVE_TOOLS");
        jdbc.update("""
                UPDATE agent_tool_invocation SET status='SUCCEEDED',
                  result_json=?::jsonb
                WHERE run_id=? AND tool_call_id='call-done'
                """, "{\"status\":\"SUCCEEDED\",\"effect\":\"PROPOSAL_PENDING\",\"proposalId\":\"" + UUID.randomUUID() + "\"}", run.id());

        var approvals = mock(AgentApprovalService.class);
        var modelExecutor = mock(RoutingAgentModelExecutor.class);
        var executor = toolExecutor(approvals, modelExecutor);
        var refreshed = repository.findRun(fixture.project(), run.id()).orElseThrow();
        var pending = repository.pendingModelTurn(refreshed).orElseThrow();

        var outcome = executor.executeCalls(refreshed, context(refreshed), new IterationPlanningSkill(),
                pending, List.of(writeDefinition()), repository.listSteps(fixture.project(), run.id()), true, false);

        // 已保存结果按原身份恢复：不再发起提案，运行正常收尾
        org.mockito.Mockito.verify(approvals, org.mockito.Mockito.never())
                .proposeOrRevise(any(), any(), any(), any(), any());
        assertThat(outcome.status()).isIn(AgentRunStatus.SUCCEEDED, AgentRunStatus.QUEUED);
        var status = jdbc.queryForObject(
                "SELECT status FROM agent_tool_invocation WHERE run_id=? AND tool_call_id='call-done'", String.class, run.id());
        assertThat(status).isEqualTo("SUCCEEDED");
    }

    @Test
    void recoveryStillEnforcesSkillWhitelist() {
        var fixture = fixture();
        provider(fixture.user(), "model-a", true, "CHAT,NATIVE_TOOLS,USAGE");
        var run = runningRun(fixture);

        // 原生轮次落库了一个 Skill 白名单之外的写调用：恢复时白名单校验仍然生效
        var outsideTurn = new ModelTurnResult("", List.of(
                new com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall("call-outside", "draft_weekly_report",
                        json.createObjectNode().put("content", "x"))),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 10L);
        repository.recordModelTurn(run, outsideTurn, "NATIVE_TOOLS");

        var approvals = mock(AgentApprovalService.class);
        var modelExecutor = mock(RoutingAgentModelExecutor.class);
        var executor = toolExecutor(approvals, modelExecutor);
        var refreshed = repository.findRun(fixture.project(), run.id()).orElseThrow();
        var outcome = executor.executeCalls(refreshed, context(refreshed), new IterationPlanningSkill(),
                repository.pendingModelTurn(refreshed).orElseThrow(), List.of(outsideDefinition()),
                repository.listSteps(fixture.project(), run.id()), true, false);
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);
        var status = jdbc.queryForObject(
                "SELECT status FROM agent_tool_invocation WHERE run_id=? AND tool_call_id='call-outside'", String.class, run.id());
        assertThat(status).isEqualTo("REJECTED");
        org.mockito.Mockito.verify(approvals, org.mockito.Mockito.never())
                .proposeOrRevise(any(), any(), any(), any(), any());
    }

    /** 构建被测执行器：真实仓库 + 桩写工具，其余协作者用测试替身。 */
    private AgentToolCallExecutor toolExecutor(AgentApprovalService approvals, RoutingAgentModelExecutor modelExecutor) {
        var sanitizer = mock(com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer.class);
        when(sanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));
        com.shitulelv.aicollab.agent.domain.tool.ApprovalWriteAgentTool writeTool = new com.shitulelv.aicollab.agent.domain.tool.ApprovalWriteAgentTool() {
            @Override public String name() { return "create_task_after_approval"; }
            @Override public boolean writesBusinessData() { return true; }
            @Override public com.fasterxml.jackson.databind.JsonNode normalize(
                    com.shitulelv.aicollab.agent.domain.tool.AgentToolContext c, com.fasterxml.jackson.databind.JsonNode v) { return v; }
            @Override public com.fasterxml.jackson.databind.JsonNode diff(
                    com.shitulelv.aicollab.agent.domain.tool.AgentToolContext c, com.fasterxml.jackson.databind.JsonNode v) { return v; }
            @Override public com.shitulelv.aicollab.agent.domain.tool.AgentToolResult execute(
                    com.shitulelv.aicollab.agent.domain.tool.AgentToolContext c, com.fasterxml.jackson.databind.JsonNode v) {
                return new com.shitulelv.aicollab.agent.domain.tool.AgentToolResult(v, List.of(), List.of());
            }
        };
        com.shitulelv.aicollab.agent.domain.tool.AgentTool reportTool = new com.shitulelv.aicollab.agent.domain.tool.AgentTool() {
            @Override public String name() { return "draft_weekly_report"; }
            @Override public boolean writesBusinessData() { return true; }
            @Override public com.shitulelv.aicollab.agent.domain.tool.AgentToolResult execute(
                    com.shitulelv.aicollab.agent.domain.tool.AgentToolContext c, com.fasterxml.jackson.databind.JsonNode v) {
                return new com.shitulelv.aicollab.agent.domain.tool.AgentToolResult(v, List.of(), List.of());
            }
        };
        return new AgentToolCallExecutor(repository,
                new com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry(List.of(writeTool, reportTool)),
                mock(AgentCancellationService.class), mock(AgentLoopGuard.class),
                approvals, modelExecutor, sanitizer,
                json, mock(AgentEventService.class), new AgentToolScheduler());
    }

    private com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext context(AgentRunView run) {
        return new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(), "OWNER", false,
                com.shitulelv.aicollab.agent.domain.model.AgentPageContext.empty(),
                com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits.defaults(), 0, List.of());
    }

    private com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition writeDefinition() {
        return new com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition(
                "create_task_after_approval", "创建任务提案", json.createObjectNode().put("type", "object"), true);
    }

    private com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition outsideDefinition() {
        return new com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition(
                "draft_weekly_report", "起草周报", json.createObjectNode().put("type", "object"), true);
    }

    private ModelTurnResult writeCallTurn(String callId, String title) {
        return new ModelTurnResult("", List.of(new com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall(
                callId, "create_task_after_approval",
                json.createObjectNode().put("title", title).put("description", "恢复验收").put("priority", "HIGH"))),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 10L);
    }

    private AgentProposalOutcome proposalOutcome(Fixture fixture, AgentRunView run) {
        var args = json.createObjectNode().put("title", "恢复验收任务");
        var view = new com.shitulelv.aicollab.agent.application.view.AgentApprovalView(
                UUID.randomUUID(), fixture.project(), run.id(), null, "create_task_after_approval",
                args, args, null, null, "PENDING", fixture.user(), null, null, null,
                OffsetDateTime.now().plusHours(1), null, 0, OffsetDateTime.now(),
                run.sessionId(), com.shitulelv.aicollab.agent.domain.model.AgentProposalFamily.TASK_CREATE,
                UUID.randomUUID(), 1, OffsetDateTime.now(), "nonce");
        return new AgentProposalOutcome(view, AgentProposalOutcome.Operation.CREATED, args, args, args);
    }

    private String snapshotModel(AgentRunView run) {
        return jdbc.queryForObject(
                "SELECT model_configuration_snapshot->>'model' FROM agent_run WHERE id=?", String.class, run.id());
    }

    private AgentRunView runningRun(Fixture fixture) {
        var session = repository.createSession(fixture.project(), fixture.user(), "换模型验收");
        var run = repository.createRun(fixture.project(), session.id(), fixture.user(),
                "检查这周任务是否影响交付", false, null, null);
        jdbc.update("UPDATE agent_run SET status='RUNNING' WHERE id=?", run.id());
        return repository.findRun(fixture.project(), run.id()).orElseThrow();
    }

    private UUID provider(UUID userId, String modelName, boolean isDefault, String capabilities) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_ai_provider(id,user_id,name,provider_type,base_url,api_path,
                  encrypted_api_key,model_name,enabled,capabilities,is_default)
                VALUES (?,?,?,'OPENAI_COMPATIBLE','https://example.com','/v1/chat/completions','enc',?,true,?,?)
                """, id, userId, modelName, modelName, capabilities, isDefault);
        return id;
    }

    private record Fixture(UUID user, UUID project) {
    }

    private Fixture fixture() {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "switch-" + user.toString().substring(0, 8), "test-only-hash", "Switch");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "模型切换验收", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        return new Fixture(user, project);
    }
}
