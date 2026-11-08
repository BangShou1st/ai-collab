package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.planning.api.CreateTaskPlanRequest;
import com.shitulelv.aicollab.planning.domain.PlanMilestone;
import com.shitulelv.aicollab.planning.domain.PlanTask;
import com.shitulelv.aicollab.planning.domain.PlanSource;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftNormalizer;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftValidator;
import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.domain.ValidationResult;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanEventRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanIssueRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord;
import com.shitulelv.aicollab.planning.infrastructure.ai.TaskPlanRuntimeProperties;
import com.shitulelv.aicollab.planning.infrastructure.tool.PlanningReadOnlyTool;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * P2 agentic 规划 Testcontainers 集成测试（设计文档 §5.2/§5.3/§5.4）：
 * REFLECT 硬伤修复链、repair_count=2 终态、软伤 READY_WITH_ISSUES、畸形反思降级、
 * 反思开关、动态批次无跨批依赖边、工具调用持久化。
 */
@Testcontainers(disabledWithoutDocker = true)
class TaskPlanAgenticPlanningPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    static JdbcTemplate jdbc;
    static TaskPlanRepository repository;
    static ObjectMapper json;
    static TaskPlanOutputParser parser;
    static TaskPlanDraftValidator validator;
    static PlanningPromptPolicy promptPolicy;
    static GenerationOutcomeDecider outcomeDecider;
    static TaskPlanDraftNormalizer normalizer;
    static TaskPlanVersionCommitService commitService;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        json = new ObjectMapper().findAndRegisterModules();
        repository = new TaskPlanRepository(jdbc, json);
        parser = new TaskPlanOutputParser(json);
        validator = new TaskPlanDraftValidator();
        promptPolicy = new PlanningPromptPolicy();
        outcomeDecider = new GenerationOutcomeDecider();
        normalizer = new TaskPlanDraftNormalizer();
        commitService = new TaskPlanVersionCommitService(repository,
                new TaskPlanIssueRepository(jdbc, json), new TaskPlanEventRepository(jdbc, json));
    }

    // ── 模型脚本桩：按 purpose 出队内容并记录 prompt ─────────────────────

    /** purpose → 待返回内容队列；队列空时 REFLECT 返回 reflectDefault，其余 purpose 直接失败（脚本缺口）。 */
    private static TaskPlanModelClient scriptedModel(Map<String, Deque<String>> script,
                                                     String reflectDefault,
                                                     Map<String, List<String>> capturedPrompts) {
        TaskPlanModelClient model = mock(TaskPlanModelClient.class);
        doAnswer(invocation -> {
            String purpose = invocation.getArgument(2);
            String prompt = invocation.getArgument(1);
            capturedPrompts.computeIfAbsent(purpose, ignored -> new ArrayList<>()).add(prompt);
            Deque<String> queue = script.get(purpose);
            if (queue == null || queue.isEmpty()) {
                if ("TASK_PLAN_REFLECT".equals(purpose)) return new GenerationResult(
                        reflectDefault, "p", "m", 10, 10, 10);
                throw new IllegalStateException("模型脚本未为 purpose " + purpose + " 提供内容");
            }
            return new GenerationResult(queue.poll(), "p", "m", 10, 10, 10);
        }).when(model).generate(anyString(), anyString(), anyString(), any(), any(), any(), any(),
                any(JsonNode.class));
        return model;
    }

    private static TaskPlanContextAssembler stubbedContexts(UUID projectId, PlanSource source) {
        TaskPlanContextAssembler contexts = mock(TaskPlanContextAssembler.class);
        whenAssemble(contexts, source);
        when(contexts.memberSnapshot(any())).thenReturn(
                new TaskPlanContextAssembler.MemberSnapshot("成员列表", Set.of()));
        return contexts;
    }

    private static void whenAssemble(TaskPlanContextAssembler contexts, PlanSource source) {
        org.mockito.Mockito.when(contexts.assemble(any())).thenReturn(
                new TaskPlanContextAssembler.PlanningContext("<PROJECT_DATA>test</PROJECT_DATA>",
                        source == null ? List.of() : List.of(source)));
    }

    /** 从详情 prompt 的 DETAIL_BATCH_SCOPE 动态构造该批的详情输出（批次无关的通用桩）。 */
    private static String detailResponseFromScope(String prompt, Map<String, String[]> taskDates) throws Exception {
        int open = prompt.indexOf("<DETAIL_BATCH_SCOPE>");
        int close = prompt.indexOf("</DETAIL_BATCH_SCOPE>");
        JsonNode scope = json.readTree(prompt.substring(open + "<DETAIL_BATCH_SCOPE>".length(), close).trim());
        ArrayNode milestones = json.createArrayNode();
        for (JsonNode key : scope.get("milestoneKeys")) {
            ObjectNode m = milestones.addObject();
            m.put("tempKey", key.asText());
            m.put("description", "Detail of " + key.asText());
            m.putArray("sourceRefs");
        }
        ArrayNode tasks = json.createArrayNode();
        for (JsonNode key : scope.get("taskKeys")) {
            String k = key.asText();
            String[] dates = taskDates.getOrDefault(k, new String[]{
                    LocalDate.now().plusDays(2).toString(), LocalDate.now().plusDays(10).toString()});
            ObjectNode t = tasks.addObject();
            t.put("tempKey", k);
            t.put("description", "Detail of " + k);
            t.put("priority", "MEDIUM");
            t.put("estimatedHours", 8.0);
            t.put("startDate", dates[0]);
            t.put("dueDate", dates[1]);
            t.putNull("suggestedAssigneeId");
            t.putArray("dependencyTempKeys");
            t.putArray("sourceRefs");
        }
        ObjectNode payload = json.createObjectNode();
        payload.set("milestones", milestones);
        payload.set("tasks", tasks);
        return json.writeValueAsString(payload);
    }

    private static String hardIssue(String tempKey, String field, String suggestion) {
        return "{\"issues\":[{\"severity\":\"HARD\",\"targetType\":\"TASK\",\"targetTempKey\":\"" + tempKey
                + "\",\"field\":\"" + field + "\",\"suggestion\":\"" + suggestion + "\"}]}";
    }

    private static String dueDatePatch(String tempKey, String date) {
        return "{\"milestonePatches\":[],\"taskPatches\":[{\"tempKey\":\"" + tempKey
                + "\",\"dueDate\":\"" + date + "\"}]}";
    }

    private static String latestValidationJson(UUID planId) {
        return jdbc.queryForObject("""
                SELECT v.validation_result_json::text FROM ai_task_plan_version v
                WHERE v.plan_id=? ORDER BY v.version_no DESC LIMIT 1
                """, String.class, planId);
    }

    private static List<Map<String, Object>> attempts(UUID planId, String stage) {
        return jdbc.queryForList("""
                SELECT stage,status,repair_count,error_code FROM ai_task_plan_attempt
                WHERE plan_id=? AND stage=? ORDER BY attempt_no
                """, planId, stage);
    }

    // ══════════════════════════════════════════════════════════════════
    // R1: REFLECT 硬伤 → 修复链 → repair_count=2 → 终态 READY
    // ══════════════════════════════════════════════════════════════════

    @Test
    void reflectHardIssuesTriggerRepairChainUpToTwoRepairs() throws Exception {
        UUID userId = insertUser("r1");
        UUID projectId = insertProject("R1 Project", userId);
        insertMember(projectId, userId, "OWNER");

        String skeletonJson = """
                {"summary":"Project plan","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"Phase 1","objective":"Design","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"Task 1","objective":"Do design","sortOrder":0}]}
                """;
        String detailJson = """
                {"milestones":[{"tempKey":"m1","description":"Design phase","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"Complete design","priority":"HIGH",
                "estimatedHours":8.0,"startDate":"$S","dueDate":"$D",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """.replace("$S", LocalDate.now().plusDays(2).toString())
                .replace("$D", LocalDate.now().plusDays(10).toString());
        // 反思两轮都报 HARD（dueDate），第三轮通过；修复 patch 各调整一次 dueDate
        Map<String, Deque<String>> script = new HashMap<>();
        script.put("TASK_PLAN_SKELETON", new ArrayDeque<>(List.of(skeletonJson)));
        script.put("TASK_PLAN_DETAIL", new ArrayDeque<>(List.of(detailJson)));
        script.put("TASK_PLAN_REFLECT", new ArrayDeque<>(List.of(
                hardIssue("t1", "dueDate", "截止日期与依赖不一致"),
                hardIssue("t1", "dueDate", "仍有依赖冲突"),
                "{\"issues\":[]}")));
        script.put("TASK_PLAN_REFLECT_REPAIR", new ArrayDeque<>(List.of(
                dueDatePatch("t1", LocalDate.now().plusDays(12).toString()),
                dueDatePatch("t1", LocalDate.now().plusDays(13).toString()))));
        Map<String, List<String>> captured = new HashMap<>();
        TaskPlanModelClient model = scriptedModel(script, "{\"issues\":[]}", captured);

        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                Runnable::run, repository, model, parser, validator, json,
                stubbedContexts(projectId, null), promptPolicy, outcomeDecider, normalizer, commitService);
        TaskPlanRecord plan = repository.create(projectId, userId, new CreateTaskPlanRequest(
                "R1 Plan", "Goal", null, LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of()));
        orchestrator.dispatch(plan, userId, false);

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status())
                .as(() -> "lastError=" + finalPlan.lastErrorCode() + " / " + finalPlan.lastErrorSummary())
                .isEqualTo(TaskPlanStatus.READY);
        // 修复链：恰好 2 个 REPAIR attempt，repair_count 沿链为 1 和 2（V84 CHECK 0..2）
        List<Map<String, Object>> repairs = attempts(plan.id(), "REPAIR");
        assertThat(repairs).hasSize(2);
        assertThat(repairs.stream().map(row -> ((Number) row.get("repair_count")).intValue()).toList())
                .containsExactly(1, 2);
        // 反思轮次：3 次 REFLECT（初始 + 每次修复后再验证）
        assertThat(attempts(plan.id(), "REFLECT")).hasSize(3);
        // 无残留 issues → READY，版本 JSON 不含 issues 字段
        assertThat(latestValidationJson(plan.id())).doesNotContain("\"issues\"");
        // 模型调用次数：骨架1 + 详情1 + 反思3 + 修复2 = 7
        assertThat(captured.values().stream().mapToInt(List::size).sum()).isEqualTo(7);
    }

    // ══════════════════════════════════════════════════════════════════
    // R2: repair_count=2 用尽后仍有硬伤 → 终态 READY_WITH_ISSUES + issues 进版本 JSON
    // ══════════════════════════════════════════════════════════════════

    @Test
    void residualHardIssuesAfterRepairBudgetLandReadyWithIssues() throws Exception {
        UUID userId = insertUser("r2");
        UUID projectId = insertProject("R2 Project", userId);
        insertMember(projectId, userId, "OWNER");

        String skeletonJson = """
                {"summary":"Project plan","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"Phase 1","objective":"Design","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"Task 1","objective":"Do design","sortOrder":0}]}
                """;
        String detailJson = """
                {"milestones":[{"tempKey":"m1","description":"Design phase","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"Complete design","priority":"HIGH",
                "estimatedHours":8.0,"startDate":"$S","dueDate":"$D",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """.replace("$S", LocalDate.now().plusDays(2).toString())
                .replace("$D", LocalDate.now().plusDays(10).toString());
        // 反思始终报同一个 HARD；两次修复后（预算 2 用尽）仍存在 → 不阻塞 READY，落 READY_WITH_ISSUES
        Map<String, Deque<String>> script = new HashMap<>();
        script.put("TASK_PLAN_SKELETON", new ArrayDeque<>(List.of(skeletonJson)));
        script.put("TASK_PLAN_DETAIL", new ArrayDeque<>(List.of(detailJson)));
        script.put("TASK_PLAN_REFLECT", new ArrayDeque<>(List.of()));
        script.put("TASK_PLAN_REFLECT_REPAIR", new ArrayDeque<>(List.of(
                dueDatePatch("t1", LocalDate.now().plusDays(12).toString()),
                dueDatePatch("t1", LocalDate.now().plusDays(13).toString()))));
        Map<String, List<String>> captured = new HashMap<>();
        TaskPlanModelClient model = scriptedModel(script,
                hardIssue("t1", "dueDate", "始终冲突"), captured);

        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                Runnable::run, repository, model, parser, validator, json,
                stubbedContexts(projectId, null), promptPolicy, outcomeDecider, normalizer, commitService);
        TaskPlanRecord plan = repository.create(projectId, userId, new CreateTaskPlanRequest(
                "R2 Plan", "Goal", null, LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of()));
        orchestrator.dispatch(plan, userId, false);

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status()).isEqualTo(TaskPlanStatus.READY_WITH_ISSUES);
        // 修复预算全局 2：第 3 次修复被 startRepair 拒绝（null），不再产生 REPAIR attempt
        assertThat(attempts(plan.id(), "REPAIR")).hasSize(2);
        // issues 写进版本 JSON（顶层 issues 字段，为前端展示预留），HARD 残留可见
        String validationJson = latestValidationJson(plan.id());
        assertThat(validationJson).contains("\"issues\"");
        assertThat(validationJson).contains("HARD");
        assertThat(validationJson).contains("dueDate");
    }

    // ══════════════════════════════════════════════════════════════════
    // R3: 反思软伤 → READY_WITH_ISSUES，issues 进版本 JSON
    // ══════════════════════════════════════════════════════════════════

    @Test
    void reflectSoftIssuesLandReadyWithIssuesWithIssuesInVersionJson() throws Exception {
        UUID userId = insertUser("r3");
        UUID projectId = insertProject("R3 Project", userId);
        insertMember(projectId, userId, "OWNER");

        String skeletonJson = """
                {"summary":"Project plan","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"Phase 1","objective":"Design","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"Task 1","objective":"Do design","sortOrder":0}]}
                """;
        String detailJson = """
                {"milestones":[{"tempKey":"m1","description":"Design phase","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"Complete design","priority":"HIGH",
                "estimatedHours":8.0,"startDate":"$S","dueDate":"$D",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """.replace("$S", LocalDate.now().plusDays(2).toString())
                .replace("$D", LocalDate.now().plusDays(10).toString());
        String softIssue = """
                {"issues":[{"severity":"SOFT","targetType":"TASK","targetTempKey":"t1","field":"estimatedHours",
                "suggestion":"粒度偏粗，建议拆分"}]}
                """;
        Map<String, Deque<String>> script = new HashMap<>();
        script.put("TASK_PLAN_SKELETON", new ArrayDeque<>(List.of(skeletonJson)));
        script.put("TASK_PLAN_DETAIL", new ArrayDeque<>(List.of(detailJson)));
        script.put("TASK_PLAN_REFLECT", new ArrayDeque<>(List.of(softIssue)));
        Map<String, List<String>> captured = new HashMap<>();
        TaskPlanModelClient model = scriptedModel(script, "{\"issues\":[]}", captured);

        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                Runnable::run, repository, model, parser, validator, json,
                stubbedContexts(projectId, null), promptPolicy, outcomeDecider, normalizer, commitService);
        TaskPlanRecord plan = repository.create(projectId, userId, new CreateTaskPlanRequest(
                "R3 Plan", "Goal", null, LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of()));
        orchestrator.dispatch(plan, userId, false);

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status()).isEqualTo(TaskPlanStatus.READY_WITH_ISSUES);
        // 软伤不触发修复链
        assertThat(attempts(plan.id(), "REPAIR")).isEmpty();
        assertThat(attempts(plan.id(), "REFLECT")).hasSize(1);
        // issues 进版本 JSON 供前端展示
        String validationJson = latestValidationJson(plan.id());
        assertThat(validationJson).contains("\"issues\"");
        assertThat(validationJson).contains("SOFT");
        assertThat(validationJson).contains("estimatedHours");
    }

    // ══════════════════════════════════════════════════════════════════
    // R4: REFLECT 畸形输出 → 降级不失败整个计划（fail-open + diagnostics 记录降级原因）
    // ══════════════════════════════════════════════════════════════════

    @Test
    void malformedReflectOutputDegradesWithoutFailingPlan() throws Exception {
        UUID userId = insertUser("r4");
        UUID projectId = insertProject("R4 Project", userId);
        insertMember(projectId, userId, "OWNER");

        String skeletonJson = """
                {"summary":"Project plan","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"Phase 1","objective":"Design","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"Task 1","objective":"Do design","sortOrder":0}]}
                """;
        String detailJson = """
                {"milestones":[{"tempKey":"m1","description":"Design phase","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"Complete design","priority":"HIGH",
                "estimatedHours":8.0,"startDate":"$S","dueDate":"$D",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """.replace("$S", LocalDate.now().plusDays(2).toString())
                .replace("$D", LocalDate.now().plusDays(10).toString());
        Map<String, Deque<String>> script = new HashMap<>();
        script.put("TASK_PLAN_SKELETON", new ArrayDeque<>(List.of(skeletonJson)));
        script.put("TASK_PLAN_DETAIL", new ArrayDeque<>(List.of(detailJson)));
        script.put("TASK_PLAN_REFLECT", new ArrayDeque<>(List.of("这不是 JSON，模型输出畸形")));
        Map<String, List<String>> captured = new HashMap<>();
        TaskPlanModelClient model = scriptedModel(script, "{\"issues\":[]}", captured);

        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                Runnable::run, repository, model, parser, validator, json,
                stubbedContexts(projectId, null), promptPolicy, outcomeDecider, normalizer, commitService);
        TaskPlanRecord plan = repository.create(projectId, userId, new CreateTaskPlanRequest(
                "R4 Plan", "Goal", null, LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of()));
        orchestrator.dispatch(plan, userId, false);

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        // 反思失败不失败整个计划：草稿正常落 READY
        assertThat(finalPlan.status()).isEqualTo(TaskPlanStatus.READY);
        // 反思 attempt 标记 FAILED + 降级原因写入 diagnostics_json.reflect
        List<Map<String, Object>> reflects = attempts(plan.id(), "REFLECT");
        assertThat(reflects).hasSize(1);
        assertThat(reflects.getFirst().get("status")).isEqualTo("FAILED");
        String diagnostics = jdbc.queryForObject(
                "SELECT diagnostics_json::text FROM ai_task_plan_attempt WHERE plan_id=? AND stage='REFLECT'",
                String.class, plan.id());
        assertThat(diagnostics).contains("REFLECT_DEGRADE");
        assertThat(diagnostics).contains("\"degraded\"");
        assertThat(diagnostics).contains("\"reason\"");
        // 无修复链
        assertThat(attempts(plan.id(), "REPAIR")).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════
    // R5: reflect-enabled=false → 跳过 REFLECT 直接提交
    // ══════════════════════════════════════════════════════════════════

    @Test
    void reflectDisabledSkipsReflectStage() throws Exception {
        UUID userId = insertUser("r5");
        UUID projectId = insertProject("R5 Project", userId);
        insertMember(projectId, userId, "OWNER");

        String skeletonJson = """
                {"summary":"Project plan","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"Phase 1","objective":"Design","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"Task 1","objective":"Do design","sortOrder":0}]}
                """;
        String detailJson = """
                {"milestones":[{"tempKey":"m1","description":"Design phase","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"Complete design","priority":"HIGH",
                "estimatedHours":8.0,"startDate":"$S","dueDate":"$D",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """.replace("$S", LocalDate.now().plusDays(2).toString())
                .replace("$D", LocalDate.now().plusDays(10).toString());
        Map<String, Deque<String>> script = new HashMap<>();
        script.put("TASK_PLAN_SKELETON", new ArrayDeque<>(List.of(skeletonJson)));
        script.put("TASK_PLAN_DETAIL", new ArrayDeque<>(List.of(detailJson)));
        Map<String, List<String>> captured = new HashMap<>();
        TaskPlanModelClient model = scriptedModel(script, "{\"issues\":[]}", captured);

        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                Runnable::run, repository, model, parser, validator, json,
                stubbedContexts(projectId, null), promptPolicy, outcomeDecider, normalizer, commitService);
        ReflectionTestUtils.setField(orchestrator, "runtimeProperties",
                new TaskPlanRuntimeProperties(false));
        TaskPlanRecord plan = repository.create(projectId, userId, new CreateTaskPlanRequest(
                "R5 Plan", "Goal", null, LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of()));
        orchestrator.dispatch(plan, userId, false);

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status()).isEqualTo(TaskPlanStatus.READY);
        assertThat(attempts(plan.id(), "REFLECT")).isEmpty();
        // 模型调用恰好 2 次（骨架+详情），无反思调用
        assertThat(captured.values().stream().mapToInt(List::size).sum()).isEqualTo(2);
    }

    // ══════════════════════════════════════════════════════════════════
    // R6: 动态批次——链式依赖 t1→t2→t3 + 独立 t4 全程无批内依赖边、全覆盖
     // ══════════════════════════════════════════════════════════════════

    @Test
    void dynamicBatchesKeepDependencyEdgesAcrossBatches() throws Exception {
        UUID userId = insertUser("r6");
        UUID projectId = insertProject("R6 Project", userId);
        insertMember(projectId, userId, "OWNER");

        // O5 式种子：手动落一版带依赖的"最新草稿"（模拟用户编辑过的完整版本），
        // detail-only 重试继承该草稿的依赖图做动态切批
        TaskPlanRecord plan = repository.create(projectId, userId, new CreateTaskPlanRequest(
                "R6 Plan", "Goal", null, LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of()));
        Map<String, String[]> dates = Map.of(
                "t1", new String[]{LocalDate.now().plusDays(2).toString(), LocalDate.now().plusDays(4).toString()},
                "t2", new String[]{LocalDate.now().plusDays(5).toString(), LocalDate.now().plusDays(7).toString()},
                "t3", new String[]{LocalDate.now().plusDays(8).toString(), LocalDate.now().plusDays(10).toString()},
                "t4", new String[]{LocalDate.now().plusDays(11).toString(), LocalDate.now().plusDays(13).toString()});
        TaskPlanDraft seeded = new TaskPlanDraft(
                "Project plan", List.of(), List.of(),
                List.of(new PlanMilestone("m1", "Phase 1", "Design", "desc",
                        LocalDate.now().plusDays(20), 0, List.of())),
                List.of(
                        new PlanTask("t1", "m1", "Task 1", "Do 1", "d", "HIGH",
                                java.math.BigDecimal.valueOf(8), LocalDate.now().plusDays(2),
                                LocalDate.now().plusDays(4), null, null, List.of(), List.of(), 0),
                        new PlanTask("t2", "m1", "Task 2", "Do 2", "d", "HIGH",
                                java.math.BigDecimal.valueOf(8), LocalDate.now().plusDays(5),
                                LocalDate.now().plusDays(7), null, null, List.of("t1"), List.of(), 1),
                        new PlanTask("t3", "m1", "Task 3", "Do 3", "d", "HIGH",
                                java.math.BigDecimal.valueOf(8), LocalDate.now().plusDays(8),
                                LocalDate.now().plusDays(10), null, null, List.of("t2"), List.of(), 2),
                        new PlanTask("t4", "m1", "Task 4", "Do 4", "d", "HIGH",
                                java.math.BigDecimal.valueOf(8), LocalDate.now().plusDays(11),
                                LocalDate.now().plusDays(13), null, null, List.of(), List.of(), 3)),
                List.of());
        repository.appendGeneratedVersion(projectId, plan.id(), plan.generationSeq(), plan.activeAttemptId(),
                TaskPlanStatus.SKELETON_GENERATING, "AI_SKELETON", null, seeded, userId,
                new ValidationResult(List.of(), List.of()), TaskPlanStatus.DETAIL_GENERATING);
        UUID detailAttempt = repository.startDetailAfterSkeleton(projectId, plan.id(), userId);
        TaskPlanRecord detailPlan = repository.require(projectId, plan.id());

        Map<String, Deque<String>> script = new HashMap<>();
        Map<String, List<String>> captured = new HashMap<>();
        TaskPlanModelClient model = scriptedModel(script, "{\"issues\":[]}", captured);
        doAnswer(invocation -> {
            String purpose = invocation.getArgument(2);
            String prompt = invocation.getArgument(1);
            if ("TASK_PLAN_DETAIL_BATCH".equals(purpose) || "TASK_PLAN_DETAIL".equals(purpose)) {
                captured.computeIfAbsent(purpose, ignored -> new ArrayList<>()).add(prompt);
                return new GenerationResult(detailResponseFromScope(prompt, dates), "p", "m", 10, 10, 10);
            }
            Deque<String> queue = script.get(purpose);
            return new GenerationResult(queue != null && !queue.isEmpty() ? queue.poll() : "{\"issues\":[]}",
                    "p", "m", 10, 10, 10);
        }).when(model).generate(anyString(), anyString(), anyString(), any(), any(), any(), any(),
                any(JsonNode.class));

        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                Runnable::run, repository, model, parser, validator, json,
                stubbedContexts(projectId, null), promptPolicy, outcomeDecider, normalizer, commitService);
        orchestrator.dispatch(detailPlan, userId, true);

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status())
                .as(() -> "lastError=" + finalPlan.lastErrorCode() + " / " + finalPlan.lastErrorSummary())
                .isEqualTo(TaskPlanStatus.READY);

        // 依赖拓扑把链拆成多批：t2 不能与 t1 同批，t3 不能与 t2 同批
        List<String> batchPrompts = captured.get("TASK_PLAN_DETAIL_BATCH");
        assertThat(batchPrompts).as("链式依赖应触发分批").isNotNull();
        Set<String> seen = new HashSet<>();
        List<Set<String>> batchKeys = new ArrayList<>();
        for (String prompt : batchPrompts) {
            int open = prompt.indexOf("<DETAIL_BATCH_SCOPE>");
            int close = prompt.indexOf("</DETAIL_BATCH_SCOPE>");
            JsonNode scope = json.readTree(prompt.substring(open + "<DETAIL_BATCH_SCOPE>".length(), close).trim());
            Set<String> keys = new HashSet<>();
            scope.get("taskKeys").forEach(key -> {
                assertThat(seen.add(key.asText())).as("任务 %s 不得出现在两个批次", key.asText()).isTrue();
                keys.add(key.asText());
            });
            batchKeys.add(keys);
        }
        assertThat(seen).containsExactlyInAnyOrder("t1", "t2", "t3", "t4");
        // 依赖边 (t2→t1)、(t3→t2) 的两端都不得同批
        assertThat(batchOf(batchKeys, "t1")).isNotEqualTo(batchOf(batchKeys, "t2"));
        assertThat(batchOf(batchKeys, "t2")).isNotEqualTo(batchOf(batchKeys, "t3"));
        // 无依赖的 t4 与 t1 合并同批（动态批次合并能力）
        assertThat(batchOf(batchKeys, "t4")).isEqualTo(batchOf(batchKeys, "t1"));
    }

    private static int batchOf(List<Set<String>> batches, String key) {
        for (int i = 0; i < batches.size(); i++) {
            if (batches.get(i).contains(key)) return i;
        }
        return -1;
    }

    // ══════════════════════════════════════════════════════════════════
    // R7: 工具接地——骨架阶段工具调用结果注入下一轮 prompt，并持久化进 diagnostics_json
    // ══════════════════════════════════════════════════════════════════

    @Test
    void skeletonToolCallsAreInjectedAndPersistedInDiagnostics() throws Exception {
        UUID userId = insertUser("r7");
        UUID projectId = insertProject("R7 Project", userId);
        insertMember(projectId, userId, "OWNER");

        String skeletonJson = """
                {"summary":"Project plan","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"Phase 1","objective":"Design","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"Task 1","objective":"Do design","sortOrder":0}]}
                """;
        String toolCall = "<TOOL_CALL>{\"tool\":\"get_project_milestones\",\"arguments\":{}}</TOOL_CALL>";
        String detailJson = """
                {"milestones":[{"tempKey":"m1","description":"Design phase","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"Complete design","priority":"HIGH",
                "estimatedHours":8.0,"startDate":"$S","dueDate":"$D",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """.replace("$S", LocalDate.now().plusDays(2).toString())
                .replace("$D", LocalDate.now().plusDays(10).toString());
        Map<String, Deque<String>> script = new HashMap<>();
        script.put("TASK_PLAN_SKELETON", new ArrayDeque<>(List.of(toolCall, skeletonJson)));
        script.put("TASK_PLAN_DETAIL", new ArrayDeque<>(List.of(detailJson)));
        Map<String, List<String>> captured = new HashMap<>();
        TaskPlanModelClient model = scriptedModel(script, "{\"issues\":[]}", captured);

        // 测试桩工具（生产由 PlanningToolRegistry 注入四个真实只读工具）
        PlanningReadOnlyTool stubMilestones = new PlanningReadOnlyTool() {
            @Override public String name() { return "get_project_milestones"; }
            @Override public String description() { return "列出项目里程碑"; }
            @Override public JsonNode execute(JsonNode arguments, UUID pid, UUID uid) {
                ObjectNode data = json.createObjectNode();
                data.put("total", 0);
                data.putArray("milestones");
                return data;
            }
        };
        PlanningToolLoop toolLoop = new PlanningToolLoop(
                new com.shitulelv.aicollab.planning.infrastructure.tool.PlanningToolRegistry(List.of(stubMilestones)),
                json);

        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                Runnable::run, repository, model, parser, validator, json,
                stubbedContexts(projectId, null), promptPolicy, outcomeDecider, normalizer, commitService);
        ReflectionTestUtils.setField(orchestrator, "toolLoop", toolLoop);
        TaskPlanRecord plan = repository.create(projectId, userId, new CreateTaskPlanRequest(
                "R7 Plan", "Goal", null, LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of()));
        orchestrator.dispatch(plan, userId, false);

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status())
                .as(() -> "lastError=" + finalPlan.lastErrorCode() + " / " + finalPlan.lastErrorSummary())
                .isEqualTo(TaskPlanStatus.READY);
        // 第二轮骨架 prompt 注入了工具结果
        List<String> skeletonPrompts = captured.get("TASK_PLAN_SKELETON");
        assertThat(skeletonPrompts).hasSize(2);
        assertThat(skeletonPrompts.get(1)).contains("<TOOL_RESULT name=\"get_project_milestones\"");
        // 工具调用持久化进骨架 attempt 的 diagnostics_json（可审计）
        String diagnostics = jdbc.queryForObject(
                "SELECT diagnostics_json::text FROM ai_task_plan_attempt WHERE plan_id=? AND stage='SKELETON'",
                String.class, plan.id());
        assertThat(diagnostics).contains("TOOL_CALL");
        assertThat(diagnostics).contains("get_project_milestones");
        assertThat(diagnostics).contains("\"ok\"");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static UUID insertUser(String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,display_name)
                VALUES (?,?,?,?)
                """, id, "agentic-test-" + tag, "test-only-hash", "Agentic Test " + tag);
        return id;
    }

    private static UUID insertProject(String name, UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,name,owner_id,created_by,start_date,due_date)
                VALUES (?,?,?,?,?,?)
                """, id, name, ownerId, ownerId,
                LocalDate.now().minusDays(10), LocalDate.now().plusDays(60));
        return id;
    }

    private static void insertMember(UUID projectId, UUID userId, String role) {
        jdbc.update("""
                INSERT INTO project_member(project_id,user_id,role)
                VALUES (?,?,?)
                """, projectId, userId, role);
    }
}
