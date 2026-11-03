package com.shitulelv.aicollab.work.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 项目洞察业务正确性集成测试（真实 PostgreSQL 17）。
 *
 * <p>覆盖任务书场景：历史已完成任务不计入本期完成、本期完成按 completed_at 事件证据统计、
 * 本期无活动不输出宣传文案、状态多次变化按最终完成时间、已取消不进完成率分母、
 * 周界（含/不含）与时区一致性、逾期+阻塞并存的任务数去重、未分配聚合条目、
 * 即将到期 7 天窗口、里程碑窗口与审计事件还原、规划未应用/应用后编辑/版本变更、
 * 来源键缺失或重复、跨项目隔离。</p>
 *
 * <p>写路径通过 SQL 复刻 {@code TaskMapper.updateScoped} 的 completed_at 维护语义
 * （{@code CASE WHEN 'DONE' THEN COALESCE(completed_at, ?) ELSE NULL END}），保证与真实
 * 应用写入行为一致；被测对象 {@link WorkReportService} 只做读取。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class WorkReportServicePostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final ZoneId ZONE = ZoneId.systemDefault();

    static final UUID OWNER = UUID.fromString("64000000-0000-0000-0000-000000000001");
    static final UUID MEMBER = UUID.fromString("64000000-0000-0000-0000-000000000002");
    static final UUID OUTSIDER = UUID.fromString("64000000-0000-0000-0000-000000000003");
    static final UUID PROJECT_A = UUID.fromString("65000000-0000-0000-0000-000000000001");
    static final UUID PROJECT_B = UUID.fromString("65000000-0000-0000-0000-000000000002");

    /** 固定统计周期：2026-01-05（周一）~ 2026-01-11（周日），与真实"今天"解耦，便于边界断言。 */
    static final LocalDate FIXED_START = LocalDate.of(2026, 1, 5);
    static final LocalDate FIXED_END = LocalDate.of(2026, 1, 11);
    static final OffsetDateTime FIXED_START_TS = FIXED_START.atStartOfDay(ZONE).toOffsetDateTime();
    static final OffsetDateTime FIXED_END_EXCLUSIVE = FIXED_END.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

    static JdbcTemplate jdbc;
    static WorkReportService reports;

    @BeforeAll
    static void migrateAndWire() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        reports = new WorkReportService(access, jdbc, JSON);
    }

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        insertUser(OWNER, "owner", "洞察所有者");
        insertUser(MEMBER, "member", "洞察成员");
        insertUser(OUTSIDER, "outsider", "外部用户");
        insertProject(PROJECT_A, "洞察项目甲", OWNER);
        insertProject(PROJECT_B, "洞察项目乙", OUTSIDER);
        insertMember(PROJECT_A, OWNER, "OWNER");
        insertMember(PROJECT_A, MEMBER, "MEMBER");
        insertMember(PROJECT_B, OUTSIDER, "OWNER");
    }

    // ==================== AI 周报 ====================

    @Test
    void weeklyReportDistinguishesSnapshotFromPeriodEvidence() {
        // 历史已完成任务：创建/完成都在周期之前 → 只进累计快照，不进本期变化
        insertTask(PROJECT_A, "历史任务", "DONE", null, MEMBER,
                FIXED_START_TS.minusDays(40), FIXED_START_TS.minusDays(30), "MEDIUM");
        // 本期内创建并完成
        insertTask(PROJECT_A, "本期完成", "DONE", null, MEMBER,
                FIXED_START_TS.plusHours(1), FIXED_END_EXCLUSIVE.minusHours(1), "HIGH");
        // 本期内创建、尚未完成
        insertTask(PROJECT_A, "本期新建未完成", "IN_PROGRESS", null, MEMBER,
                FIXED_START_TS.plusHours(2), null, "MEDIUM");
        // 本期内创建但已取消：计入本期新建，不进累计分母
        insertTask(PROJECT_A, "本期新建已取消", "CANCELED", null, MEMBER,
                FIXED_START_TS.plusHours(3), null, "LOW");
        // 项目乙的任务不得串入项目甲
        insertTask(PROJECT_B, "乙项目任务", "DONE", null, OUTSIDER,
                FIXED_START_TS.plusHours(1), FIXED_END_EXCLUSIVE.minusHours(1), "MEDIUM");

        var report = reports.getWeeklyReport(PROJECT_A, MEMBER, FIXED_START, FIXED_END);

        assertThat(report.startDate()).isEqualTo(FIXED_START);
        assertThat(report.endDate()).isEqualTo(FIXED_END);
        // 累计快照（不含已取消）
        assertThat(report.taskStats().totalTasks()).isEqualTo(3);
        assertThat(report.taskStats().completedTasks()).isEqualTo(2);
        assertThat(report.taskStats().inProgressTasks()).isEqualTo(1);
        assertThat(report.taskStats().canceledTasks()).isEqualTo(1);
        assertThat(report.taskStats().activeTasks()).isEqualTo(1);
        assertThat(report.taskStats().completionRate()).isEqualByComparingTo("0.667");
        // 本期变化（事件证据）
        assertThat(report.periodStats().createdInPeriod()).isEqualTo(3);
        assertThat(report.periodStats().completedInPeriod()).isEqualTo(1);
        // 文案只基于本期事实
        assertThat(report.highlights()).contains("本期新建任务 3 个", "本期完成任务 1 个");
        assertThat(String.join(";", report.highlights())).doesNotContain("完成率");
        assertThat(report.dataSource()).contains("确定性 SQL 统计");
    }

    @Test
    void weeklyReportNoActivityPeriodOutputsNoPropaganda() {
        insertTask(PROJECT_A, "历史任务", "DONE", null, MEMBER,
                FIXED_START_TS.minusDays(40), FIXED_START_TS.minusDays(30), "MEDIUM");

        var report = reports.getWeeklyReport(PROJECT_A, MEMBER, FIXED_START, FIXED_END);

        assertThat(report.periodStats().createdInPeriod()).isZero();
        assertThat(report.periodStats().completedInPeriod()).isZero();
        // 无数据周期不得输出"项目进展良好"类宣传文案
        assertThat(report.highlights()).isEmpty();
        assertThat(report.risks()).isEmpty();
        assertThat(String.join(";", report.notes())).contains("没有检测到任务或里程碑的新建/完成事件");
    }

    @Test
    void weeklyReportStatusMultiTransitionCountsFinalCompletionOnly() {
        LocalDate start = LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDate end = LocalDate.now();
        UUID reopenForever = insertTask(PROJECT_A, "完成又重开且保持打开", "TODO", null, MEMBER,
                OffsetDateTime.now(ZONE), null, "MEDIUM");
        UUID completedTwice = insertTask(PROJECT_A, "完成又重开又完成", "TODO", null, MEMBER,
                OffsetDateTime.now(ZONE), null, "MEDIUM");

        updateTaskStatus(completedTwice, "DONE");
        updateTaskStatus(completedTwice, "IN_PROGRESS");
        updateTaskStatus(completedTwice, "DONE");
        updateTaskStatus(reopenForever, "DONE");
        updateTaskStatus(reopenForever, "IN_PROGRESS");

        var report = reports.getWeeklyReport(PROJECT_A, MEMBER, start, end);

        // 快照：只有最终处于 DONE 的任务计入
        assertThat(report.taskStats().completedTasks()).isEqualTo(1);
        // 重开任务被 updateScoped 语义清空 completed_at，不冒充"本期完成"
        assertThat(report.periodStats().completedInPeriod()).isEqualTo(1);
        assertThat(report.taskStats().activeTasks()).isEqualTo(1);
        assertThat(report.taskStats().completedWithoutCompletedAtCount()).isZero();
        // 贡献者：本期活动与累计区分
        assertThat(report.topContributors()).hasSize(1);
        assertThat(report.topContributors().getFirst().completedInPeriod()).isEqualTo(1);
        assertThat(report.topContributors().getFirst().createdInPeriod()).isEqualTo(2);
    }

    @Test
    void weeklyReportCanceledTaskExcludedFromCompletionRateDenominator() {
        UUID done = insertTask(PROJECT_A, "已完成", "TODO", null, MEMBER,
                OffsetDateTime.now(ZONE), null, "MEDIUM");
        UUID canceled = insertTask(PROJECT_A, "已取消", "TODO", null, MEMBER,
                OffsetDateTime.now(ZONE), null, "MEDIUM");
        updateTaskStatus(done, "DONE");
        updateTaskStatus(canceled, "CANCELED");

        var report = reports.getWeeklyReport(PROJECT_A, MEMBER);

        assertThat(report.taskStats().totalTasks()).isEqualTo(1);
        assertThat(report.taskStats().canceledTasks()).isEqualTo(1);
        assertThat(report.taskStats().completedTasks()).isEqualTo(1);
        // 完成率分母不含已取消：1/1
        assertThat(report.taskStats().completionRate()).isEqualByComparingTo("1.000");
    }

    @Test
    void weeklyReportDefaultsToCurrentNaturalWeek() {
        var report = reports.getWeeklyReport(PROJECT_A, MEMBER);

        LocalDate today = LocalDate.now();
        assertThat(report.startDate()).isEqualTo(today.with(DayOfWeek.MONDAY));
        assertThat(report.endDate()).isEqualTo(today);
    }

    @Test
    void weeklyReportRejectsInvalidExplicitPeriod() {
        assertThatThrownBy(() -> reports.getWeeklyReport(PROJECT_A, MEMBER, FIXED_END, FIXED_START))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> reports.getWeeklyReport(
                PROJECT_A, MEMBER, FIXED_START, FIXED_START.plusDays(63)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void weeklyReportPeriodBoundariesAreInclusiveStartExclusiveEnd() {
        // 恰好落在周期起始时刻（含）→ 计入本期新建
        insertTask(PROJECT_A, "起始边界内", "TODO", null, MEMBER, FIXED_START_TS, null, "LOW");
        // 比起始时刻早 1 秒（不含）→ 不计入本期新建
        insertTask(PROJECT_A, "起始边界外", "TODO", null, MEMBER, FIXED_START_TS.minusSeconds(1), null, "LOW");
        // 完成于期末前 1 秒（含）→ 计入本期完成
        insertTask(PROJECT_A, "期末边界内", "DONE", null, MEMBER,
                FIXED_START_TS.minusDays(30), FIXED_END_EXCLUSIVE.minusSeconds(1), "LOW");
        // 完成于期末时刻（不含，属于下一期）→ 不计入本期完成，只进累计快照
        insertTask(PROJECT_A, "期末边界外", "DONE", null, MEMBER,
                FIXED_START_TS.minusDays(30), FIXED_END_EXCLUSIVE, "LOW");

        var report = reports.getWeeklyReport(PROJECT_A, MEMBER, FIXED_START, FIXED_END);

        assertThat(report.periodStats().createdInPeriod()).isEqualTo(1);
        assertThat(report.periodStats().completedInPeriod()).isEqualTo(1);
        assertThat(report.taskStats().completedTasks()).isEqualTo(2);
        assertThat(report.taskStats().totalTasks()).isEqualTo(4);
    }

    @Test
    void weeklyReportMilestoneWindowAndAuditPeriodEvidence() {
        LocalDate today = LocalDate.now();
        UUID completedMilestone = insertMilestone(PROJECT_A, "已完里程碑", today.minusDays(5), "COMPLETED");
        UUID rolledBackMilestone = insertMilestone(PROJECT_A, "回退里程碑", today.plusDays(20), "PLANNED");
        insertMilestone(PROJECT_A, "即将到期", today.plusDays(3), "ACTIVE");
        insertMilestone(PROJECT_A, "远期里程碑", today.plusDays(30), "PLANNED");
        insertMilestone(PROJECT_A, "已逾期", today.minusDays(2), "ACTIVE");
        insertMilestone(PROJECT_A, "已取消里程碑", today.minusDays(1), "CANCELED");

        // 本期审计事件：MILESTONE_CREATED ×1；MILESTONE_UPDATED 到达 COMPLETED ×2
        // （其一当前仍是 PLANNED，说明回退过，不得计入本期完成）
        insertAudit(PROJECT_A, completedMilestone, "MILESTONE_UPDATED",
                "{\"status\":\"COMPLETED\"}", OffsetDateTime.now(ZONE));
        insertAudit(PROJECT_A, rolledBackMilestone, "MILESTONE_UPDATED",
                "{\"status\":\"COMPLETED\"}", OffsetDateTime.now(ZONE));
        insertAudit(PROJECT_A, UUID.randomUUID(), "MILESTONE_CREATED",
                "{\"status\":\"PLANNED\"}", OffsetDateTime.now(ZONE));
        // 周期之外的完成事件不得计入
        insertAudit(PROJECT_A, completedMilestone, "MILESTONE_UPDATED",
                "{\"status\":\"COMPLETED\"}", FIXED_START_TS.minusDays(1));

        var report = reports.getWeeklyReport(PROJECT_A, MEMBER);

        assertThat(report.milestoneStats().totalMilestones()).isEqualTo(6);
        assertThat(report.milestoneStats().completedMilestones()).isEqualTo(1);
        assertThat(report.milestoneStats().canceledMilestones()).isEqualTo(1);
        assertThat(report.milestoneStats().upcomingMilestones()).isEqualTo(1);
        assertThat(report.milestoneStats().overdueMilestones()).isEqualTo(1);
        assertThat(report.milestoneStats().dueSoonWindowDays()).isEqualTo(7);
        assertThat(report.periodStats().milestoneCreatedInPeriod()).isEqualTo(1);
        assertThat(report.periodStats().milestoneCompletedInPeriod()).isEqualTo(1);
    }

    // ==================== 延期风险分析 ====================

    @Test
    void riskAnalysisCoversOverdueAndDueSoonWindowWithExclusions() {
        insertTask(PROJECT_A, "逾期 1 天", "IN_PROGRESS", LocalDate.now().minusDays(1), MEMBER, null, "MEDIUM");
        insertTask(PROJECT_A, "逾期 4 天", "IN_PROGRESS", LocalDate.now().minusDays(4), MEMBER, null, "MEDIUM");
        insertTask(PROJECT_A, "逾期 8 天", "IN_PROGRESS", LocalDate.now().minusDays(8), MEMBER, null, "MEDIUM");
        insertTask(PROJECT_A, "2 天后到期", "TODO", LocalDate.now().plusDays(2), MEMBER, null, "HIGH");
        insertTask(PROJECT_A, "6 天后到期", "TODO", LocalDate.now().plusDays(6), MEMBER, null, "LOW");
        // 窗口外（第 7 天之后）、远期、已完成、已取消、无截止日：均不产生逾期/到期条目
        insertTask(PROJECT_A, "7 天后到期（窗口外）", "TODO", LocalDate.now().plusDays(7), MEMBER, null, "LOW");
        insertTask(PROJECT_A, "远期任务", "TODO", LocalDate.now().plusDays(30), MEMBER, null, "LOW");
        insertTask(PROJECT_A, "已完成逾期任务", "DONE", LocalDate.now().minusDays(9), MEMBER, null, "MEDIUM");
        insertTask(PROJECT_A, "已取消逾期任务", "CANCELED", LocalDate.now().minusDays(9), MEMBER, null, "MEDIUM");
        insertTask(PROJECT_A, "无截止日任务", "TODO", null, MEMBER, null, "MEDIUM");

        var view = reports.getRiskAnalysis(PROJECT_A, MEMBER);

        List<com.shitulelv.aicollab.work.application.view.RiskAnalysisView.RiskItem> overdue =
                view.risks().stream().filter(r -> "OVERDUE".equals(r.riskType())).toList();
        List<com.shitulelv.aicollab.work.application.view.RiskAnalysisView.RiskItem> dueSoon =
                view.risks().stream().filter(r -> "DUE_SOON".equals(r.riskType())).toList();

        assertThat(overdue).hasSize(3);
        assertThat(overdue).extracting(
                        com.shitulelv.aicollab.work.application.view.RiskAnalysisView.RiskItem::severity)
                .containsExactly("HIGH", "MEDIUM", "LOW");
        assertThat(dueSoon).hasSize(2);
        assertThat(dueSoon).extracting(
                        com.shitulelv.aicollab.work.application.view.RiskAnalysisView.RiskItem::severity)
                .containsExactly("MEDIUM", "LOW");
        // 全部条目携带机器可读规则依据
        assertThat(view.risks()).allSatisfy(item -> assertThat(item.rule()).isNotBlank());
        assertThat(view.rules()).extracting(
                        com.shitulelv.aicollab.work.application.view.RiskAnalysisView.RiskRule::code)
                .containsExactlyInAnyOrder("TASK_OVERDUE", "TASK_DUE_SOON", "TASK_BLOCKED", "TASK_UNASSIGNED");
        // 参与检测的活跃任务数：8 个（10 条中已完成/已取消各 1 条不参与）
        assertThat(view.summary().checkedTaskCount()).isEqualTo(8);
        assertThat(view.summary().riskItemCount()).isEqualTo(5);
        assertThat(view.summary().affectedTaskCount()).isEqualTo(5);
        assertThat(view.dataSource()).contains("规则检测");
    }

    @Test
    void riskAnalysisOverdueAndBlockedSameTaskCountedOnceInAffectedTasks() {
        UUID blocker = insertTask(PROJECT_A, "未完成前置任务", "IN_PROGRESS", null, MEMBER, null, "MEDIUM");
        UUID overdueBlocked = insertTask(PROJECT_A, "逾期且被阻塞", "IN_PROGRESS",
                LocalDate.now().minusDays(1), MEMBER, null, "HIGH");
        jdbc.update("INSERT INTO task_dependency(task_id, depends_on_task_id) VALUES (?, ?)",
                overdueBlocked, blocker);

        var view = reports.getRiskAnalysis(PROJECT_A, MEMBER);

        // 同一任务命中 OVERDUE + BLOCKED 两条规则，条目显式列出全部风险类型
        assertThat(view.risks()).hasSize(2);
        assertThat(view.risks()).extracting(
                        com.shitulelv.aicollab.work.application.view.RiskAnalysisView.RiskItem::riskType)
                .containsExactlyInAnyOrder("OVERDUE", "BLOCKED");
        assertThat(view.risks())
                .filteredOn(r -> "OVERDUE".equals(r.riskType()))
                .allSatisfy(r -> assertThat(r.taskId()).isEqualTo(overdueBlocked));
        assertThat(view.risks())
                .filteredOn(r -> "BLOCKED".equals(r.riskType()))
                .allSatisfy(r -> {
                    assertThat(r.taskId()).isEqualTo(overdueBlocked);
                    // 逾期/即将到期窗口内被阻塞 → HIGH
                    assertThat(r.severity()).isEqualTo("HIGH");
                });
        // 条目数 2 vs 涉及任务数 1（去重）
        assertThat(view.summary().riskItemCount()).isEqualTo(2);
        assertThat(view.summary().affectedTaskCount()).isEqualTo(1);
        assertThat(view.summary().checkedTaskCount()).isEqualTo(2);
    }

    @Test
    void riskAnalysisUnassignedAggregatesAndFarFutureBlockedIsMedium() {
        UUID unassignedFarFuture = insertTask(PROJECT_A, "远期被阻塞未分配", "TODO",
                LocalDate.now().plusDays(40), null, null, "MEDIUM");
        UUID blocker = insertTask(PROJECT_A, "前置任务", "TODO", null, MEMBER, null, "MEDIUM");
        jdbc.update("INSERT INTO task_dependency(task_id, depends_on_task_id) VALUES (?, ?)",
                unassignedFarFuture, blocker);
        insertTask(PROJECT_A, "未分配任务一", "TODO", null, null, null, "LOW");
        insertTask(PROJECT_A, "未分配任务二", "TODO", null, null, null, "LOW");

        var view = reports.getRiskAnalysis(PROJECT_A, MEMBER);

        var unassigned = view.risks().stream()
                .filter(r -> "UNASSIGNED".equals(r.riskType())).toList();
        assertThat(unassigned).hasSize(1);
        assertThat(unassigned.getFirst().affectedTaskCount()).isEqualTo(3);
        assertThat(unassigned.getFirst().rule()).isEqualTo("TASK_UNASSIGNED");

        var blocked = view.risks().stream()
                .filter(r -> "BLOCKED".equals(r.riskType())).toList();
        assertThat(blocked).hasSize(1);
        // 截止日远期（窗口外）被阻塞 → MEDIUM
        assertThat(blocked.getFirst().severity()).isEqualTo("MEDIUM");

        assertThat(view.summary().riskItemCount()).isEqualTo(2);
        assertThat(view.summary().affectedTaskCount()).isEqualTo(3);
        assertThat(view.summary().overallAssessment()).contains("风险可控");
    }

    @Test
    void riskAnalysisWithoutActiveTasksStatesFactInsteadOfPraise() {
        var view = reports.getRiskAnalysis(PROJECT_B, OUTSIDER);

        assertThat(view.risks()).isEmpty();
        assertThat(view.summary().checkedTaskCount()).isZero();
        assertThat(view.summary().affectedTaskCount()).isZero();
        assertThat(view.summary().overallAssessment()).contains("没有活跃任务");
        assertThat(view.summary().overallAssessment()).doesNotContain("进展良好");
    }

    // ==================== 规划对比 ====================

    @Test
    void planComparisonNeverAppliedReportsBaselineUnknown() {
        UUID plan = insertPlan(PROJECT_A, "未应用规划", "READY", null, 1, null, null);
        UUID version = insertPlanVersion(plan, 1, planTasksJson(
                planTaskJson("t1", "规划任务一", "HIGH", null, "2026-01-05", "2026-01-10")));

        var view = reports.getPlanComparison(PROJECT_A, plan, MEMBER);

        assertThat(view.comparisonStatus()).isEqualTo("PLAN_NOT_APPLIED");
        assertThat(view.baselineSource()).isEqualTo("NONE");
        assertThat(view.baselineVersionId()).isNull();
        assertThat(view.message()).contains("从未确认应用");
        assertThat(view.message()).contains("无法对比");
        assertThat(view.taskComparisons()).isEmpty();
        assertThat(view.summary().totalPlanned()).isZero();
        assertThat(view.summary().matchedTasks()).isZero();
        // 准备好的版本与确认记录都不存在 → 无可用基准
        assertThat(version).isNotNull();
    }

    @Test
    void planComparisonUsesAppliedVersionAsBaselineNotLatest() {
        UUID plan = insertPlan(PROJECT_A, "已应用规划", "CONFIRMED", null, 2, null, null);
        UUID v1 = insertPlanVersion(plan, 1, planTasksJson(
                planTaskJson("t1", "规划任务一", "HIGH", null, "2026-01-05", "2026-01-10"),
                planTaskJson("t2", "规划任务二", "MEDIUM", null, null, null)));
        UUID v2 = insertPlanVersion(plan, 2, planTasksJson(
                planTaskJson("t1-v2", "重规划的任务", "LOW", null, null, null)));
        confirmPlan(PROJECT_A, plan, v1);

        // 实际任务来自应用版本 v1：t1 与规划完全一致（无负责人差异）；t2 优先级被人工修改
        insertPlanTask(PROJECT_A, plan, v1, "t1", "规划任务一", "HIGH", "TODO", null,
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 10));
        insertPlanTask(PROJECT_A, plan, v1, "t2", "规划任务二", "HIGH", "IN_PROGRESS", null, null, null);

        var view = reports.getPlanComparison(PROJECT_A, plan, MEMBER);

        assertThat(view.comparisonStatus()).isEqualTo("COMPARED");
        assertThat(view.baselineSource()).isEqualTo("APPLIED_VERSION");
        assertThat(view.baselineVersionId()).isEqualTo(v1);
        assertThat(view.baselineVersionNo()).isEqualTo(1);
        assertThat(view.appliedAt()).isNotNull();
        // 基准是实际应用版本 v1，而不是可能被继续编辑的 latest v2
        assertThat(view.summary().totalPlanned()).isEqualTo(2);
        assertThat(view.summary().matchedTasks()).isEqualTo(1);
        assertThat(view.summary().modifiedTasks()).isEqualTo(1);
        assertThat(view.summary().missingTasks()).isZero();
        assertThat(view.summary().extraTasks()).isZero();
        var modified = view.taskComparisons().stream()
                .filter(c -> "MODIFIED".equals(c.status())).findFirst().orElseThrow();
        assertThat(modified.diffs()).hasSize(1);
        assertThat(modified.diffs().getFirst().fieldName()).isEqualTo("priority");
        assertThat(modified.diffs().getFirst().plannedValue()).isEqualTo("MEDIUM");
        assertThat(modified.diffs().getFirst().actualValue()).isEqualTo("HIGH");
        assertThat(modified.actualStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void planComparisonClassifiesOtherVersionTasksInsteadOfMisreporting() {
        UUID plan = insertPlan(PROJECT_A, "版本变更规划", "CONFIRMED", null, 2, null, null);
        UUID v1 = insertPlanVersion(plan, 1, planTasksJson(
                planTaskJson("t1", "规划任务一", "MEDIUM", null, null, null)));
        UUID v2 = insertPlanVersion(plan, 2, planTasksJson(
                planTaskJson("t9", "新版任务", "LOW", null, null, null)));
        confirmPlan(PROJECT_A, plan, v1);

        insertPlanTask(PROJECT_A, plan, v1, "t1", "规划任务一", "MEDIUM", "TODO", null, null, null);
        // 版本变更后新版本来源的任务：不属于应用版本基准，不得误报为 EXTRA
        insertPlanTask(PROJECT_A, plan, v2, "t9", "新版任务", "LOW", "TODO", null, null, null);

        var view = reports.getPlanComparison(PROJECT_A, plan, MEMBER);

        assertThat(view.comparisonStatus()).isEqualTo("COMPARED");
        assertThat(view.baselineVersionId()).isEqualTo(v1);
        assertThat(view.summary().matchedTasks()).isEqualTo(1);
        assertThat(view.summary().extraTasks()).isZero();
        assertThat(view.otherVersionTaskCount()).isEqualTo(1);
        assertThat(view.taskComparisons())
                .filteredOn(c -> "OTHER_VERSION".equals(c.status()))
                .hasSize(1)
                .first()
                .satisfies(c -> assertThat(c.note()).contains("其他规划版本"));
    }

    @Test
    void planComparisonHandlesMissingUnlinkedAndAmbiguousSourceKeys() {
        UUID plan = insertPlan(PROJECT_A, "来源键异常规划", "CONFIRMED", null, 1, null, null);
        UUID v1 = insertPlanVersion(plan, 1, planTasksJson(
                planTaskJson("t1", "已落库任务", "MEDIUM", null, null, null),
                planTaskJson("t2", "重复键任务", "MEDIUM", null, null, null),
                planTaskJson("t3", "未落库任务", "MEDIUM", null, null, null)));
        confirmPlan(PROJECT_A, plan, v1);

        insertPlanTask(PROJECT_A, plan, v1, "t1", "已落库任务", "MEDIUM", "TODO", null, null, null);
        // 无来源键（手动落库到该规划）：无法可靠关联
        insertPlanTask(PROJECT_A, plan, null, null, "无来源键任务", "LOW", "TODO", null, null, null);
        // 重复来源键（防御场景，version 为空的两条同键任务）：无法唯一关联
        insertPlanTask(PROJECT_A, plan, null, "t2", "重复键任务甲", "MEDIUM", "TODO", null, null, null);
        insertPlanTask(PROJECT_A, plan, null, "t2", "重复键任务乙", "MEDIUM", "TODO", null, null, null);
        // t3 未落库（或已被删除）

        var view = reports.getPlanComparison(PROJECT_A, plan, MEMBER);

        assertThat(view.comparisonStatus()).isEqualTo("COMPARED");
        assertThat(view.summary().matchedTasks()).isEqualTo(1);
        // t2 重复键、t3 未落库：均不强行判定匹配
        assertThat(view.summary().missingTasks()).isEqualTo(1);
        assertThat(view.unlinkedTaskCount()).isEqualTo(1);
        assertThat(view.ambiguousTaskCount()).isEqualTo(2);
        assertThat(view.taskComparisons())
                .filteredOn(c -> "UNLINKED".equals(c.status())).hasSize(1)
                .first().satisfies(c -> assertThat(c.note()).contains("source_plan_task_key"));
        assertThat(view.taskComparisons())
                .filteredOn(c -> "AMBIGUOUS".equals(c.status())).hasSize(3);
        assertThat(view.taskComparisons())
                .filteredOn(c -> "MISSING".equals(c.status())).hasSize(1)
                .first().satisfies(c -> {
                    assertThat(c.plannedTaskId()).isEqualTo("t3");
                    assertThat(c.note()).contains("未落库");
                });
    }

    @Test
    void planComparisonReportsExtraBaselineKeyAsDataAnomaly() {
        UUID plan = insertPlan(PROJECT_A, "多余来源键规划", "CONFIRMED", null, 1, null, null);
        UUID v1 = insertPlanVersion(plan, 1, planTasksJson(
                planTaskJson("t1", "规划任务一", "MEDIUM", null, null, null)));
        confirmPlan(PROJECT_A, plan, v1);

        insertPlanTask(PROJECT_A, plan, v1, "t1", "规划任务一", "MEDIUM", "TODO", null, null, null);
        insertPlanTask(PROJECT_A, plan, v1, "t5", "幽灵任务", "LOW", "TODO", null, null, null);

        var view = reports.getPlanComparison(PROJECT_A, plan, MEMBER);

        assertThat(view.summary().matchedTasks()).isEqualTo(1);
        assertThat(view.summary().extraTasks()).isEqualTo(1);
        assertThat(view.taskComparisons())
                .filteredOn(c -> "EXTRA".equals(c.status())).hasSize(1)
                .first().satisfies(c -> assertThat(c.note()).contains("数据异常"));
    }

    @Test
    void planComparisonIsolatedByProject() {
        UUID planB = insertPlan(PROJECT_B, "乙项目规划", "CONFIRMED", null, 1, null, null);
        UUID v1 = insertPlanVersion(planB, 1, planTasksJson(
                planTaskJson("t1", "乙项目规划任务", "MEDIUM", null, null, null)));
        confirmPlan(PROJECT_B, planB, v1);
        insertPlanTask(PROJECT_B, planB, v1, "t1", "乙项目规划任务", "MEDIUM", "TODO", null, null, null);

        // 项目甲成员访问乙项目规划：拒绝
        assertThatThrownBy(() -> reports.getPlanComparison(PROJECT_A, planB, MEMBER))
                .isInstanceOf(BusinessException.class);

        // 项目甲周报不含乙项目任务
        insertTask(PROJECT_B, "乙项目任务", "DONE", null, OUTSIDER,
                OffsetDateTime.now(ZONE), OffsetDateTime.now(ZONE), "MEDIUM");
        var report = reports.getWeeklyReport(PROJECT_A, MEMBER);
        assertThat(report.taskStats().totalTasks()).isZero();
    }

    // ==================== 测试夹具 ====================

    private void insertUser(UUID id, String username, String displayName) {
        jdbc.update("""
                insert into app_user(id, username, password_hash, display_name)
                values (?, ?, 'insight-test-hash', ?)
                """, id, username, displayName);
    }

    private void insertProject(UUID id, String name, UUID ownerId) {
        jdbc.update("""
                insert into project(id, name, owner_id, created_by)
                values (?, ?, ?, ?)
                """, id, name, ownerId, ownerId);
    }

    private void insertMember(UUID projectId, UUID userId, String role) {
        jdbc.update("insert into project_member(project_id, user_id, role) values (?, ?, ?)",
                projectId, userId, role);
    }

    /** 直接插入任务，可显式指定 created_at / completed_at，用于周期边界与历史数据场景。 */
    private UUID insertTask(UUID projectId, String title, String status, LocalDate dueDate,
                            UUID assigneeId, OffsetDateTime createdAt, OffsetDateTime completedAt,
                            String priority) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_task(id, project_id, title, status, priority, assignee_id,
                  due_date, created_by, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, projectId, title, status, priority, assigneeId, dueDate, OWNER,
                createdAt, completedAt);
        return id;
    }

    /** 便捷重载：created_at 默认当前时刻。 */
    private UUID insertTask(UUID projectId, String title, String status, LocalDate dueDate,
                            UUID assigneeId, OffsetDateTime completedAt, String priority) {
        return insertTask(projectId, title, status, dueDate, assigneeId,
                OffsetDateTime.now(ZONE), completedAt, priority);
    }

    /**
     * 复刻 {@code TaskMapper.updateScoped} 的状态更新语义（completed_at 维护逻辑），
     * 保证测试写入与真实应用写路径一致。
     */
    private void updateTaskStatus(UUID taskId, String status) {
        jdbc.update("""
                UPDATE project_task SET status = ?,
                  completed_at = CASE WHEN ? = 'DONE' THEN COALESCE(completed_at, now()) ELSE NULL END,
                  version = version + 1, updated_at = now()
                WHERE id = ?
                """, status, status, taskId);
    }

    private UUID insertMilestone(UUID projectId, String name, LocalDate targetDate, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO milestone(id, project_id, name, target_date, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, projectId, name, targetDate, status, OWNER);
        return id;
    }

    private void insertAudit(UUID projectId, UUID entityId, String action,
                             String detailJson, OffsetDateTime createdAt) {
        jdbc.update("""
                INSERT INTO audit_log(project_id, user_id, action, entity_type, entity_id,
                  detail, request_id, created_at)
                VALUES (?, NULL, ?, 'MILESTONE', ?, ?::jsonb, ?, ?)
                """, projectId, action, entityId, detailJson, UUID.randomUUID(), createdAt);
    }

    private UUID insertPlan(UUID projectId, String title, String status, UUID latestVersionId,
                            int latestVersionNo, UUID confirmedVersionId, OffsetDateTime confirmedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_task_plan(id, project_id, title, goal, plan_start_date, plan_due_date,
                  max_task_count, status, latest_version_no, latest_version_id,
                  confirmed_by, confirmed_version_id, confirmed_at, created_by)
                VALUES (?, ?, ?, '洞察测试目标', '2026-01-01', '2026-02-01', 10, ?, ?, ?, ?, ?, ?, ?)
                """, id, projectId, title, status, latestVersionNo, latestVersionId,
                confirmedVersionId != null ? OWNER : null, confirmedVersionId, confirmedAt, OWNER);
        return id;
    }

    private UUID insertPlanVersion(UUID planId, int versionNo, String tasksJson) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_task_plan_version(id, plan_id, version_no, source_type,
                  generation_seq, tasks_json, created_by)
                VALUES (?, ?, ?, 'AI_COMPLETE', 1, ?::jsonb, ?)
                """, id, planId, versionNo, tasksJson, OWNER);
        return id;
    }

    private void confirmPlan(UUID projectId, UUID planId, UUID versionId) {
        jdbc.update("""
                UPDATE ai_task_plan SET confirmed_version_id = ?, confirmed_at = now(),
                  confirmed_by = ?, updated_at = now() WHERE id = ?
                """, versionId, OWNER, planId);
        jdbc.update("""
                INSERT INTO ai_task_plan_confirmation(id, project_id, plan_id, version_id,
                  idempotency_key, request_hash, status, created_by)
                VALUES (?, ?, ?, ?, ?, 'insight-test', 'SUCCESS', ?)
                """, UUID.randomUUID(), projectId, planId, versionId, UUID.randomUUID(), OWNER);
        jdbc.update("UPDATE ai_task_plan SET latest_version_id = ? WHERE id = ?", versionId, planId);
    }

    private UUID insertPlanTask(UUID projectId, UUID planId, UUID versionId, String key,
                                String title, String priority, String status, UUID assigneeId,
                                LocalDate startDate, LocalDate dueDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_task(id, project_id, title, status, priority, assignee_id,
                  start_date, due_date, created_by, source_plan_id, source_plan_version_id,
                  source_plan_task_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, projectId, title, status, priority, assigneeId, startDate, dueDate,
                OWNER, planId, versionId, key);
        return id;
    }

    private static String planTaskJson(String key, String title, String priority,
                                       UUID assigneeId, String startDate, String dueDate) {
        return "{\"tempKey\":\"" + key + "\",\"title\":\"" + title + "\",\"priority\":\"" + priority
                + "\",\"assigneeId\":" + (assigneeId == null ? "null" : "\"" + assigneeId + "\"")
                + ",\"startDate\":" + (startDate == null ? "null" : "\"" + startDate + "\"")
                + ",\"dueDate\":" + (dueDate == null ? "null" : "\"" + dueDate + "\"")
                + ",\"sortOrder\":0}";
    }

    private static String planTasksJson(String... items) {
        return "[" + String.join(",", items) + "]";
    }
}
