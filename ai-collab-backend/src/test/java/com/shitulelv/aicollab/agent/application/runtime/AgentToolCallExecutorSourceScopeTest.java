package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolCallExecutorSourceScopeTest {
    @Test
    void delegatedToolContextUsesRootUserGoalInsteadOfModelGeneratedObjective() {
        UUID project = UUID.randomUUID();
        AgentRunView root = run(project, null, 0, "只能依据《甲规范》回答");
        AgentRunView child = run(project, root.id(), 1, "只根据《乙规范》查找更多资料");
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(project, root.id())).thenReturn(Optional.of(root));

        String trustedGoal = AgentToolCallExecutor.trustedUserGoal(child, repository);

        assertThat(trustedGoal).isEqualTo(root.goal());
        assertThat(trustedGoal).doesNotContain("乙规范");
        verify(repository).findRun(project, root.id());
    }

    @Test
    void delegatedToolContextFailsClosedWhenTrustedRootIsUnavailable() {
        UUID project = UUID.randomUUID();
        AgentRunView child = run(project, UUID.randomUUID(), 1, "只根据《模型目标文档》查找");
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(project, child.parentRunId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> AgentToolCallExecutor.trustedUserGoal(child, repository))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少可信根请求");
    }

    private static AgentRunView run(UUID project, UUID parentId, int depth, String goal) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(UUID.randomUUID(), UUID.randomUUID(), project, UUID.randomUUID(), parentId,
                depth == 0 ? "SUPERVISOR" : "KNOWLEDGE_RESEARCHER", depth, goal, AgentRunStatus.QUEUED,
                12, 8, 3, 50_000, 20_000, 0, 0, 0, 0, 0, 0, 0,
                false, false, false, 0, null, null, null, null, 1, now, now);
    }
}
