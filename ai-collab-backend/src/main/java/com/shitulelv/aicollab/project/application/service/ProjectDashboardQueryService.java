package com.shitulelv.aicollab.project.application.service;

import com.shitulelv.aicollab.project.application.view.DashboardActivityView;
import com.shitulelv.aicollab.project.application.view.DashboardView;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.infrastructure.mapper.ProjectDashboardMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectDashboardQueryService {
    private final ProjectDashboardMapper dashboardMapper;
    private final AuditLogQueryService auditLogQuery;
    private final ProjectAccessGuard accessGuard;

    public ProjectDashboardQueryService(
            ProjectDashboardMapper dashboardMapper,
            AuditLogQueryService auditLogQuery,
            ProjectAccessGuard accessGuard) {
        this.dashboardMapper = dashboardMapper;
        this.auditLogQuery = auditLogQuery;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public DashboardView getDashboard(UUID projectId, UUID userId) {
        accessGuard.requireMember(projectId, userId);

        var project = dashboardMapper.findProjectInfo(projectId, userId);
        var taskStats = dashboardMapper.countTasksByStatus(projectId);
        var milestones = dashboardMapper.listMilestoneProgress(projectId);
        var recentTasks = dashboardMapper.listRecentTasks(projectId, 8);
        var recentDocuments = dashboardMapper.listRecentDocuments(projectId, 5);
        List<DashboardActivityView> recentActivities = auditLogQuery.recentDashboardActivities(projectId, userId, 10);

        return new DashboardView(
                project,
                taskStats,
                milestones,
                recentTasks,
                recentDocuments,
                recentActivities);
    }
}
