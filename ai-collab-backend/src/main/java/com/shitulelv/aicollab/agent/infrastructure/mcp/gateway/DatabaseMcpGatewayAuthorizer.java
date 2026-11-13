package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;

/**
 * Ed25519 身份必须绑定真实 run/project 与当前项目成员关系；静态绑定仅作为 local/test 显式白名单。
 */
final class DatabaseMcpGatewayAuthorizer implements McpGatewayAuthorizer {

    private final AgentRepository runs;
    private final ProjectAccessGuard access;

    DatabaseMcpGatewayAuthorizer(AgentRepository runs, ProjectAccessGuard access) {
        this.runs = runs;
        this.access = access;
    }

    @Override
    public AuthorizedContext authorize(McpGatewayIdentity identity) {
        if (identity.staticTest()) {
            return new AuthorizedContext(identity.projectRef(), identity.runRef(), null, "STATIC_TEST");
        }
        AgentRunView run = runs.findRun(identity.projectRef(), identity.runRef())
                .orElseThrow(() -> McpGatewayException.business(McpGatewayException.Kind.UNAUTHORIZED));
        if (run.status().terminal()) {
            throw McpGatewayException.business(McpGatewayException.Kind.UNAUTHORIZED);
        }
        try {
            access.requireMember(run.projectId(), run.requesterId());
        } catch (BusinessException unauthorized) {
            throw McpGatewayException.business(McpGatewayException.Kind.UNAUTHORIZED);
        }
        return new AuthorizedContext(run.projectId(), run.id(), run.requesterId(), "SERVICE");
    }

    @Override
    public boolean stillAuthorized(McpGatewayIdentity identity) {
        if (identity.staticTest()) {
            return true;
        }
        return runs.findRun(identity.projectRef(), identity.runRef())
                .filter(run -> !run.status().terminal())
                .map(run -> {
                    try {
                        access.requireMember(run.projectId(), run.requesterId());
                        return true;
                    } catch (BusinessException revoked) {
                        return false;
                    }
                })
                .orElse(false);
    }
}
