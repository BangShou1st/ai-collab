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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 列表资料可读完整性：真实串联"工具分页 → 清洗器 → 模型可见视图 → 续页"，
 * 验证多页读取后 ID 集合无遗漏、无重复，并覆盖超过 50 条里程碑、长描述、
 * 最新/历史投影阈值与 sanitizer 缩减边界。
 */
class AgentListPaginationChainTest {
    /** 组装器最新工具结果阈值（与 AgentModelMessageComposer.NEWEST_TOOL_OUTPUT_CAP 一致）。 */
    private static final int NEWEST_CAP = 6000;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AgentToolResultSanitizer sanitizer = new AgentToolResultSanitizer(json);
    private final AgentToolOutputProjector projector = new AgentToolOutputProjector(json);
    private final AgentToolContextFixture context = new AgentToolContextFixture();

    private record AgentToolContextFixture() {
        com.shitulelv.aicollab.agent.domain.tool.AgentToolContext context() {
            return new com.shitulelv.aicollab.agent.domain.tool.AgentToolContext(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SUPERVISOR", false, 0);
        }
    }

    // ------------------------------------------------------------ 里程碑：超过 50 条

    @Test
    void milestonePagingCoversAllRecordsAcrossPagesWithoutGapOrDuplicate() {
        var ctx = context.context();
        var milestones = new ArrayList<MilestoneView>();
        for (int i = 1; i <= 63; i++) milestones.add(milestone(i, 20));
        MilestoneApplicationService service = mock(MilestoneApplicationService.class);
        when(service.list(eq(ctx.projectId()), eq(ctx.userId()))).thenReturn(milestones);
        var tool = new MilestoneListAgentTool(service, json);

        var walk = walkMilestones(tool, ctx, NEWEST_CAP, 50);
        assertThat(walk.ids()).containsExactlyElementsOf(allIds(milestones));
        assertThat(walk.duplicates()).isZero();
        assertThat(walk.pages()).isGreaterThanOrEqualTo(2);
        assertThat(walk.lastHasMore()).isFalse();
        assertThat(walk.lastNextCursor()).isNull();
    }

    @Test
    void toolPageFitsModelViewSoNoRecordIsDroppedByProjection() {
        var ctx = context.context();
        var milestones = new ArrayList<MilestoneView>();
        for (int i = 1; i <= 30; i++) milestones.add(milestone(i, 400));
        MilestoneApplicationService service = mock(MilestoneApplicationService.class);
        when(service.list(eq(ctx.projectId()), eq(ctx.userId()))).thenReturn(milestones);
        var tool = new MilestoneListAgentTool(service, json);

        // 服务端页预算低于组装器阈值：本页记录全部进入模型视图，投影不再裁掉记录
        var walk = walkMilestones(tool, ctx, NEWEST_CAP, 50);
        assertThat(walk.ids()).containsExactlyElementsOf(allIds(milestones));
        assertThat(walk.duplicates()).isZero();
        for (var view : walk.views()) {
            var data = view.path("data");
            assertThat(data.path("returned").asInt())
                    .as("可见条数必须等于模型视图记录数")
                    .isEqualTo(visibleRecordCount(view));
            if (view.path("projection").asText().equals("DETERMINISTIC")) {
                assertThat(data.path("pageReturned").asInt()).isEqualTo(data.path("returned").asInt());
                assertThat(data.path("unshownInPage").asInt()).isZero();
            }
            assertThat(data.path("nextCursor").isNull()
                    || data.path("nextCursor").asText().compareTo(lastVisibleId(view)) > 0)
                    .as("续读位置必须严格位于可见记录之后")
                    .isTrue();
        }
    }

