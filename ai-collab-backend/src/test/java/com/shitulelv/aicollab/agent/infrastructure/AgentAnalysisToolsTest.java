package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.infrastructure.tool.ProjectRiskAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.WeeklyReportDraftAgentTool;
import com.shitulelv.aicollab.work.application.service.WorkReportService;
import com.shitulelv.aicollab.work.application.view.RiskAnalysisView;
import com.shitulelv.aicollab.work.application.view.WeeklyReportView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAnalysisToolsTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AgentToolContext context = new AgentToolContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "SUPERVISOR", false, 0);

    @Test
    void weeklyDraftUsesDeterministicReportFacts() {
        WorkReportService reports = mock(WorkReportService.class);
        when(reports.getWeeklyReport(context.projectId(), context.userId(), 7))
                .thenReturn(new WeeklyReportView(
                        LocalDate.of(2026, 7, 29),
                        new WeeklyReportView.TaskStatistics(10, 7, 2, 1, new BigDecimal("0.700")),
                        new WeeklyReportView.MilestoneStatistics(3, 1, 1, 1),
                        List.of(), List.of("完成率达到 70%"), List.of("有 1 个任务已逾期")));

        var result = new WeeklyReportDraftAgentTool(reports, json)
                .execute(context, json.createObjectNode());

        assertThat(result.data().path("reportDate").asText()).isEqualTo("2026-07-29");
        assertThat(result.data().path("taskStatistics").path("completedTasks").asInt()).isEqualTo(7);
        assertThat(result.inferences())
                .extracting(inference -> inference.statement())
                .contains("有 1 个任务已逾期");
    }

    @Test
    void riskToolKeepsCalculatedSeverityAndBasis() {
        WorkReportService reports = mock(WorkReportService.class);
        UUID task = UUID.randomUUID();
        when(reports.getRiskAnalysis(context.projectId(), context.userId()))
                .thenReturn(new RiskAnalysisView(
                        List.of(new RiskAnalysisView.RiskItem(
                                task, "接口联调", "OVERDUE", "HIGH",
                                "任务已逾期 8 天", LocalDate.of(2026, 7, 21), "小李")),
                        new RiskAnalysisView.RiskSummary(
                                1, 0, 0, "项目存在一定风险，需要关注逾期任务和依赖阻塞")));

        var result = new ProjectRiskAgentTool(reports, json)
                .execute(context, json.createObjectNode());

        assertThat(result.data().path("risks").get(0).path("severity").asText()).isEqualTo("HIGH");
        assertThat(result.inferences()).singleElement().satisfies(inference -> {
            assertThat(inference.statement()).contains("项目存在一定风险");
            assertThat(inference.basis()).isEqualTo("确定性风险统计");
        });
    }
}
