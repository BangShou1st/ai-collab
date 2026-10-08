package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentLeaseScope;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder.UsageSettlement;
import com.shitulelv.aicollab.infrastructure.ai.model.ZenModelExecution;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RUN_CONTEXT 提交边界回归（审查 2026-10-08 C4/C5，真实 PostgreSQL）。
 *
 * <p>用真实 Recorder/Repository 与 Flyway 迁移验证：实际用量结算与有效摘要发布分开、
 * 同 attempt 重复完成幂等、失去 claim / 取消 / 目标修订冲突不得发布有效摘要但用量如实结算；
 * 已提交摘要经真实持久化进入实际组装消息并替换覆盖前缀；两个压缩周期经真实持久化推进。
 * 隔离 Testcontainers 容器，不读写生产库。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentRunContextCommitPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static ObjectMapper json;
    static AgentRepository repository;
    static AgentRunEventRecorder recorder;

    private NativeToolCallingExecutor nativeExecutor;
    private RoutingAgentModelExecutor model;
    private AgentContextSummarizer summarizer;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        json = new ObjectMapper().findAndRegisterModules();
        recorder = new AgentRunEventRecorder(jdbc, json);
        repository = new AgentRepository(jdbc, json, recorder);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM agent_usage_settlement");
        jdbc.update("DELETE FROM agent_tool_invocation");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_run_event");
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM app_user");

        nativeExecutor = mock(NativeToolCallingExecutor.class);
        var now = java.time.OffsetDateTime.now();
        var provider = new com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider(
                UUID.randomUUID(), UUID.randomUUID(), "test-native", com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType.OPENAI_COMPATIBLE,
                "https://example.invalid", "/v1/chat/completions", "encrypted", "test-native",
                true, 0.2, 1024,
                java.util.EnumSet.of(com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability.NATIVE_TOOLS,
                        com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability.CHAT),
                true, now, now, null);
        var store = mock(AgentModelConfigurationStore.class);
        when(store.require(any())).thenReturn(provider);
        var zen = mock(ZenModelExecution.class);
        when(zen.isZen(any())).thenReturn(false);
        model = new RoutingAgentModelExecutor(nativeExecutor,
                mock(LegacyReadOnlyAgentExecutor.class), zen, store);
        summarizer = new AgentContextSummarizer(repository, model, json);
    }

    private record Fixture(UUID user, UUID project, UUID session, AgentRunView run) {}

    private Fixture fixture(String goal) {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "rctx-" + user.toString().substring(0, 8), "test-only-hash", "RunContext");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "RUN_CONTEXT 提交边界", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        var session = repository.createSession(project, user, "RUN_CONTEXT 提交边界");
        AgentRunView run = repository.createRun(project, session.id(), user, goal, false,
                "PROJECT_RESEARCH", null);
        jdbc.update("""
                UPDATE agent_run SET status='RUNNING', claim_version=1, claim_started_at=now(),
                  lease_expires_at=now()+interval '10 minutes' WHERE id=?
                """, run.id());
        return new Fixture(user, project, session.id(), repository.findRun(project, run.id()).orElseThrow());
    }

    private JsonNode committedSummary(String text, int from, int through) {
        return committedSummary(text, from, through, 0);
    }

    private JsonNode committedSummary(String text, int from, int through, int goalRevision) {
        return json.createObjectNode()
                .put("scope", "RUN_CONTEXT")
                .put("text", text)
                .put("sourceFromSequence", from)
                .put("sourceThroughSequence", through)
                .put("goalRevision", goalRevision)
                .put("cycle", 1);
    }

    private UsageSettlement usage() {
        return new UsageSettlement(1_000, 100, "PROVIDER", "PROVIDER", 5L);
    }

    private int runInputActual(UUID runId) {
        Integer v = jdbc.queryForObject(
                "SELECT input_tokens_actual FROM agent_run WHERE id=?", Integer.class, runId);
        return v == null ? 0 : v;
    }

    private String stepStatus(UUID attemptId) {
        return jdbc.queryForObject(
                "SELECT output_json->>'status' FROM agent_step WHERE id=?", String.class, attemptId);
    }

    // ========== C4-1：同 attempt 重复完成不重复结算、不改写已发布摘要 ==========

    @Test
    void duplicateCompletionDoesNotResettleOrRewritePublishedSummary() {
        Fixture f = fixture("重复完成幂等");
        UUID attemptId = repository.beginRunContextAttempt(f.run(), 1, 1, 2, 0);
        repository.completeRunContextAttempt(attemptId, "COMMITTED", "test-native", usage(),
                "RUN_CONTEXT_COMMITTED", committedSummary("第一份摘要文本", 1, 2));
        int bookedAfterFirst = runInputActual(f.run().id());

        // 重复完成：换一份不同文本的摘要，不得改写已发布内容，也不得再次结算
        repository.completeRunContextAttempt(attemptId, "COMMITTED", "test-native", usage(),
                "RUN_CONTEXT_COMMITTED", committedSummary("改写尝试文本", 1, 2));

        assertThat(runInputActual(f.run().id())).isEqualTo(bookedAfterFirst);
        assertThat(repository.countCommittedRunContextCycles(f.project(), f.run().id())).isEqualTo(1);
        JsonNode latest = repository.latestRunContextSummary(f.project(), f.run().id());
        assertThat(latest).isNotNull();
        assertThat(latest.path("text").asText()).isEqualTo("第一份摘要文本");
        assertThat(stepStatus(attemptId)).isEqualTo("COMMITTED");
    }

    // ========== C4-2：取消的返回不得成为当前有效摘要，用量仍如实结算 ==========

    @Test
    void canceledReturnCannotBecomeValidSummaryButUsageIsSettled() {
        Fixture f = fixture("取消 fencing");
        UUID attemptId = repository.beginRunContextAttempt(f.run(), 1, 1, 2, 0);
        jdbc.update("UPDATE agent_run SET cancel_requested_at=now() WHERE id=?", f.run().id());

        repository.completeRunContextAttempt(attemptId, "COMMITTED", "test-native", usage(),
                "RUN_CONTEXT_COMMITTED", committedSummary("不应生效的摘要", 1, 2));

        assertThat(repository.latestRunContextSummary(f.project(), f.run().id())).isNull();
        assertThat(repository.countCommittedRunContextCycles(f.project(), f.run().id())).isZero();
        assertThat(stepStatus(attemptId)).isNotEqualTo("COMMITTED");
        // 已发生的模型调用消耗不因 fencing 丢失
        assertThat(runInputActual(f.run().id())).isEqualTo(1_000);
    }

    // ========== C4-3：失去 claim 的旧 worker 不得发布有效摘要 ==========

    @Test
    void staleWorkerWithLostLeaseCannotPublishValidSummary() {
        Fixture f = fixture("失去 claim fencing");
        UUID attemptId = repository.beginRunContextAttempt(f.run(), 1, 1, 2, 0);
        jdbc.update("UPDATE agent_run SET claim_version=7 WHERE id=?", f.run().id());

        // 旧 worker 仍持 epoch=1：发布被拦截，用量照常幂等结算
        try (var scope = new AgentLeaseScope(1)) {
            repository.completeRunContextAttempt(attemptId, "COMMITTED", "test-native", usage(),
                    "RUN_CONTEXT_COMMITTED", committedSummary("旧 worker 摘要", 1, 2));
        }

        assertThat(repository.latestRunContextSummary(f.project(), f.run().id())).isNull();
        assertThat(repository.countCommittedRunContextCycles(f.project(), f.run().id())).isZero();
        assertThat(stepStatus(attemptId)).isNotEqualTo("COMMITTED");
        assertThat(runInputActual(f.run().id())).isEqualTo(1_000);
    }

    // ========== C4-4：目标修订冲突不得发布；一致时正常发布（真实业务修订事实） ==========

    @Test
    void goalRevisionConflictCannotPublishButMatchingRevisionPublishes() {
        Fixture f = fixture("目标修订 fencing");
        // 会话工作状态的业务目标修订（真实事实，非 run.version）
        jdbc.update("""
                UPDATE agent_session SET working_state=jsonb_set(
                  COALESCE(working_state,'{}'::jsonb),'{goalRevision}','2',true) WHERE id=?
                """, f.session());
        UUID attemptId = repository.beginRunContextAttempt(f.run(), 1, 1, 2, 2);

        // 生成期间用户更正目标：goalRevision 前进到 3 → 期望 2 的返回不得发布
        jdbc.update("""
                UPDATE agent_session SET working_state=jsonb_set(
                  working_state,'{goalRevision}','3',true) WHERE id=?
                """, f.session());
        repository.completeRunContextAttempt(attemptId, "COMMITTED", "test-native", usage(),
                "RUN_CONTEXT_COMMITTED", committedSummary("过期目标的摘要", 1, 2), 2);
        assertThat(repository.latestRunContextSummary(f.project(), f.run().id())).isNull();
        assertThat(runInputActual(f.run().id())).isEqualTo(1_000);

        // 同一目标修订（3）下的新周期正常发布
        jdbc.update("UPDATE agent_run SET cancel_requested_at=NULL WHERE id=?", f.run().id());
        UUID second = repository.beginRunContextAttempt(f.run(), 2, 1, 2, 3);
        repository.completeRunContextAttempt(second, "COMMITTED", "test-native", usage(),
                "RUN_CONTEXT_COMMITTED", committedSummary("当前目标的摘要", 1, 2, 3), 3);
        JsonNode latest = repository.latestRunContextSummary(f.project(), f.run().id());
        assertThat(latest).isNotNull();
        assertThat(latest.path("text").asText()).isEqualTo("当前目标的摘要");
        assertThat(latest.path("goalRevision").asInt()).isEqualTo(3);
    }

    // ========== C1+真实持久化：已提交摘要进入实际组装消息，覆盖前缀被替换 ==========

    @Test
    void committedSummaryEntersComposerMessagesThroughRealPersistence() {
        Fixture f = fixture("摘要进入实际消息");
        insertToolStep(f.run().id(), 1, "COVERED_RAW_FACT_SHOULD_NOT_REAPPEAR");
        insertToolStep(f.run().id(), 2, "RECENT_RAW_FACT_5311");

        UUID attemptId = repository.beginRunContextAttempt(f.run(), 1, 1, 1, 0);
        repository.completeRunContextAttempt(attemptId, "COMMITTED", "test-native", usage(),
                "RUN_CONTEXT_COMMITTED", committedSummary("PERSISTED_SUMMARY_FACT_4907", 1, 1));

        AgentRunView run = repository.findRun(f.project(), f.run().id()).orElseThrow();
        var composer = new AgentModelMessageComposer(repository, null, json);
        var composition = composer.composeV2(run, contextFor(run),
                stubSkill(), com.shitulelv.aicollab.agent.domain.model.AgentPlan.create(run.goal(), List.of()),
                repository.listSteps(f.project(), run.id()), 950_000, 1.0, false);

        String request = composition.messages().toString();
        assertThat(request).contains("PERSISTED_SUMMARY_FACT_4907");
        assertThat(request).contains("RECENT_RAW_FACT_5311");
        assertThat(request).doesNotContain("COVERED_RAW_FACT_SHOULD_NOT_REAPPEAR");
    }

    // ========== 两个压缩周期经真实持久化真实推进 ==========

    @Test
    void twoCompactionCyclesAdvanceThroughRealPersistence() {
        Fixture f = fixture("两周期推进");
        insertToolStep(f.run().id(), 1, "CYCLE_ONE_FACT_AAA");
        insertToolStep(f.run().id(), 2, "CYCLE_ONE_FACT_BBB");
        insertModelRequestStep(f.run().id(), 3);
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any()))
                .thenReturn(new com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult(
                        "第一周期摘要文本", List.of(),
                        com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason.STOP,
                        new com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage(2_000, 200),
                        "controlled", "controlled", 1L));

        AgentRunView run = repository.findRun(f.project(), f.run().id()).orElseThrow();
        var budget = new AgentContextBudget.Budget(950_000, 950_000, 256_000, 128_000,
                AgentContextBudget.BINDING_MODEL_WINDOW, false);
        boolean first = summarizer.compactRunContext(run, null, budget, Integer.MAX_VALUE, () -> true);
        assertThat(first).isTrue();
        assertThat(repository.countCommittedRunContextCycles(f.project(), run.id())).isEqualTo(1);
        JsonNode firstSummary = repository.latestRunContextSummary(f.project(), run.id());
        assertThat(firstSummary.path("sourceThroughSequence").asInt()).isEqualTo(2);

        // 新增来源后第二周期：只压缩未覆盖的新记录（seq 10），seq 11 为最新记录不进入。
        // 注意：周期自身的摘要请求步骤也占用 sequence（uq_agent_step_sequence），
        // 因此第二周期的新来源用更高的序号插入。
        insertToolStep(run.id(), 10, "CYCLE_TWO_FACT_CCC");
        insertModelRequestStep(run.id(), 11);
        run = repository.findRun(f.project(), run.id()).orElseThrow();
        org.mockito.ArgumentCaptor<List<com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage>> secondRequest =
                org.mockito.ArgumentCaptor.forClass(List.class);
        when(nativeExecutor.callModel(secondRequest.capture(), anyList(), any(), any(), any()))
                .thenReturn(new com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult(
                        "第二周期摘要文本（延续第一周期）", List.of(),
                        com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason.STOP,
                        new com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage(2_000, 200),
                        "controlled", "controlled", 1L));
        boolean second = summarizer.compactRunContext(run, null, budget, Integer.MAX_VALUE, () -> true);

        assertThat(second).isTrue();
        assertThat(repository.countCommittedRunContextCycles(f.project(), run.id())).isEqualTo(2);
        JsonNode latest = repository.latestRunContextSummary(f.project(), run.id());
        assertThat(latest.path("cycle").asInt()).isEqualTo(2);
        assertThat(latest.path("previousSummaryIncorporated").asBoolean()).isTrue();
        // 第二周期只覆盖新来源：seq 10，不重复压缩已覆盖的 seq 1..2
        assertThat(latest.path("sourceFromSequence").asInt()).isEqualTo(10);
        assertThat(latest.path("sourceThroughSequence").asInt()).isEqualTo(10);
        // 第二周期请求延续上一份摘要文本、包含新记录原文，不重复送入已覆盖原文
        String secondRequestText = secondRequest.getValue().toString();
        assertThat(secondRequestText).contains("第一周期摘要文本");
        assertThat(secondRequestText).contains("CYCLE_TWO_FACT_CCC");
        assertThat(secondRequestText).doesNotContain("CYCLE_ONE_FACT_AAA");
    }

    // ========== 辅助 ==========

    private void insertToolStep(UUID runId, int sequence, String content) {
        jdbc.update("""
                INSERT INTO agent_step(run_id, sequence_no, type, tool_name, input_json, output_json, reason)
                SELECT ?, ?, 'TOOL_CALL_COMPLETED', 'read_document_section',
                  ?::jsonb, ?::jsonb, 'TOOL_SUCCESS'
                """, runId, sequence,
                json.createObjectNode().put("toolCallId", "call-" + sequence).toString(),
                json.createObjectNode().put("status", "SUCCEEDED").put("content", content).toString());
    }

    private void insertModelRequestStep(UUID runId, int sequence) {
        jdbc.update("""
                INSERT INTO agent_step(run_id, sequence_no, type, reason)
                VALUES (?, ?, 'MODEL_REQUEST', 'latest')
                """, runId, sequence);
    }

    private com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext contextFor(AgentRunView run) {
        return new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(
                run.projectId(), run.sessionId(), run.id(), run.requesterId(),
                "SUPERVISOR", false, com.shitulelv.aicollab.agent.domain.model.AgentPageContext.empty(),
                com.shitulelv.aicollab.agent.domain.model.AgentResourcePolicy.v2Limits(0), 0, List.of());
    }

    private com.shitulelv.aicollab.agent.domain.model.AgentSkill stubSkill() {
        var skill = mock(com.shitulelv.aicollab.agent.domain.model.AgentSkill.class);
        when(skill.code()).thenReturn("PROJECT_RESEARCH");
        when(skill.instruction()).thenReturn("Read only document research");
        when(skill.outputContract()).thenReturn("Evidence and gaps");
        return skill;
    }
}