    @Test
    void projectionUnderExtremeCapStaysSelfConsistentAndBounded() {
        var ctx = context.context();
        var milestones = new ArrayList<MilestoneView>();
        for (int i = 1; i <= 30; i++) milestones.add(milestone(i, 400));
        MilestoneApplicationService service = mock(MilestoneApplicationService.class);
        when(service.list(eq(ctx.projectId()), eq(ctx.userId()))).thenReturn(milestones);
        var tool = new MilestoneListAgentTool(service, json);

        // 人为把可见阈值压到 1200 字符（远低于真实 6000）：投影必须缩小可见范围，
        // 并且如实给出"本页还有多少条没看到"，让模型可以据此重查，而不是静默丢资料
        var sanitized = sanitizer.sanitize(tool.execute(ctx, json.createObjectNode().put("limit", 50)).data());
        var view = projector.projectToolOutput(wrap(sanitized), 1200);
        var data = view.path("data");
        assertThat(view.toString().length()).isLessThanOrEqualTo(1200);
        assertThat(view.path("projection").asText()).isEqualTo("DETERMINISTIC");
        assertThat(view.path("projectionScope").asText()).isEqualTo("LIST_PAGE_WITH_ALIGNED_CURSOR");
        assertThat(data.path("resumeScope").asText()).isEqualTo("REMAINDER_OF_THIS_PAGE");
        assertThat(view.path("modelVisibleChars").asInt()).isEqualTo(view.toString().length());
        assertThat(data.path("returned").asInt()).isEqualTo(visibleRecordCount(view));
        assertThat(data.path("pageReturned").asInt()).isGreaterThan(data.path("returned").asInt());
        assertThat(data.path("unshownInPage").asInt())
                .isEqualTo(data.path("pageReturned").asInt() - data.path("returned").asInt());
        // 续读位置就是本页第一条未展示记录：续读从它开始，不跨过未见记录
        assertThat(data.path("nextCursor").asText())
                .isEqualTo(sanitized.path("items").get(data.path("returned").asInt()).path("id").asText());
        assertThat(data.path("total").asInt()).isGreaterThan(data.path("returned").asInt());
    }

    @Test
    void milestoneToolPagingIsCompleteAndCursorPointsAtFirstUnreturned() {
        var ctx = context.context();
        var milestones = new ArrayList<MilestoneView>();
        for (int i = 1; i <= 120; i++) milestones.add(milestone(i, 10));
        MilestoneApplicationService service = mock(MilestoneApplicationService.class);
        when(service.list(eq(ctx.projectId()), eq(ctx.userId()))).thenReturn(milestones);
        var tool = new MilestoneListAgentTool(service, json);

        // limit 超过上限时必须明确失败，不能静默按 50 处理
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> tool.execute(ctx, json.createObjectNode().put("limit", 200)))
                .isInstanceOf(IllegalArgumentException.class);

        var page = tool.execute(ctx, json.createObjectNode().put("limit", 50)).data();
        int returned = page.path("returned").asInt();
        assertThat(page.path("items")).hasSize(returned);
        assertThat(returned).isBetween(1, 50);
        assertThat(page.path("total").asInt()).isEqualTo(120);
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        // nextCursor 是第一条未返回记录：续读从它本身开始，不跳过、不重复
        assertThat(page.path("nextCursor").asText()).isEqualTo(idAt(milestones, returned));
    }

    // ------------------------------------------------------------ 任务列表

    @Test
    void taskPagingCoversAllRecordsWithLongDescriptions() {
        var ctx = context.context();
        var tasks = new ArrayList<TaskView>();
        for (int i = 1; i <= 47; i++) tasks.add(task(i, 800));
        TaskApplicationService service = mock(TaskApplicationService.class);
        when(service.list(eq(ctx.projectId()), isNull(), isNull(), isNull(), eq(ctx.userId()))).thenReturn(tasks);
        var tool = new TaskListAgentTool(service, json);

        var walk = walkTasks(tool, ctx, NEWEST_CAP, 20);
        assertThat(walk.ids()).containsExactlyElementsOf(allTaskIds(tasks));
        assertThat(walk.duplicates()).isZero();
    }

