package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.*;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.*;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentRunRestoreTest {
    private AgentRunView run(UUID project, UUID session, AgentRunStatus st) {
        return new AgentRunView(UUID.randomUUID(), session, project, UUID.randomUUID(), null, "USER", 0, "goal",
                st, 12, 16, 3, 100000, 8000, 0, 0, 0, 0, 0, false, false, false, 0, null, null, null, null, 1,
                OffsetDateTime.now(), OffsetDateTime.now());
    }
    private AgentRunService svc(AgentRepository repo, AgentApprovalRepository approvals) {
        return new AgentRunService(mock(ProjectAccessGuard.class), repo, mock(com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry.class),
                new ObjectMapper(), mock(com.shitulelv.aicollab.agent.application.runtime.AgentEventService.class),
                mock(AgentEventRepository.class), approvals);
    }
    @Test void latestRunReturnsNullWhenNone() {
        var repo = mock(AgentRepository.class);
        var pid = UUID.randomUUID(); var sid = UUID.randomUUID(); var user = UUID.randomUUID();
        when(repo.findSession(pid, sid)).thenReturn(Optional.of(mock(AgentSessionView.class)));
        when(repo.findLatestRun(pid, sid)).thenReturn(Optional.empty());
        assertThat(svc(repo, mock(AgentApprovalRepository.class)).latestRun(pid, sid, user)).isNull();
    }
    @Test void latestRunRequiresMembership() {
        var access = mock(ProjectAccessGuard.class);
        doThrow(new BusinessException(com.shitulelv.aicollab.common.exception.ErrorCode.AUTH_FORBIDDEN)).when(access).requireMember(any(), any());
        var svc = new AgentRunService(access, mock(AgentRepository.class), mock(com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry.class),
                new ObjectMapper(), mock(com.shitulelv.aicollab.agent.application.runtime.AgentEventService.class),
                mock(AgentEventRepository.class), mock(AgentApprovalRepository.class));
        assertThatThrownBy(() -> svc.latestRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(BusinessException.class);
    }
    @Test void runApprovalsScopedAndChecked() {
        var repo = mock(AgentRepository.class);
        var approvals = mock(AgentApprovalRepository.class);
        var pid = UUID.randomUUID(); var rid = UUID.randomUUID(); var user = UUID.randomUUID();
        when(repo.findRun(pid, rid)).thenReturn(Optional.of(run(pid, UUID.randomUUID(), AgentRunStatus.RUNNING)));
        when(approvals.listByRun(pid, rid)).thenReturn(List.of());
        assertThat(svc(repo, approvals).runApprovals(pid, rid, user)).isEmpty();
        verify(approvals).listByRun(pid, rid);
        verify(approvals, never()).list(any(), any());
    }
}
