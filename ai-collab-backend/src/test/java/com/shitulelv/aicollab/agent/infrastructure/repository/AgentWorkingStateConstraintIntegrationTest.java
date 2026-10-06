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

    /** 真实第10轮原句：显式"取代"关系必须立即生效，中间状态 8 active / 10 superseded。 */
    @Test
    void explicitSupersedeSentencePromotesNewCountImmediately() {
        Fixture f = fixture();
        AgentRunView run = start(f, "整理任务，最多10项");
        assertThat(active(state(run), "TASK_COUNT_MAX").get(0).path("value").asText()).isEqualTo("最多10项");

        submit(run, "把本目标任务数量上限调整为最多8项；这取代原来最多10项。日期不改，负责人不改，仍不生成规划。请复述当前有效约束。");
        UUID turn10MessageId = jdbc.queryForObject(
                "SELECT id FROM agent_message WHERE session_id=? AND role='USER' ORDER BY created_at DESC, id DESC LIMIT 1",
                UUID.class, run.sessionId());

        JsonNode after8 = state(run);
        List<JsonNode> counts = active(after8, "TASK_COUNT_MAX");
        // 中间状态：8 已生效、旧 10 已退位——不是"待澄清+旧值仍 active"
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).path("value").asText()).isEqualTo("最多8项");
        assertThat(counts.get(0).path("detail").path("max").asInt()).isEqualTo(8);
        assertThat(counts.get(0).path("needsClarification").isMissingNode()
                || !counts.get(0).path("needsClarification").asBoolean()).isTrue();
        // 溯源与取代关系保留
        assertThat(counts.get(0).path("detail").path("supersedes").get(0).asInt()).isEqualTo(10);
        assertThat(counts.get(0).path("sourceMessageId").asText()).isEqualTo(turn10MessageId.toString());
        assertThat(counts.get(0).path("quote").asText()).contains("8项");
        List<JsonNode> superseded10 = new ArrayList<>();
        for (JsonNode entry : after8.path("constraints")) {
            if ("superseded".equals(entry.path("status").asText())
                    && "最多10项".equals(entry.path("value").asText())) superseded10.add(entry);
        }
        assertThat(superseded10).hasSize(1);
        assertThat(superseded10.get(0).path("supersededBy").asText()).isEqualTo(counts.get(0).path("id").asText());

        // 目标切换：数量约束随目标退位，历史保留
        submit(run, "新目标：切换到费用目标评估");
        assertThat(active(state(run), "TASK_COUNT_MAX")).isEmpty();
        assertThat(state(run).path("goalHistory").size()).isEqualTo(1);

        // 新目标回到可靠性验收并改为 6 项
        submit(run, "新目标：回到可靠性验收规划，任务数量调整为最多6项；这取代此前最多8项。");
        JsonNode after6 = state(run);
        List<JsonNode> counts6 = active(after6, "TASK_COUNT_MAX");
        assertThat(counts6).hasSize(1);
        assertThat(counts6.get(0).path("detail").path("max").asInt()).isEqualTo(6);
        // 10 与 8 都只存在于 superseded 历史
        List<String> supersededValues = new ArrayList<>();
        for (JsonNode entry : after6.path("constraints")) {
            if ("superseded".equals(entry.path("status").asText())) supersededValues.add(entry.path("value").asText());
        }
        assertThat(supersededValues).contains("最多10项", "最多8项");

        // 现在生成：数量约束保持 6，不因普通请求变化
        submit(run, "现在生成同一目标的完整规划草稿。");
        List<JsonNode> countsFinal = active(state(run), "TASK_COUNT_MAX");
        assertThat(countsFinal).hasSize(1);
        assertThat(countsFinal.get(0).path("detail").path("max").asInt()).isEqualTo(6);
    }

    /** 兼容既有误标：待澄清条目 + 旧值 active 的会话，显式取代句后一并退位。 */
    @Test
    void explicitSupersedeResolvesPreviouslyPendingCountWithoutClearingHistory() {
        Fixture f = fixture();
        AgentRunView run = start(f, "整理任务，最多10项");
        submit(run, "最多8项还是最多10项，你再看看"); // 歧义 → 待澄清，旧 10 仍 active

        JsonNode pending = state(run);
        assertThat(active(pending, "TASK_COUNT_MAX")).hasSize(2);

        submit(run, "把本目标任务数量上限调整为最多8项；这取代原来最多10项。");
        JsonNode state = state(run);
        List<JsonNode> counts = active(state, "TASK_COUNT_MAX");
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).path("detail").path("max").asInt()).isEqualTo(8);
        // 待澄清条目与旧 10 条目都退位，历史保留
        List<String> supersededValues = new ArrayList<>();
        for (JsonNode entry : state.path("constraints")) {
            if ("superseded".equals(entry.path("status").asText())) supersededValues.add(entry.path("value").asText());
        }
        assertThat(supersededValues).contains("最多10项");
        assertThat(state.path("constraints").size()).isGreaterThanOrEqualTo(3);
    }

    /** 疑问/假设句不得修改当前约束。 */
    @Test
    void hypotheticalAndQuestionFormsDoNotChangeCount() {
        Fixture f = fixture();
        AgentRunView run = start(f, "整理任务，最多6项");
        submit(run, "如果改成8项会怎样？");
        submit(run, "要不要改成10项？");
        JsonNode state = state(run);
        List<JsonNode> counts = active(state, "TASK_COUNT_MAX");
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).path("detail").path("max").asInt()).isEqualTo(6);
    }

    /** 不同任务的日期保护并存；明确修改只影响对应任务。 */
    @Test
    void dateLocksForDifferentTasksCoexistAndExplicitChangeOnlyAffectsItsObject() {
        Fixture f = fixture();
        AgentRunView run = start(f, "初始目标");
        submit(run, "任务A的日期固定为2026-10-05至2026-10-10，不改日期");
        submit(run, "任务B的日期固定为2026-10-15至2026-10-20，不改日期");

        JsonNode both = state(run);
        List<JsonNode> locks = active(both, "DATE_LOCK");
        assertThat(locks).hasSize(2);
        assertThat(locks.stream().map(e -> e.path("detail").path("object").asText()))
                .containsExactlyInAnyOrder("任务A", "任务B");

        // 明确修改任务A：只影响A，任务B保护保持 active
        submit(run, "任务A的日期不变，固定为2026-10-06至2026-10-11");
        JsonNode afterChange = state(run);
        List<JsonNode> locksAfter = active(afterChange, "DATE_LOCK");
        assertThat(locksAfter).hasSize(2);
        JsonNode entryA = locksAfter.stream()
                .filter(e -> "任务A".equals(e.path("detail").path("object").asText())).findFirst().orElseThrow();
        JsonNode entryB = locksAfter.stream()
                .filter(e -> "任务B".equals(e.path("detail").path("object").asText())).findFirst().orElseThrow();
        assertThat(entryA.path("detail").path("dates").get(0).asText()).isEqualTo("2026-10-06");
        assertThat(entryB.path("detail").path("dates").get(0).asText()).isEqualTo("2026-10-15");
        assertThat(entryB.path("status").asText()).isEqualTo("active");

        // 对象含糊的全局日期：不自动覆盖已有明确对象的约束
        submit(run, "整体日期定为2026-11-01至2026-11-10，不改日期");
        List<JsonNode> locksAmbiguous = active(state(run), "DATE_LOCK");
        assertThat(locksAmbiguous).hasSize(3);
        assertThat(locksAmbiguous.stream().anyMatch(e ->
                "任务A".equals(e.path("detail").path("object").asText())
                        && e.path("detail").path("dates").get(0).asText().equals("2026-10-06"))).isTrue();
        assertThat(locksAmbiguous.stream().anyMatch(e ->
                "任务B".equals(e.path("detail").path("object").asText()))).isTrue();
    }

    /** 不同任务的负责人保护并存；旧实现按值不同互相覆盖。 */
    @Test
    void assigneeLocksForDifferentTasksCoexist() {
        Fixture f = fixture();
        AgentRunView run = start(f, "初始目标");
        submit(run, "任务A负责人固定为甲");
        submit(run, "任务B负责人固定为乙");

        List<JsonNode> locks = active(state(run), "ASSIGNEE_LOCK");
        assertThat(locks).hasSize(2);
        assertThat(locks.stream().map(e -> e.path("detail").path("object").asText()))
                .containsExactlyInAnyOrder("任务A", "任务B");
        assertThat(locks.stream().map(e -> e.path("detail").path("assignee").asText()))
                .containsExactlyInAnyOrder("甲", "乙");

        // 明确修改任务A负责人：只影响A
        submit(run, "任务A负责人改为丙");
        List<JsonNode> after = active(state(run), "ASSIGNEE_LOCK");
        assertThat(after).hasSize(2);
        assertThat(after.stream().anyMatch(e ->
                "任务A".equals(e.path("detail").path("object").asText())
                        && "丙".equals(e.path("detail").path("assignee").asText()))).isTrue();
        assertThat(after.stream().anyMatch(e ->
                "任务B".equals(e.path("detail").path("object").asText())
                        && "乙".equals(e.path("detail").path("assignee").asText()))).isTrue();
    }

    /** "沿用此前"必须在对应对象范围内唯一定位，不能把别的对象的日期套过来。 */
    @Test
    void reusePriorDatesIsScopedToTheSameObject() {
        Fixture f = fixture();
        AgentRunView run = start(f, "初始目标");
        submit(run, "任务A的日期固定为2026-01-05至2026-01-10，不改日期");
        submit(run, "任务B的日期沿用此前，不改日期");

        List<JsonNode> locks = active(state(run), "DATE_LOCK");
        JsonNode entryB = locks.stream()
                .filter(e -> "任务B".equals(e.path("detail").path("object").asText())).findFirst().orElseThrow();
        // 任务B没有自己的历史日期：不跨对象复用任务A的日期
        assertThat(entryB.path("value").asText()).doesNotContain("2026-01-05");
        assertThat(entryB.path("detail").has("dates")).isFalse();
        assertThat(entryB.path("value").asText()).contains("任务B");
    }

    /** 泛指整个规划的约束与具体任务约束不混为一条；泛指沿用只在泛指条目里定位。 */
    @Test
    void genericAndObjectScopedConstraintsStaySeparate() {
        Fixture f = fixture();
        AgentRunView run = start(f, "初始目标");
        submit(run, "任务A的日期固定为2026-01-05至2026-01-10，不改日期");
        submit(run, "规划日期沿用此前，不改日期");
        // 泛指条目没有可定位的泛指历史日期 → 待澄清，而不是沿用任务A的日期
        List<JsonNode> locks = active(state(run), "DATE_LOCK");
        assertThat(locks).hasSize(2);
        JsonNode generic = locks.stream()
                .filter(e -> e.path("detail").path("object").asText().isEmpty()).findFirst().orElseThrow();
        assertThat(generic.path("needsClarification").asBoolean()).isTrue();
        assertThat(generic.path("value").asText()).doesNotContain("2026-01-05");
        // 任务A条目不受影响
        JsonNode entryA = locks.stream()
                .filter(e -> "任务A".equals(e.path("detail").path("object").asText())).findFirst().orElseThrow();
        assertThat(entryA.path("detail").path("dates").get(0).asText()).isEqualTo("2026-01-05");
    }

    /**
     * 回归（20261006 真实验收溯源）：分析类请求"…按目标日期完成,分别需要什么条件…"
     * 曾经由"日期+≤6字桥接+单字'别'（来自'分别'）"误命中 DATE_LOCK，登记出
     * 无日期、无对象的"不改日期"硬约束并被渲染为必须遵守。分析请求不是约束设定。
     */
    @Test
    void analysisRequestMentioningDatesDoesNotCreateDateLock() {
        Fixture f = fixture();
        AgentRunView run = start(f, "初始目标");
        submit(run, """
                请做一次里程碑维度的深入分析:对每个里程碑,列出其下所有任务的标题、状态、截止日期、依赖数量,并计算:\
                1) 每个里程碑按任务数加权的状态分布;2) 哪些任务的状态与其截止日期的紧迫程度不匹配(例如 URGENT 优先级却排到很晚,\
                或截止日临近却还是 TODO);3) 每个里程碑最早和最晚的任务截止日期;4) 你认为每个里程碑能否按目标日期完成,\
                分别需要什么条件。输出用表格,数据来源标注工具名。""");

        assertThat(active(state(run), "DATE_LOCK")).isEmpty();
    }

    /** "能否/是否"类核查语气即使命中日期保护词形，也不产生锁定约束。 */
    @Test
    void dateQuestionDoesNotLockDates() {
        Fixture f = fixture();
        AgentRunView run = start(f, "初始目标");
        submit(run, "请评估各里程碑能否按目标日期完成，日期是否会延期？");

        assertThat(active(state(run), "DATE_LOCK")).isEmpty();
    }

    /** 渐进修复不得覆盖来源 quote：quote 是来源消息中的原文证据，重提取只能从 value 再生。 */
    @Test
    void repairNormalizationPreservesOriginalQuote() {
        Fixture f = fixture();
        AgentRunView run = start(f, "初始目标");
        jdbc.update("""
                UPDATE agent_session SET working_state=?::jsonb WHERE id=?
                """, """
                {"schemaVersion":2,"stateRevision":5,"goalRevision":1,"goalVersion":3,
                 "activeGoal":"验收规划",
                 "constraints":[
                   {"id":"33333333-3333-3333-3333-333333333333","scope":"DATE_LOCK","status":"active",
                    "value":"不改日期","quote":"列出其下所有任务的标题、状态、截止日期、依赖数量","sourceMessageId":null}
                 ],
                 "latestRequest":"旧请求"}
                """, run.sessionId());
        submit(run, "普通追问：任务现在什么状态？");

        List<JsonNode> dates = active(state(run), "DATE_LOCK");
        assertThat(dates).hasSize(1);
        // 修复只规范化 value；来源 quote 保持原样，不每轮被"不改日期"改写
        assertThat(dates.get(0).path("quote").asText())
                .isEqualTo("列出其下所有任务的标题、状态、截止日期、依赖数量");
    }

    /**
     * 用户显式更正与旧状态的优先级：更正不解析语义、不改写 activeGoal（新目标仍走
     * 显式"新目标："分支），但必须登记事实，供读取端声明"冲突内容以更正为准"。
     */
    @Test
    void explicitCorrectionIsRecordedWithSourceAndKeepsLatest() {
        Fixture f = fixture();
        AgentRunView run = start(f, "项目目标:2026-10-31 前完成灰度发布");
        submit(run, "更正一个关键信息:经过评审,交付目标从 2026-10-31 提前到 2026-10-24。预算约束不变。");

        JsonNode state = state(run);
        JsonNode corrections = state.path("goalCorrections");
        assertThat(corrections.isArray()).isTrue();
        assertThat(corrections.size()).isEqualTo(1);
        JsonNode latest = corrections.get(corrections.size() - 1);
        assertThat(latest.path("quote").asText()).contains("更正");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_message WHERE id=?::uuid AND role='USER'",
                Integer.class, latest.path("sourceMessageId").asText())).isEqualTo(1);

        // 后续无更正的请求不追加、不丢失
        submit(run, "普通追问：当前进度如何？");
        assertThat(state(run).path("goalCorrections").size()).isEqualTo(1);
        // 旧值仍然 active 的约束不受更正影响（更正优先级由读取端声明）
        assertThat(state(run).path("activeGoal").asText()).isEqualTo("项目目标:2026-10-31 前完成灰度发布");
    }
}