    @Test
    void taskPagingStaysLosslessUnderAggressiveProjection() {
        var ctx = context.context();
        var tasks = new ArrayList<TaskView>();
        for (int i = 1; i <= 20; i++) tasks.add(task(i, 800));
        TaskApplicationService service = mock(TaskApplicationService.class);
        when(service.list(eq(ctx.projectId()), isNull(), isNull(), isNull(), eq(ctx.userId()))).thenReturn(tasks);
        var tool = new TaskListAgentTool(service, json);

        // 工具页预算低于组装器阈值：即使描述很长，续读链也完整覆盖且不重复
        var walk = walkTasks(tool, ctx, NEWEST_CAP, 20);
        assertThat(walk.ids()).containsExactlyElementsOf(allTaskIds(tasks));
        assertThat(walk.duplicates()).isZero();
        assertThat(walk.pages()).isGreaterThan(3);
    }

    @Test
    void taskFactsCoverageMatchesVisibleItems() {
        var ctx = context.context();
        var tasks = new ArrayList<TaskView>();
        for (int i = 1; i <= 8; i++) tasks.add(task(i, 800));
        TaskApplicationService service = mock(TaskApplicationService.class);
        when(service.list(eq(ctx.projectId()), isNull(), isNull(), isNull(), eq(ctx.userId()))).thenReturn(tasks);
        var tool = new TaskListAgentTool(service, json);

        var data = tool.execute(ctx, json.createObjectNode().put("limit", 8)).data();
        assertThat(data.path("taskFacts")).hasSameSizeAs(data.path("items"));
        assertThat(data.path("factsScope").asText()).isEqualTo("SAME_AS_ITEMS_PAGE");
        // 清洗后进入模型视图：紧凑 fact 视图与可见 items 仍同范围
        var view = sanitizer.sanitize(tool.execute(ctx, json.createObjectNode().put("limit", 8)).data());
        assertThat(view.path("taskFacts")).hasSameSizeAs(view.path("items"));
    }

    // ------------------------------------------------------------ 清洗 + 投影边界

    @Test
    void aggressiveProjectionCapStillKeepsCursorChainReadable() {
        var ctx = context.context();
        // 超长描述与超多记录：工具页预算、清洗器二次缩减与投影三层同时生效
        var tasks = new ArrayList<TaskView>();
        for (int i = 1; i <= 60; i++) tasks.add(task(i, 1200));
        TaskApplicationService service = mock(TaskApplicationService.class);
        when(service.list(eq(ctx.projectId()), isNull(), isNull(), isNull(), eq(ctx.userId()))).thenReturn(tasks);
        var tool = new TaskListAgentTool(service, json);

        // 真实模型可见阈值：工具页预算低于它，续读链完整且不重复
        var walk = walkTasks(tool, ctx, NEWEST_CAP, 50);
        assertThat(walk.ids()).containsExactlyElementsOf(allTaskIds(tasks));
        assertThat(walk.duplicates()).isZero();
        assertThat(walk.lastHasMore()).isFalse();
        assertThat(walk.lastNextCursor()).isNull();
    }

    @Test
    void taskDescriptionIsBoundedInModelView() {
        var ctx = context.context();
        var tasks = List.of(task(1, 2000));
        TaskApplicationService service = mock(TaskApplicationService.class);
        when(service.list(eq(ctx.projectId()), isNull(), isNull(), isNull(), eq(ctx.userId()))).thenReturn(tasks);
        var tool = new TaskListAgentTool(service, json);

        var item = tool.execute(ctx, json.createObjectNode()).data().path("items").get(0);
        assertThat(item.path("description").asText()).hasSize(300);
    }

    // ------------------------------------------------------------ 辅助

    private record Walk(List<String> ids, int duplicates, int pages, boolean lastHasMore, String lastNextCursor,
                        List<JsonNode> views) {
    }

