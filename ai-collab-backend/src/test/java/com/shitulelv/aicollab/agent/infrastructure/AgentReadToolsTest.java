package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.infrastructure.tool.KnowledgeSearchAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.TaskListAgentTool;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import com.shitulelv.aicollab.work.application.view.TaskView;
import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentReadToolsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentToolContext context = new AgentToolContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "SUPERVISOR", false, 0);

    @Test
    void taskListAppliesHardResultLimit() {
        TaskApplicationService tasks = mock(TaskApplicationService.class);
        when(tasks.list(eq(context.projectId()), isNull(), isNull(), isNull(), eq(context.userId())))
                .thenReturn(List.of(task("一"), task("二")));
        TaskListAgentTool tool = new TaskListAgentTool(tasks, json);

        var result = tool.execute(context, json.createObjectNode().put("limit", 1));

        assertThat(result.data().path("items")).hasSize(1);
        assertThat(result.data().path("items").get(0).path("title").asText()).isEqualTo("一");
        assertThat(result.data().path("items").get(0).path("status").asText()).isEqualTo("TODO");
        assertThat(result.data().path("taskFacts").get(0).path("status").asText()).isEqualTo("TODO");
        assertThat(result.data().path("taskFacts").get(0).path("assigneeId").isNull()).isTrue();
        assertThat(result.data().path("fieldGuide").asText()).contains("不是任务状态");
    }

    @Test
    void knowledgeSearchReturnsBoundedLocationAwareCitations() {
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        when(access.requireMember(context.projectId(), context.userId())).thenReturn(ProjectRole.MEMBER);
        DocumentSearchService search = mock(DocumentSearchService.class);
        UUID chunk = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        when(search.search(eq(context.projectId()), eq("验收标准"), eq(List.of()), eq(8)))
                .thenReturn(List.of(new DocumentSearchHit(
                        chunk, document, "需求.pdf", "验收",
                        "必须通过全部自动化测试", "hash", 0.91,
                        Map.of("pageNumber", 7))));
        KnowledgeSearchAgentTool tool = new KnowledgeSearchAgentTool(
                access, search, new KnowledgeContextBuilder(), json);

        var result = tool.execute(
                context, json.createObjectNode().put("query", "验收标准"));

        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().getFirst().documentId()).isEqualTo(document);
        assertThat(result.citations().getFirst().chunkId()).isEqualTo(chunk);
        assertThat(result.citations().getFirst().pageNumber()).isEqualTo(7);
        assertThat(result.citations().getFirst().quote()).isEqualTo("必须通过全部自动化测试");
    }

    @Test
    void taskFactsPreserveTitleStatusAndFormalAssignee() {
        var tasks = mock(TaskApplicationService.class);
        var member = UUID.randomUUID();
        var task = new TaskView(UUID.randomUUID(), context.projectId(), "验收", "", null, null,
                member, "Local Owner", TaskStatus.IN_PROGRESS, TaskPriority.HIGH,
                null, null, null, 3, 0, List.of(), null);
        when(tasks.list(eq(context.projectId()), isNull(), isNull(), isNull(), eq(context.userId()))).thenReturn(List.of(task));
        var data = new TaskListAgentTool(tasks, json).execute(context, json.createObjectNode()).data();
        var fact = data.path("taskFacts").get(0);
        assertThat(fact.path("title").asText()).isEqualTo("验收");
        assertThat(fact.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(fact.path("assigneeId").asText()).isEqualTo(member.toString());
        assertThat(fact.path("assigneeName").asText()).isEqualTo("Local Owner");
    }

    private TaskView task(String title) {
        return new TaskView(
                UUID.randomUUID(), context.projectId(), title, "描述",
                null, null, null, null, TaskStatus.TODO, TaskPriority.MEDIUM,
                null, null, null, 0, 0, List.of(), null);
    }
}
