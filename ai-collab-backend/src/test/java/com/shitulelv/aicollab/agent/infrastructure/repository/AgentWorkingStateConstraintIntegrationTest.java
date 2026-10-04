package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentWorkingState;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 约束规范化与新目标分支提取（真实 PostgreSQL）。
 *
 * <p>回归根因：① appendUser 的"新目标："分支只 replaceGoal，不提取本消息约束；
 * ② 每个作用域的 value 存整段请求，"日期不改，最多10项，先只讨论"整体进入
 * 日期条目，数量改成8后日期条目仍携带"10项/不生成"并被渲染为必须遵守。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentWorkingStateConstraintIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static AgentRepository repository;
    static ObjectMapper json;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        json = new ObjectMapper().findAndRegisterModules();
        repository = new AgentRepository(jdbc, json, new AgentRunEventRecorder(jdbc, json));
    }

    @BeforeEach
    void clearAgentFixtures() {
        jdbc.update("DELETE FROM agent_tool_invocation");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_run_event");
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
    }

    private record Fixture(UUID user, UUID project) {
    }

    private Fixture fixture() {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "ws-" + user.toString().substring(0, 8), "test-only-hash", "WorkingState");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "Constraint split", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        return new Fixture(user, project);
    }

    /** 通过真实 createRun 提交第一条消息（内部走 appendUser），返回运行（携带会话）。 */
    private AgentRunView start(Fixture f, String request) {
        var session = repository.createSession(f.project(), f.user(), "约束回归");
        return repository.createRun(f.project(), session.id(), f.user(), request, false, null, null);
    }

    /** 后续消息：插入真实 USER 消息行并关联到工作状态。 */
    private void submit(AgentRunView run, String request) {
        UUID messageId = jdbc.queryForObject("""
                INSERT INTO agent_message(session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'USER',?,'[]'::jsonb,'[]'::jsonb)
                RETURNING id
                """, UUID.class, run.sessionId(), run.id(), request);
        AgentWorkingState.appendUser(jdbc, json, run.sessionId(), request, messageId);
    }

    private JsonNode state(AgentRunView run) {
        return repository.workingState(run.projectId(), run.sessionId());
    }

    private List<JsonNode> active(JsonNode state, String scope) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode entry : state.path("constraints")) {
            if ("active".equals(entry.path("status").asText())
                    && (scope == null || scope.equals(entry.path("scope").asText()))) {
                result.add(entry);
            }
        }
        return result;
    }

    @Test
    void goalReplacementStillExtractsConstraintsFromSameMessage() {
        Fixture f = fixture();
        AgentRunView run = start(f, "普通目标");
        submit(run, "新目标：转为验收规划，日期不改，负责人不改，最多6项。请先复述有效约束。");

        JsonNode state = state(run);
        assertThat(state.path("activeGoal").asText()).startsWith("新目标：");
        // 新目标分支仍登记了本消息的约束
        List<JsonNode> counts = active(state, "TASK_COUNT_MAX");
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).path("value").asText()).isEqualTo("最多6项");
        assertThat(counts.get(0).path("detail").path("max").asInt()).isEqualTo(6);
        assertThat(active(state, "DATE_LOCK")).hasSize(1);
        assertThat(active(state, "ASSIGNEE_LOCK")).hasSize(1);
        // sourceMessageId 是真实消息 ID
        String sourceMessageId = counts.get(0).path("sourceMessageId").asText();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_message WHERE id=?::uuid AND role='USER'",
                Integer.class, sourceMessageId)).isEqualTo(1);
        // 新条目归属新目标修订
        assertThat(counts.get(0).path("goalRevision").asInt())
                .isEqualTo(state.path("goalRevision").asInt());
    }

    @Test
    void countAndDateConstraintsCarryIndependentFactsThroughSequence() {
        Fixture f = fixture();
        // 第1轮：日期固定+最多10项+暂不生成（复现原验收场景）
        AgentRunView run = start(f, """
                我们要为 ai-collab 做发布前可靠性验收。先只讨论不生成草稿：规划日期固定为2026-10-05至2026-10-25，不改日期；\
                所有任务建议负责人固定为 Local Owner，不改负责人；最多10项任务。先列出服务端边界。""");
        // 第2轮：改为8项
        submit(run, "任务数量改成8项");
        // 第3轮：新目标返回并改6项（沿用此前日期/负责人）
        submit(run, """
                新目标：回到 ai-collab 发布前可靠性验收规划，沿用此前该目标约定的固定日期和 Local Owner，日期不改，\
                负责人不改；任务数量进一步调整为最多6项，取代8项。请先复述有效约束，不生成草稿。""");
        // 第4轮：现在生成
        submit(run, "现在生成同一可靠性目标的完整规划草稿，遵守当前有效约束。");

        JsonNode state = state(run);
        List<JsonNode> counts = active(state, "TASK_COUNT_MAX");
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).path("value").asText()).isEqualTo("最多6项");
        assertThat(counts.get(0).path("detail").path("max").asInt()).isEqualTo(6);

        List<JsonNode> dates = active(state, "DATE_LOCK");
        assertThat(dates).hasSize(1);
        JsonNode dateEntry = dates.get(0);
        // 日期锁只表达日期：保留真实日期区间，不带任何数量或"不生成"要求
        assertThat(dateEntry.path("detail").path("dates").size()).isEqualTo(2);
        assertThat(dateEntry.path("detail").path("dates").get(0).asText()).isEqualTo("2026-10-05");
        assertThat(dateEntry.path("detail").path("dates").get(1).asText()).isEqualTo("2026-10-25");
        assertThat(dateEntry.path("value").asText()).doesNotContain("10项").doesNotContain("8项").doesNotContain("生成");

        List<JsonNode> assignees = active(state, "ASSIGNEE_LOCK");
        assertThat(assignees).hasSize(1);
        assertThat(assignees.get(0).path("detail").path("assignee").asText()).isEqualTo("Local Owner");
        assertThat(assignees.get(0).path("value").asText()).doesNotContain("最多").doesNotContain("项");

        // 任何 active 条目都不得携带旧数量或旧阶段指令
        for (JsonNode entry : active(state, null)) {
            assertThat(entry.path("value").asText()).doesNotContain("10项").doesNotContain("8项");
        }
        // 10→8→6 的历史可追溯（superseded 保留）
        List<String> supersededValues = new ArrayList<>();
        for (JsonNode entry : state.path("constraints")) {
            if ("superseded".equals(entry.path("status").asText())) supersededValues.add(entry.path("value").asText());
        }
        assertThat(supersededValues).contains("最多10项", "最多8项");
        // 目标沿革保留
        assertThat(state.path("goalHistory").size()).isEqualTo(1);
    }

    @Test
    void interrogativeCheckDoesNotResetConstraint() {
        Fixture f = fixture();
        AgentRunView run = start(f, "整理任务清单，最多6项");
        int before = state(run).path("constraints").size();
        submit(run, "请检查当前列表是否仍为最多6项？");

        JsonNode state = state(run);
        assertThat(active(state, "TASK_COUNT_MAX")).hasSize(1);
        assertThat(state.path("constraints").size()).isEqualTo(before);
    }

    @Test
    void negatedOldValueIsNotExtractedAsCurrentConstraint() {
        Fixture f = fixture();
        AgentRunView run = start(f, "整理任务，最多10项");
        submit(run, "不是最多10项了，改成最多4项");

        List<JsonNode> counts = active(state(run), "TASK_COUNT_MAX");
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).path("value").asText()).isEqualTo("最多4项");
    }

    @Test
    void ambiguousNumbersAreKeptForClarificationWithoutOverridingKnownCount() {
        Fixture f = fixture();
        AgentRunView run = start(f, "整理任务，最多6项");
        submit(run, "再想想：最多5项或最多7项都行");

        JsonNode state = state(run);
        List<JsonNode> counts = active(state, "TASK_COUNT_MAX");
        // 已知的 6 不得被含糊消息替代
        assertThat(counts).hasSize(2);
        assertThat(counts.stream().anyMatch(e -> "最多6项".equals(e.path("value").asText()))).isTrue();
        assertThat(counts.stream().anyMatch(e -> e.path("needsClarification").asBoolean())).isTrue();
    }

    @Test
    void legacyWholeRequestValuesAreProgressivelyRepairedOnWrite() {
        Fixture f = fixture();
        AgentRunView run = start(f, "初始目标");
        // 模拟旧版 v2 会话：整段请求被存进每个作用域的 value
        jdbc.update("""
                UPDATE agent_session SET working_state=?::jsonb WHERE id=?
                """, """
                {"schemaVersion":2,"stateRevision":5,"goalRevision":1,"goalVersion":3,
                 "activeGoal":"验收规划",
                 "constraints":[
                   {"id":"11111111-1111-1111-1111-111111111111","scope":"DATE_LOCK","status":"active",
                    "value":"日期不改，最多10项，先只讨论不生成草稿","sourceMessageId":null},
                   {"id":"22222222-2222-2222-2222-222222222222","scope":"TASK_COUNT_MAX","status":"active",
                    "value":"日期不改，最多10项，先只讨论不生成草稿","sourceMessageId":null}
                 ],
                 "latestRequest":"旧请求"}
                """, run.sessionId());
        submit(run, "普通追问：任务现在什么状态？");

        JsonNode state = state(run);
        List<JsonNode> dates = active(state, "DATE_LOCK");
        assertThat(dates).hasSize(1);
        // 日期条目被重规范化：不再携带"10项/不生成"
        assertThat(dates.get(0).path("value").asText()).doesNotContain("10项").doesNotContain("生成");
        // 数量条目重新提取出 10 并保持独立事实
        List<JsonNode> counts = active(state, "TASK_COUNT_MAX");
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).path("detail").path("max").asInt()).isEqualTo(10);
        assertThat(counts.get(0).path("quote").asText()).isNotBlank();
    }

    @Test
    void reuseRequiresUniquelyLocatablePriorEvidence() {
        Fixture f = fixture();
        AgentRunView run = start(f, "规划日期固定为2026-01-01至2026-01-31，不改日期");
        submit(run, "改用日期 2026-02-01 至 2026-02-28，不改日期");
        // 两段不同日期历史存在后，"沿用此前日期"无法唯一定位：保守待澄清
        submit(run, "新目标：另一个规划，日期不改，沿用此前日期");

        List<JsonNode> dates = active(state(run), "DATE_LOCK");
        assertThat(dates).hasSize(1);
        assertThat(dates.get(0).path("needsClarification").asBoolean()).isTrue();
        assertThat(dates.get(0).path("quote").asText()).isNotBlank();
    }
}
