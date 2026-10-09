package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.tool.MilestoneListAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.TaskListAgentTool;
import com.shitulelv.aicollab.work.application.service.MilestoneApplicationService;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import com.shitulelv.aicollab.work.application.view.MilestoneView;
import com.shitulelv.aicollab.work.application.view.TaskView;
import com.shitulelv.aicollab.work.domain.model.MilestoneStatus;
import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 三类问题的资料覆盖与据实回答：项目概览、按范围列全部、检查本周任务是否影响交付。
 *
 * <p>这里不使用"固定答案"的假模型作为质量证据：测试把工具分页 → 清洗器 → 模型可见视图
 * 串成一条链，把模型实际能读到的记录累积成事实集，再断言按该事实集作答时，
 * 结论/事实/原因/建议/未核查范围五类内容都有依据，且"延期"只由日期证据支持。</p>
 */
class ListFactsQuestionCoverageTest {
    /** 与组装器最新工具结果阈值一致的模型可见上限。 */
    private static final int MODEL_VIEW_CAP = 6000;
    private static final int TOOL_PAGE_LIMIT = 50;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AgentToolResultSanitizer sanitizer = new AgentToolResultSanitizer(json);
    private final AgentToolOutputProjector projector = new AgentToolOutputProjector(json);
    private final com.shitulelv.aicollab.agent.domain.tool.AgentToolContext ctx =
            new com.shitulelv.aicollab.agent.domain.tool.AgentToolContext(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OWNER", false, 0);

    // ------------------------------------------------------------ 问题一：项目概览

    @Test
    void overviewQuestionCanReportCountsAndScopesFromToolFacts() {
        var tasks = taskFixture();
        var tool = taskTool(tasks);
        var page = readAllTasks(tool);

        // 概览只回答能核实的事实：总数、状态分布、逾期数；未读范围显式可写
        assertThat(page.total()).isEqualTo(tasks.size());
        assertThat(page.records()).hasSize(tasks.size());
        assertThat(page.hasUnreadRange()).isFalse();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (var record : page.records()) {
            byStatus.merge(record.path("status").asText(), 1, Integer::sum);
        }
        int expectedBlocked = (int) tasks.stream().filter(t -> t.status() == TaskStatus.BLOCKED).count();
        assertThat(byStatus.get("BLOCKED")).isEqualTo(expectedBlocked);
        assertThat(page.records()).allSatisfy(record ->
                assertThat(record.hasNonNull("assigneeName")).isTrue());
    }

    // ------------------------------------------------------------ 问题二：列全部（含超过 50 条）

    @Test
    void listAllQuestionCoversEveryRecordAcrossPages() {
        var tasks = new ArrayList<TaskView>();
        for (int i = 1; i <= 130; i++) tasks.add(task(i, i % 7 == 0 ? TaskStatus.BLOCKED : TaskStatus.TODO, 20, null));
        var tool = taskTool(tasks);

        var page = readAllTasks(tool);
        assertThat(page.records()).hasSize(130);
        assertThat(page.ids()).doesNotHaveDuplicates().hasSize(130);
        assertThat(page.records().stream().map(r -> r.path("id").asText()).toList())
                .containsExactlyElementsOf(tasks.stream().map(t -> t.id().toString()).sorted().toList());
        assertThat(page.pages()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void listAllMilestonesCoversMoreThanFiftyRecords() {
        var milestones = new ArrayList<MilestoneView>();
        for (int i = 1; i <= 63; i++) milestones.add(milestone(i, i % 9 == 0 ? MilestoneStatus.COMPLETED : MilestoneStatus.ACTIVE));
        MilestoneApplicationService service = mock(MilestoneApplicationService.class);
        when(service.list(eq(ctx.projectId()), eq(ctx.userId()))).thenReturn(milestones);
        var tool = new MilestoneListAgentTool(service, json);

        var records = new ArrayList<JsonNode>();
        var ids = new ArrayList<String>();
        boolean hasMore = true;
        String cursor = null;
        int pages = 0;
        while (hasMore && pages < 30) {
            var args = json.createObjectNode().put("limit", TOOL_PAGE_LIMIT);
            if (cursor != null) args.put("cursor", cursor);
            var view = projector.projectToolOutput(wrap(sanitizer.sanitize(tool.execute(ctx, args).data())), MODEL_VIEW_CAP);
            for (JsonNode item : view.path("data").path("items")) {
                if (!item.hasNonNull("id")) continue;
                records.add(item);
                ids.add(item.path("id").asText());
            }
            hasMore = view.path("data").path("hasMore").asBoolean();
            cursor = view.path("data").path("nextCursor").isNull() ? null : view.path("data").path("nextCursor").asText();
            pages++;
        }
        assertThat(ids).doesNotHaveDuplicates().hasSize(63);
        assertThat(records.stream().filter(r -> r.path("status").asText().equals("COMPLETED")).count())
                .isEqualTo(milestones.stream().filter(m -> m.status() == MilestoneStatus.COMPLETED).count());
    }

    // ------------------------------------------------------------ 问题三：本周任务是否影响交付

    @Test
    void deliveryImpactQuestionDistinguishesOverdueFromUnknown() {
        var tasks = new ArrayList<TaskView>();
        // 已逾期：截止日期已过且未完成
        tasks.add(new TaskView(new UUID(0, 1), ctx.projectId(), "逾期任务：联调环境不可用",
                "阻塞说明", null, null, UUID.randomUUID(), "张三", TaskStatus.IN_PROGRESS, TaskPriority.HIGH,
                null, LocalDate.now().minusDays(20), LocalDate.now().minusDays(3), 1, 2, List.of(), null));
        // 正常在途
        tasks.add(new TaskView(new UUID(0, 2), ctx.projectId(), "在途任务：接口开发",
                "", null, null, UUID.randomUUID(), "李四", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM,
                null, LocalDate.now().minusDays(2), LocalDate.now().plusDays(5), 1, 0, List.of(), null));
        // 缺少截止日期：不能据此判断是否延期
        tasks.add(new TaskView(new UUID(0, 3), ctx.projectId(), "TODO 任务：待排期",
                "", null, null, null, null, TaskStatus.TODO, TaskPriority.LOW,
                null, null, null, 0, 0, List.of(), null));
        var tool = taskTool(tasks);

        var page = readAllTasks(tool);
        var overdue = page.records().stream()
                .filter(r -> r.hasNonNull("dueDate") && !r.path("dueDate").asText().isBlank())
                .filter(r -> r.path("dueDate").asText().compareTo(LocalDate.now().toString()) < 0)
                .filter(r -> !r.path("status").asText().equals("DONE"))
                .toList();
        var unknownDue = page.records().stream().filter(r -> !r.hasNonNull("dueDate")).toList();

        assertThat(overdue).hasSize(1);
        assertThat(overdue.get(0).path("title").asText()).contains("逾期任务");
        // 缺日期的一条不得被算成延期，只能计入"无法判定"
        assertThat(unknownDue).hasSize(1);
        assertThat(unknownDue.get(0).path("title").asText()).contains("待排期");
        // 每条逾期结论都能给出可执行建议所需的负责人与依赖信息
        assertThat(overdue.get(0).path("assigneeName").asText()).isEqualTo("张三");
        assertThat(overdue.get(0).path("unfinishedDependencyCount").asInt()).isEqualTo(2);
    }

    @Test
    void unreadRangeIsExplicitWhenModelViewIsTruncated() {
        var tasks = new ArrayList<TaskView>();
        for (int i = 1; i <= 60; i++) tasks.add(task(i, TaskStatus.TODO, 900, null));
        var tool = taskTool(tasks);

        // 人为把模型可见阈值压到很低：必须给出未展示条数，模型才能据实声明部分结论
        var view = projector.projectToolOutput(wrap(sanitizer.sanitize(
                tool.execute(ctx, json.createObjectNode().put("limit", TOOL_PAGE_LIMIT)).data())), 600);
        var data = view.path("data");
        assertThat(view.path("projection").asText()).isEqualTo("DETERMINISTIC");
        assertThat(data.path("returned").asInt()).isLessThan(data.path("total").asInt());
        assertThat(data.path("total").asInt()).isGreaterThan(data.path("returned").asInt());
        assertThat(data.path("hasMore").asBoolean()).isTrue();
        assertThat(data.path("nextCursor").isNull()).isFalse();
    }

    // ------------------------------------------------------------ 辅助

    /** 一页一页读到 hasMore=false，累积模型实际可见的记录。 */
    private ReadThrough readAllTasks(TaskListAgentTool tool) {
        var records = new ArrayList<JsonNode>();
        var ids = new ArrayList<String>();
        boolean hasMore = true;
        String cursor = null;
        int pages = 0;
        int total = -1;
        boolean unread = false;
        while (hasMore && pages < 80) {
            var args = json.createObjectNode().put("limit", TOOL_PAGE_LIMIT);
            if (cursor != null) args.put("cursor", cursor);
            var view = projector.projectToolOutput(wrap(sanitizer.sanitize(tool.execute(ctx, args).data())), MODEL_VIEW_CAP);
            var data = view.path("data");
            total = Math.max(total, data.path("total").asInt() + records.size());
            if (data.path("unshownInPage").asInt() > 0) unread = true;
            for (JsonNode item : data.path("items")) {
                if (!item.hasNonNull("id")) continue;
                records.add(item);
                ids.add(item.path("id").asText());
            }
            hasMore = data.path("hasMore").asBoolean();
            cursor = data.path("nextCursor").isNull() ? null : data.path("nextCursor").asText();
            pages++;
        }
        return new ReadThrough(records, ids, pages, total, unread);
    }

    private record ReadThrough(List<JsonNode> records, List<String> ids, int pages, int total,
                               boolean hasUnreadRange) {
    }

    private TaskListAgentTool taskTool(List<TaskView> tasks) {
        TaskApplicationService service = mock(TaskApplicationService.class);
        when(service.list(eq(ctx.projectId()), isNull(), isNull(), isNull(), eq(ctx.userId()))).thenReturn(tasks);
        return new TaskListAgentTool(service, json);
    }

    private List<TaskView> taskFixture() {
        var tasks = new ArrayList<TaskView>();
        tasks.add(task(1, TaskStatus.IN_PROGRESS, 120, LocalDate.now().minusDays(20)));
        tasks.add(task(2, TaskStatus.BLOCKED, 400, LocalDate.now().plusDays(3)));
        tasks.add(task(3, TaskStatus.DONE, 60, LocalDate.now().minusDays(1)));
        tasks.add(task(4, TaskStatus.TODO, 0, null));
        return tasks;
    }

    private JsonNode wrap(JsonNode data) {
        var output = json.createObjectNode();
        output.put("status", "SUCCEEDED");
        output.set("data", data);
        return output;
    }

    private TaskView task(int index, TaskStatus status, int descriptionChars, LocalDate dueDate) {
        return new TaskView(new UUID(0, index), ctx.projectId(), "任务 " + index,
                "描述".repeat(Math.max(0, descriptionChars / 2)), null, null,
                UUID.randomUUID(), "成员" + (index % 4), status, TaskPriority.MEDIUM,
                null, LocalDate.now().minusDays(30), dueDate, 2, index % 3, List.of(), null);
    }

    private MilestoneView milestone(int index, MilestoneStatus status) {
        return new MilestoneView(new UUID(0, index), ctx.projectId(), "里程碑 " + index,
                "", LocalDate.now().minusDays(10), LocalDate.now().plusDays(20), LocalDate.now().plusDays(20),
                status, index, 1);
    }
}
