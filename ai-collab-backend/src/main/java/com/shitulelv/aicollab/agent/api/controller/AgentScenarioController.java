package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.agent.api.dto.AgentScenarioResponse;
import com.shitulelv.aicollab.agent.domain.model.AgentScenarioRegistry;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/skills")
public class AgentScenarioController {
    private final ProjectAccessGuard access; private final AgentScenarioRegistry skills;
    public AgentScenarioController(ProjectAccessGuard access, AgentScenarioRegistry skills) {
        this.access = access; this.skills = skills;
    }
    @GetMapping public ApiResponse<List<AgentScenarioResponse>> list(
            @PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
        access.requireMember(projectId, userId(jwt));
        return ApiResponse.success(skills.listAll().stream().map(AgentScenarioResponse::from).toList());
    }
    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
