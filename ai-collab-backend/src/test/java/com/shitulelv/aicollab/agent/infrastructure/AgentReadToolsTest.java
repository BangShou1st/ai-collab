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
import static org.mockito.Mockito.verify;
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
        when(search.search(eq(context.projectId()), eq("验收标准"), eq(List.of()), eq(20)))
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
        // 工具实际返回 limit、citation、sourceIdentity 与 coverage。
        var source = result.data().path("sources").get(0);
        assertThat(source.path("chunkId").asText()).isEqualTo(chunk.toString());
        assertThat(source.path("documentId").asText()).isEqualTo(document.toString());
        assertThat(source.path("sourceIdentity").path("pageNumber").asInt()).isEqualTo(7);
        assertThat(result.data().path("coverage").asText()).isEqualTo("RELEVANT_EXCERPTS_ONLY");
        assertThat(result.data().path("fullDocumentRead").asBoolean()).isFalse();
    }

    @Test
    void knowledgeSearchHonoursRequestedLimitUpToTwelve() {
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        when(access.requireMember(any(), any())).thenReturn(ProjectRole.MEMBER);
        DocumentSearchService search = mock(DocumentSearchService.class);
        List<DocumentSearchHit> twelve = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            twelve.add(new DocumentSearchHit(
                    UUID.randomUUID(), UUID.randomUUID(), "文档" + i + ".pdf", "标题" + i,
                    "第" + i + "条互不相同的短证据", "hash-" + i, 1.0 - i * 0.01));
        }
        // 候选池固定为有界 20：外部 limit 只决定最终返回来源数。
        when(search.search(eq(context.projectId()), eq("验收标准"), eq(List.of()), eq(20)))
                .thenReturn(twelve);
        KnowledgeSearchAgentTool tool = new KnowledgeSearchAgentTool(
                access, search, new KnowledgeContextBuilder(), json);

        var twelveResult = tool.execute(context,
                json.createObjectNode().put("query", "验收标准").put("limit", 12));
        var threeResult = tool.execute(context,
                json.createObjectNode().put("query", "验收标准").put("limit", 3));

        // limit=12 时 12 个短而不同的有效来源全部保留；limit=3 时最多 3 个。
        assertThat(twelveResult.data().path("sources")).hasSize(12);
        assertThat(twelveResult.citations()).hasSize(12);
        assertThat(threeResult.data().path("sources")).hasSize(3);
        assertThat(threeResult.citations()).hasSize(3);
    }

    @Test
    void knowledgeSearchUsesBoundedCandidatePoolAndKeepsDefaultSourceLimit() {
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        when(access.requireMember(any(), any())).thenReturn(ProjectRole.MEMBER);
        DocumentSearchService search = mock(DocumentSearchService.class);
        List<DocumentSearchHit> twelve = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            twelve.add(new DocumentSearchHit(
                    UUID.randomUUID(), UUID.randomUUID(), "文档" + i + ".pdf", "标题" + i,
                    "第" + i + "条互不相同的短证据", "hash-" + i, 1.0 - i * 0.01));
        }
        when(search.search(eq(context.projectId()), eq("验收标准"), eq(List.of()), eq(20)))
                .thenReturn(twelve);
        KnowledgeSearchAgentTool tool = new KnowledgeSearchAgentTool(
                access, search, new KnowledgeContextBuilder(), json);

        var result = tool.execute(context, json.createObjectNode().put("query", "验收标准"));

        // 候选窗口有界放大到 20，但缺省 limit 仍只返回 8 条来源。
        verify(search).search(eq(context.projectId()), eq("验收标准"), eq(List.of()), eq(20));
        assertThat(result.data().path("sources")).hasSize(8);
        assertThat(result.citations()).hasSize(8);
    }

    @Test
    void knowledgeSearchSelectsAcrossDocumentsAndKeepsCitationIdentity() {
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        when(access.requireMember(any(), any())).thenReturn(ProjectRole.MEMBER);
        DocumentSearchService search = mock(DocumentSearchService.class);
        UUID crowdedDocument = UUID.randomUUID();
        UUID otherDocument = UUID.randomUUID();
        UUID otherChunk = UUID.randomUUID();
        List<DocumentSearchHit> candidates = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            candidates.add(new DocumentSearchHit(
                    UUID.randomUUID(), crowdedDocument, "主体文档.pdf", "高频章节" + i,
                    "主体文档的高分证据 " + i, "crowd-" + i, 0.95 - i * 0.01));
        }
        candidates.add(new DocumentSearchHit(
                otherChunk, otherDocument, "附属文档.pdf", "关键结论",
                "附属文档里的关键证据", "other-hash", 0.5));
        when(search.search(eq(context.projectId()), eq("关键证据"), eq(List.of()), eq(20)))
                .thenReturn(candidates);
        KnowledgeSearchAgentTool tool = new KnowledgeSearchAgentTool(
                access, search, new KnowledgeContextBuilder(), json);

        var result = tool.execute(context,
                json.createObjectNode().put("query", "关键证据"));

        // 单一高分文档不能把来源名额占满：另一文档的有效证据必须进得来，且引用身份正确。
        assertThat(result.data().path("sources")).hasSize(6);
        assertThat(result.citations()).extracting(com.shitulelv.aicollab.agent.domain.model.AgentCitation::chunkId)
                .contains(otherChunk);
        var citation = result.citations().stream()
                .filter(item -> item.chunkId().equals(otherChunk)).findFirst().orElseThrow();
        assertThat(citation.documentId()).isEqualTo(otherDocument);
        assertThat(citation.quote()).isEqualTo("附属文档里的关键证据");
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
                new UUID(0,title.charAt(0)), context.projectId(), title, "描述",
                null, null, null, null, TaskStatus.TODO, TaskPriority.MEDIUM,
                null, null, null, 0, 0, List.of(), null);
    }
}
