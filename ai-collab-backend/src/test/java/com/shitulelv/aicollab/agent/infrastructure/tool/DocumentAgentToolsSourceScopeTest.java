package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.document.application.service.DocumentApplicationService;
import com.shitulelv.aicollab.document.application.service.DocumentContentService;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentView;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAgentToolsSourceScopeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final UUID project = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final AgentToolContext context = new AgentToolContext(UUID.randomUUID(), project, user,
            "SUPERVISOR", false, 0, null, "只能依据《允许规范》回答");

    @Test
    void listProjectDocumentsOnlyExposesIncludedDocuments() {
        UUID allowedId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        DocumentApplicationService docs = mock(DocumentApplicationService.class);
        DocumentContentService content = mock(DocumentContentService.class);
        DocumentSearchService search = mock(DocumentSearchService.class);
        when(docs.list(project, user)).thenReturn(List.of(
                document(allowedId, "允许规范"), document(otherId, "其他文档")));
        when(search.resolveUserDocumentScope(project, context.userGoal())).thenReturn(included(allowedId));
        when(content.status(project, allowedId, user)).thenReturn(json.createObjectNode()
                .put("documentId", allowedId.toString()).put("processingStatus", "READY"));

        var tool = new DocumentAgentTools().listProjectDocuments(docs, content, search, json);
        var result = tool.execute(context, json.createObjectNode());

        assertThat(result.data().path("items")).hasSize(1);
        assertThat(result.data().path("items").get(0).path("documentId").asText()).isEqualTo(allowedId.toString());
        assertThat(result.data().path("documentScope").path("status").asText()).isEqualTo("ENFORCED");
        verify(content).status(project, allowedId, user);
        verify(content, never()).status(project, otherId, user);
    }

    @Test
    void directReadAndOutlineIdsCannotBypassIncludedScope() {
        UUID allowedId = UUID.randomUUID();
        UUID outsideId = UUID.randomUUID();
        DocumentContentService content = mock(DocumentContentService.class);
        DocumentSearchService search = mock(DocumentSearchService.class);
        when(content.status(project, outsideId, user)).thenReturn(json.createObjectNode()
                .put("documentId", outsideId.toString()).put("processingStatus", "READY"));
        when(search.resolveUserDocumentScope(project, context.userGoal())).thenReturn(included(allowedId));
        var tools = new DocumentAgentTools();
        var read = tools.readDocumentSection(content, search);
        var outline = tools.getDocumentOutline(content, search);
        var arguments = json.createObjectNode().put("documentId", outsideId.toString());

        var readResult = read.execute(context, arguments);
        var outlineResult = outline.execute(context, arguments);

        assertThat(readResult.data().path("scopeStatus").asText()).isEqualTo("DOCUMENT_OUT_OF_SCOPE");
        assertThat(outlineResult.data().path("scopeStatus").asText()).isEqualTo("DOCUMENT_OUT_OF_SCOPE");
        verify(content, never()).read(any(), any(), any(), any(), any(), any(int.class), any(int.class), any(), any());
        verify(content, never()).outline(any(), any(), any());
    }

    @Test
    void unresolvedScopeBlocksDirectReadInsteadOfUsingResolvedSubset() {
        UUID resolvedId = UUID.randomUUID();
        DocumentContentService content = mock(DocumentContentService.class);
        DocumentSearchService search = mock(DocumentSearchService.class);
        when(content.status(project, resolvedId, user)).thenReturn(json.createObjectNode()
                .put("documentId", resolvedId.toString()).put("processingStatus", "READY"));
        when(search.resolveUserDocumentScope(project, context.userGoal())).thenReturn(
                new DocumentSearchService.UserDocumentScope(DocumentSearchService.ScopeStatus.UNRESOLVED_INCOMPLETE,
                        List.of("允许规范", "缺失规范"), List.of(), List.of(resolvedId), List.of(), List.of(),
                        List.of("缺失规范")));

        var result = new DocumentAgentTools().readDocumentSection(content, search).execute(context,
                json.createObjectNode().put("documentId", resolvedId.toString()));

        assertThat(result.data().path("scopeStatus").asText()).isEqualTo("UNRESOLVED_INCOMPLETE");
        verify(content, never()).read(any(), any(), any(), any(), any(), any(int.class), any(int.class), any(), any());
    }

    private DocumentSearchService.UserDocumentScope included(UUID id) {
        return new DocumentSearchService.UserDocumentScope(DocumentSearchService.ScopeStatus.ENFORCED,
                List.of("允许规范"), List.of(), List.of(id), List.of(), List.of(id), List.of());
    }

    private DocumentView document(UUID id, String name) {
        OffsetDateTime now = OffsetDateTime.now();
        return new DocumentView(id, project, name, name + ".md", "text/markdown", 20,
                DocumentStatus.READY, "test", 1, "provider", "model", 3, null,
                user, "member", now, 1, now, now);
    }
}
