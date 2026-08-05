package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.agent.api.dto.AgentSkillResponse;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
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
public class AgentSkillController {
    private final ProjectAccessGuard access; private final AgentSkillRegistry skills;
    public AgentSkillController(ProjectAccessGuard access, AgentSkillRegistry skills) {
        this.access = access; this.skills = skills;
    }
    @GetMapping public ApiResponse<List<AgentSkillResponse>> list(
            @PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
        access.requireMember(projectId, userId(jwt));
        return ApiResponse.success(skills.listAll().stream().map(AgentSkillResponse::from).toList());
    }
    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
