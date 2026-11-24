package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentPlanningOperationService;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentScenarioRegistry;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.ToolArgumentValidator;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.planning.application.TaskPlanQueryService;
import com.shitulelv.aicollab.project.application.service.AuditLogQueryService;
import com.shitulelv.aicollab.project.application.service.ProjectApplicationService;
import com.shitulelv.aicollab.project.application.service.ProjectDashboardQueryService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.ProjectKnowledgeRetrievalTestSupport;
import com.shitulelv.aicollab.work.application.service.MilestoneApplicationService;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import com.shitulelv.aicollab.work.application.service.WorkReportService;
import com.shitulelv.aicollab.work.application.view.TaskView;
import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真实生产工具实例的契约测试：每个工具的模型可见 Schema 必须匹配 execute 的实际要求
 * （必填字段、类型、默认值、范围），业务依赖用 mock，但 definition/execute 都是真实实现。
 * 不用手写同名 stub 证明 Schema 完整。
 */
class AgentToolContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static AgentToolContext context;

    @BeforeAll
    static void setUp() {
        context = new AgentToolContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SUPERVISOR", false, 0);
    }

    // ---- 真实生产工具实例（业务依赖 mock） ----

    private TaskGetAgentTool taskGet() {
        return new TaskGetAgentTool(mock(TaskApplicationService.class), JSON);
    }

    private KnowledgeSearchAgentTool knowledgeSearch() {
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        when(access.requireMember(any(), any())).thenReturn(
                com.shitulelv.aicollab.project.domain.model.ProjectRole.MEMBER);
        DocumentSearchService search = mock(DocumentSearchService.class);
        when(search.resolveUserDocumentScope(any(), any()))
                .thenReturn(DocumentSearchService.UserDocumentScope.unrestricted());
        when(search.search(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        return new KnowledgeSearchAgentTool(
                ProjectKnowledgeRetrievalTestSupport.legacyBacked(access, search),
                new KnowledgeContextBuilder(), JSON);
    }

    private ProjectQuestionAgentTool questionAlias() {
        return new ProjectQuestionAgentTool(knowledgeSearch());
    }

    private AuditSummaryAgentTool auditSummary() {
        AuditLogQueryService audit = mock(AuditLogQueryService.class);
        when(audit.recentDashboardActivities(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        return new AuditSummaryAgentTool(audit, JSON);
    }

    private WeeklyReportDraftAgentTool weeklyReport() {
        WorkReportService reports = mock(WorkReportService.class);
        when(reports.getWeeklyReport(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new com.shitulelv.aicollab.work.application.view.WeeklyReportView(
                        java.time.LocalDate.now(),
                        java.time.LocalDate.now().minusDays(6),
                        java.time.LocalDate.now(),
                        new com.shitulelv.aicollab.work.application.view.WeeklyReportView.TaskStatistics(
                                0, 0, 0, 0, java.math.BigDecimal.ZERO, 0, 0, 0),
                        new com.shitulelv.aicollab.work.application.view.WeeklyReportView.MilestoneStatistics(
                                0, 0, 0, 0, 0, 7),
                        new com.shitulelv.aicollab.work.application.view.WeeklyReportView.PeriodStatistics(
                                0, 0, 0, 0),
                        List.of(), List.of(), List.of(), "test", List.of()));
        return new WeeklyReportDraftAgentTool(reports, JSON);
    }

    private TaskApplicationService tasksWithTask() {
        TaskApplicationService tasks = mock(TaskApplicationService.class);
        UUID taskId = new UUID(0, 1);
        when(tasks.get(any(), eq(taskId), any())).thenReturn(new TaskView(
                taskId, context.projectId(), "真实任务", "完整描述", null, null,
                null, null, TaskStatus.TODO, TaskPriority.MEDIUM,
                null, null, null, 0, 0, List.of(), null));
        return tasks;
    }

    // ---- Schema ↔ execute 一致性：合法参数通过，缺必填/越界被拒 ----

    @Test
    void getTaskAllowsOptionalTaskIdWithPageContextFallback() {
        AgentToolDefinition def = taskGet().definition();
        assertThat(def.inputSchema().path("required").toString()).doesNotContain("taskId");
        assertThat(def.inputSchema().path("properties").path("taskId").path("format").asText())
                .isEqualTo("uuid");
        assertThat(def.inputSchema().path("additionalProperties").asBoolean()).isFalse();

        // 缺 taskId 可通过 Schema，但在既无参数也无页面选中任务时抛出 VALIDATION_ERROR
        assertThat(ToolArgumentValidator.validate(JSON.createObjectNode(), def.inputSchema())).isNull();
        var tool = new TaskGetAgentTool(tasksWithTask(), JSON);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tool.execute(context, JSON.createObjectNode()))
                .isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class)
                .extracting(e -> ((com.shitulelv.aicollab.common.exception.BusinessException) e).getErrorCode())
                .isEqualTo(com.shitulelv.aicollab.common.exception.ErrorCode.VALIDATION_ERROR);

        // 存在页面选中任务时无需显式 taskId 确定性回退读取
        UUID selectedTaskId = new UUID(0, 1);
        AgentToolContext pageCtx = context.withPage(new com.shitulelv.aicollab.agent.domain.model.AgentPageContext(
                "TASK_DETAIL", selectedTaskId, null, null, null, Map.of(), false));
        var fallbackResult = tool.execute(pageCtx, JSON.createObjectNode());
        assertThat(fallbackResult.data().path("title").asText()).isEqualTo("真实任务");

        // 显式传参优先级高于页面选中
        var result = tool.execute(context, JSON.createObjectNode()
                .put("taskId", selectedTaskId.toString()));
        assertThat(result.data().path("title").asText()).isEqualTo("真实任务");
    }

    @Test
    void knowledgeSearchSchemaDeclaresQueryDefaultLimitAndBounds() {
        AgentToolDefinition def = knowledgeSearch().definition();
        assertThat(def.inputSchema().path("required").toString()).contains("query");
        var limit = def.inputSchema().path("properties").path("limit");
        assertThat(limit.path("default").asInt()).isEqualTo(8);
        assertThat(limit.path("minimum").asInt()).isEqualTo(1);
        assertThat(limit.path("maximum").asInt()).isEqualTo(12);
        var documentIds = def.inputSchema().path("properties").path("documentIds");
        assertThat(documentIds.path("maxItems").asInt()).isEqualTo(20);

        assertThat(ToolArgumentValidator.validate(JSON.createObjectNode(), def.inputSchema()))
                .contains("query");
        assertThat(ToolArgumentValidator.validate(
                JSON.createObjectNode().put("query", "q").put("limit", 13), def.inputSchema()))
                .contains("limit");
        // 合法参数真实执行返回有界来源结构
        var result = knowledgeSearch().execute(context,
                JSON.createObjectNode().put("query", "验收标准"));
        assertThat(result.data().path("coverage").asText()).isEqualTo("RELEVANT_EXCERPTS_ONLY");
        assertThat(result.data().path("fullDocumentRead").asBoolean()).isFalse();
    }

    @Test
    void questionAliasSharesKnowledgeSearchContract() {
        AgentToolDefinition def = questionAlias().definition();
        assertThat(def.inputSchema().path("required").toString()).contains("query");
        assertThat(def.description()).contains("search_project_knowledge");
        // 别名真实执行与主工具一致
        var result = questionAlias().execute(context,
                JSON.createObjectNode().put("query", "q"));
        assertThat(result.data().path("coverage").asText()).isEqualTo("RELEVANT_EXCERPTS_ONLY");
    }

    @Test
    void questionAliasIsRetiredFromActiveExposureButKeptForHistoricalLookup() {
        KnowledgeSearchAgentTool primary = knowledgeSearch();
        ProjectQuestionAgentTool alias = new ProjectQuestionAgentTool(primary);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(primary, alias));

        assertThat(registry.find("answer_project_question_with_sources")).contains(alias);

        var mainCtx = new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "OWNER", false, com.shitulelv.aicollab.agent.domain.model.AgentPageContext.empty(),
                com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits.forScenario("PROJECT_RESEARCH"),
                0, List.of());
        var mainNames = registry.definitionsFor(mainCtx,
                        new com.shitulelv.aicollab.agent.domain.model.builtin.ProjectResearchScenario())
                .stream().map(AgentToolDefinition::name).toList();
        assertThat(mainNames).containsExactly("search_project_knowledge");
        assertThat(mainNames).doesNotContain("answer_project_question_with_sources");

        var childCtx = new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "OWNER", false, com.shitulelv.aicollab.agent.domain.model.AgentPageContext.empty(),
                com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits.forScenario("KNOWLEDGE_RESEARCHER"),
                1, List.of());
        var childScenario = new com.shitulelv.aicollab.agent.domain.model.AgentScenario() {
            @Override public String code() { return "KNOWLEDGE_RESEARCHER"; }
            @Override public String displayName() { return "文档研究助手"; }
            @Override public String description() { return "只读文档检索"; }
            @Override public java.util.Set<String> entryRoutes() { return java.util.Set.of(); }
            @Override public java.util.Set<String> grantedTools() { return DocumentResearchDelegateAgentTool.CHILD_ALLOWED_TOOLS; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean allowWriteTools() { return false; }
        };
        var childNames = registry.definitionsFor(childCtx, childScenario)
                .stream().map(AgentToolDefinition::name).toList();
        assertThat(childNames).containsExactly("search_project_knowledge");
        assertThat(childNames).doesNotContain("answer_project_question_with_sources");
    }

    @Test
    void auditSummarySchemaDeclaresOptionalLimitWithDefault10() {
        AgentToolDefinition def = auditSummary().definition();
        var limit = def.inputSchema().path("properties").path("limit");
        assertThat(limit.path("default").asInt()).isEqualTo(10);
        assertThat(limit.path("minimum").asInt()).isEqualTo(1);
        assertThat(limit.path("maximum").asInt()).isEqualTo(20);
        assertThat(def.inputSchema().has("required")).isFalse();

        // 缺省与边界内参数真实执行
        assertThat(auditSummary().execute(context, JSON.createObjectNode()).data().has("items")).isTrue();
        assertThat(auditSummary().execute(context,
                JSON.createObjectNode().put("limit", 20)).data().has("items")).isTrue();
    }

    @Test
    void weeklyReportSchemaDeclaresOptionalDaysWithDefault7() {
        AgentToolDefinition def = weeklyReport().definition();
        var days = def.inputSchema().path("properties").path("days");
        assertThat(days.path("default").asInt()).isEqualTo(7);
        assertThat(days.path("minimum").asInt()).isEqualTo(1);
        assertThat(days.path("maximum").asInt()).isEqualTo(30);

        var result = weeklyReport().execute(context, JSON.createObjectNode());
        assertThat(result.data().has("taskStatistics")).isTrue();
        assertThat(result.data().has("milestoneStatistics")).isTrue();
    }

    @Test
    void updateTaskProposalSchemaKeepsRevisionPatchPossible() {
        var tool = new UpdateTaskApprovalAgentTool(JSON, VALIDATOR,
                mock(TaskApplicationService.class), mock(com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository.class));
        AgentToolDefinition def = tool.definition();
        // 新建形态：taskId + changes 必填。changes 内不声明 required——
        // x-approval-patch 只在根对象生效，嵌套 required 无法被修订补丁跳过；
        // 新建时的 version 必填由 DTO 校验（UpdateTaskRequest @NotNull version）在执行边界保证
        assertThat(def.inputSchema().path("required").toString()).contains("taskId", "changes");
        var changes = def.inputSchema().path("properties").path("changes");
        assertThat(changes.has("required")).as("嵌套 required 会拒绝合法补丁").isFalse();
        assertThat(changes.path("properties").path("version").path("type").asText()).isEqualTo("integer");

        // 新建（无 approvalId）：version 由 DTO 校验在执行边界拒绝（Schema 不声明嵌套 required），
        // Schema 校验通过，执行抛出参数异常
        assertThat(ToolArgumentValidator.validate(json(
                "{\"taskId\":\"%s\",\"changes\":{\"title\":\"新标题\"}}".formatted(UUID.randomUUID())), def.inputSchema()))
                .isNull();
        var updateTool = new UpdateTaskApprovalAgentTool(JSON, VALIDATOR,
                mock(TaskApplicationService.class),
                mock(com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> updateTool.execute(context, json(
                        "{\"taskId\":\"%s\",\"changes\":{\"title\":\"新标题\"}}".formatted(UUID.randomUUID()))))
                .isInstanceOf(IllegalArgumentException.class);
        // 新建（无 approvalId）：version 存在则通过
        assertThat(ToolArgumentValidator.validate(json(
                "{\"taskId\":\"%s\",\"changes\":{\"title\":\"新标题\",\"version\":3}}".formatted(UUID.randomUUID())), def.inputSchema()))
                .isNull();

        // 修订补丁（带 approvalId）：required 跳过——部分修订在合并前不被 Schema 拒绝
        assertThat(ToolArgumentValidator.validate(json(
                "{\"approvalId\":\"%s\",\"changes\":{\"priority\":\"HIGH\"}}".formatted(UUID.randomUUID())), def.inputSchema()))
                .isNull();
        // 修订补丁仍不允许未知字段
        assertThat(ToolArgumentValidator.validate(json(
                "{\"approvalId\":\"%s\",\"unknownField\":1}".formatted(UUID.randomUUID())), def.inputSchema()))
                .isNotNull();
    }

    @Test
    void milestoneProposalSchemasDeclareBusinessFields() {
        var create = new CreateMilestoneApprovalAgentTool(JSON, VALIDATOR,
                mock(MilestoneApplicationService.class), mock(ProjectApplicationService.class));
        assertThat(create.definition().inputSchema().path("required").toString()).contains("name");

        var update = new UpdateMilestoneApprovalAgentTool(JSON, VALIDATOR,
                mock(MilestoneApplicationService.class));
        AgentToolDefinition def = update.definition();
        assertThat(def.inputSchema().path("required").toString()).contains("milestoneId", "changes");
        assertThat(def.inputSchema().path("properties").path("changes").has("required"))
                .as("嵌套 required 会拒绝合法补丁").isFalse();
        assertThat(def.inputSchema().path("properties").path("changes").path("properties").path("version")
                .path("type").asText()).isEqualTo("integer");
        // 修订补丁带 approvalId 可只提交变化字段
        assertThat(ToolArgumentValidator.validate(json(
                "{\"approvalId\":\"%s\",\"changes\":{\"name\":\"改名\"}}".formatted(UUID.randomUUID())), def.inputSchema()))
                .isNull();
    }

    @Test
    void noParameterToolsDeclareClosedEmptyObjects() {
        var riskReports = mock(WorkReportService.class);
        when(riskReports.getRiskAnalysis(any(), any())).thenReturn(
                new com.shitulelv.aicollab.work.application.view.RiskAnalysisView(
                        List.of(),
                        new com.shitulelv.aicollab.work.application.view.RiskAnalysisView.RiskSummary(
                                0, 0, 0, "无风险", 0, 0, 0),
                        List.of(), "规则检测"));
        for (AgentTool tool : List.of(
                new ProjectOverviewAgentTool(mock(ProjectApplicationService.class), JSON),
                new DashboardAgentTool(mock(ProjectDashboardQueryService.class), JSON),
                new ProjectProgressAgentTool(mock(ProjectDashboardQueryService.class), JSON),
                new ProjectRiskAgentTool(riskReports, JSON))) {
            JsonNode schema = tool.definition().inputSchema();
            assertThat(schema.path("type").asText()).as(tool.name()).isEqualTo("object");
            assertThat(schema.path("additionalProperties").asBoolean()).as(tool.name()).isFalse();
            // 传未知字段在 Schema 校验边界被拒
            assertThat(ToolArgumentValidator.validate(
                    JSON.createObjectNode().put("unexpected", 1), schema))
                    .as(tool.name()).isNotNull();
            // 空参数真实执行
            assertThat(tool.execute(context, JSON.createObjectNode()).data()).isNotNull();
        }
    }

    @Test
    void agentHandoffReadsSelectedPlanWithoutGrantingPlanningWrites() {
        var queries = mock(TaskPlanQueryService.class);
        var planning = new PlanningAgentTools(mock(AgentPlanningOperationService.class), queries, JSON);
        var detail = planning.getTaskPlan();
        var registry = new AgentToolRegistry(List.of(planning.listTaskPlans(), detail,
                planning.getTaskPlanProgress(), planning.startTaskPlan(), planning.repairTaskPlan(),
                planning.cancelTaskPlanGeneration()));
        var scenarios = new AgentScenarioRegistry();
        UUID planId = UUID.randomUUID();
        var page = new AgentPageContext("AGENT", null, null, null, planId, Map.of());
        var ctx = new AgentExecutionContext(context.runId(), UUID.randomUUID(), context.projectId(),
                context.userId(), "ADMIN", false, page, AgentRuntimeLimits.defaults(), 0, List.of());
        var research = scenarios.select(null, page);

        assertThat(research.code()).isEqualTo("PROJECT_RESEARCH");
        assertThat(registry.definitionsFor(ctx, research)).extracting(AgentToolDefinition::name)
                .containsExactly("get_task_plan", "get_task_plan_progress", "list_task_plans");
        var args = JSON.createObjectNode();
        assertThat(ToolArgumentValidator.validate(args, detail.definition().inputSchema())).isNull();
        detail.execute(AgentToolRegistry.toToolContext(ctx), args);
        verify(queries).detail(ctx.projectId(), planId, ctx.requesterId());
        verify(queries).versions(ctx.projectId(), planId, ctx.requesterId());

        var planningPage = new AgentPageContext("PLANNING", null, null, null, planId, Map.of());
        var iteration = scenarios.select(null, planningPage);
        assertThat(iteration.code()).isEqualTo("ITERATION_PLANNING");
        assertThat(registry.definitionsFor(ctx, iteration)).extracting(AgentToolDefinition::name)
                .containsExactly("get_task_plan", "get_task_plan_progress", "list_task_plans");
        assertThat(registry.definitionsFor(ctx, iteration, "ITERATION_PLANNING"))
                .filteredOn(AgentToolDefinition::writesBusinessData).extracting(AgentToolDefinition::name)
                .containsExactly("cancel_task_plan_generation", "repair_task_plan", "start_task_plan");
    }

    @Test
    void approvalToolsCarryRegistryPatchWithoutConflicts() {
        // Registry 对 ApprovalWriteAgentTool 注入 x-approval-patch 与 approvalId 属性；
        // 工具自带 approvalId 属性时不得重复或覆盖
        var registry = new AgentToolRegistry(List.of(
                new UpdateTaskApprovalAgentTool(JSON, VALIDATOR, mock(TaskApplicationService.class),
                        mock(com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository.class))));
        var ctx = new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), context.projectId(), context.userId(),
                "ADMIN", false,
                com.shitulelv.aicollab.agent.domain.model.AgentPageContext.empty(),
                com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits.defaults(), 0, List.of());
        var defs = registry.definitionsFor(ctx, new com.shitulelv.aicollab.agent.domain.model.builtin.IterationPlanningScenario());
        var def = defs.stream().filter(d -> d.name().equals("update_task_after_approval")).findFirst().orElseThrow();
        assertThat(def.inputSchema().path("x-approval-patch").asBoolean()).isTrue();
        assertThat(def.inputSchema().path("properties").path("approvalId").path("type").asText())
                .isEqualTo("string");
        // 同一 Schema 仍然通过修订补丁校验
        assertThat(ToolArgumentValidator.validate(json(
                "{\"approvalId\":\"%s\",\"changes\":{\"title\":\"t\",\"version\":1}}".formatted(UUID.randomUUID())), def.inputSchema()))
                .isNull();
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
