package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.infrastructure.tool.ProjectMemberListAgentTool;
import com.shitulelv.aicollab.project.application.service.ProjectMemberApplicationService;
import com.shitulelv.aicollab.project.application.view.MemberView;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectMemberListAgentToolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void returnsRealMembersAndAStableRecommendationWhenRequested() {
        ProjectMemberApplicationService members = mock(ProjectMemberApplicationService.class);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(members.list(projectId, userId)).thenReturn(List.of(
                new MemberView(firstId, "dev1", "开发一", ProjectRole.MEMBER, OffsetDateTime.now()),
                new MemberView(secondId, "dev2", "开发二", ProjectRole.MEMBER, OffsetDateTime.now())));
        ProjectMemberListAgentTool tool = new ProjectMemberListAgentTool(members, json);
        AgentToolContext context = new AgentToolContext(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                projectId, userId, "MEMBER", false, 0);

        var result = tool.execute(context, json.createObjectNode().put("recommendOne", true)).data();

        assertThat(result.path("items")).hasSize(2);
        assertThat(result.path("recommendedAssignee").path("userId").asText())
                .isIn(firstId.toString(), secondId.toString());
        assertThat(tool.execute(context, json.createObjectNode().put("recommendOne", true)).data()
                .path("recommendedAssignee").path("userId").asText())
                .isEqualTo(result.path("recommendedAssignee").path("userId").asText());
    }
}