    /** 按游标续读，每一步都经过清洗器与投影器投影，模拟模型真实读取路径。 */
    private Walk walkMilestones(MilestoneListAgentTool tool,
            com.shitulelv.aicollab.agent.domain.tool.AgentToolContext ctx, int cap, int limit) {
        var ids = new ArrayList<String>();
        var views = new ArrayList<JsonNode>();
        Set<String> seen = new LinkedHashSet<>();
        int duplicates = 0;
        boolean hasMore = true;
        String cursor = null;
        int pages = 0;
        while (hasMore && pages < 40) {
            var args = json.createObjectNode().put("limit", limit);
            if (cursor != null) args.put("cursor", cursor);
            var data = sanitizer.sanitize(tool.execute(ctx, args).data());
            var view = projector.projectToolOutput(wrap(data), cap);
            views.add(view);
            for (JsonNode item : view.path("data").path("items")) {
                if (!item.hasNonNull("id")) continue;
                String id = item.path("id").asText();
                if (!seen.add(id)) duplicates++;
                ids.add(id);
            }
            hasMore = view.path("data").path("hasMore").asBoolean();
            cursor = view.path("data").path("nextCursor").isNull() ? null : view.path("data").path("nextCursor").asText();
            pages++;
        }
        return new Walk(ids, duplicates, pages, hasMore, cursor, views);
    }

    private Walk walkTasks(TaskListAgentTool tool,
            com.shitulelv.aicollab.agent.domain.tool.AgentToolContext ctx, int cap, int limit) {
        var ids = new ArrayList<String>();
        var views = new ArrayList<JsonNode>();
        Set<String> seen = new LinkedHashSet<>();
        int duplicates = 0;
        boolean hasMore = true;
        String cursor = null;
        int pages = 0;
        while (hasMore && pages < 60) {
            var args = json.createObjectNode().put("limit", limit);
            if (cursor != null) args.put("cursor", cursor);
            var data = sanitizer.sanitize(tool.execute(ctx, args).data());
            var view = projector.projectToolOutput(wrap(data), cap);
            views.add(view);
            for (JsonNode item : view.path("data").path("items")) {
                if (!item.hasNonNull("id")) continue;
                String id = item.path("id").asText();
                if (!seen.add(id)) duplicates++;
                ids.add(id);
            }
            hasMore = view.path("data").path("hasMore").asBoolean();
            cursor = view.path("data").path("nextCursor").isNull() ? null : view.path("data").path("nextCursor").asText();
            pages++;
        }
        return new Walk(ids, duplicates, pages, hasMore, cursor, views);
    }

    /** 工具结果的外层信封，与真实 ToolResult 序列化一致。 */
    private JsonNode wrap(JsonNode data) {
        var output = json.createObjectNode();
        output.put("status", "SUCCEEDED");
        output.set("data", data);
        return output;
    }

    private int visibleRecordCount(JsonNode view) {
        int count = 0;
        for (JsonNode item : view.path("data").path("items")) if (item.hasNonNull("id")) count++;
        return count;
    }

    private String lastVisibleId(JsonNode view) {
        String last = null;
        for (JsonNode item : view.path("data").path("items")) if (item.hasNonNull("id")) last = item.path("id").asText();
        return last;
    }

    private List<String> allIds(List<MilestoneView> milestones) {
        return milestones.stream().map(m -> m.id().toString()).sorted().toList();
    }

    private List<String> allTaskIds(List<TaskView> tasks) {
        return tasks.stream().map(t -> t.id().toString()).sorted().toList();
    }

    private String idAt(List<MilestoneView> milestones, int index) {
        return milestones.stream().map(m -> m.id().toString()).sorted().skip(index).findFirst().orElseThrow();
    }

    private MilestoneView milestone(int index, int descriptionChars) {
        return new MilestoneView(new UUID(0, index), UUID.randomUUID(), "里程碑 " + index,
                "描述".repeat(Math.max(0, descriptionChars / 2)), LocalDate.now(), LocalDate.now().plusDays(30),
                LocalDate.now().plusDays(30), MilestoneStatus.PLANNED, index, 1);
    }

    private TaskView task(int index, int descriptionChars) {
        return new TaskView(new UUID(0, index), UUID.randomUUID(), "任务 " + index,
                "描述".repeat(Math.max(0, descriptionChars / 2)), null, null, UUID.randomUUID(), "张三", TaskStatus.IN_PROGRESS,
                TaskPriority.HIGH, null, LocalDate.now().minusDays(3), LocalDate.now().plusDays(2), 2, 1, List.of(), null);
    }
}
