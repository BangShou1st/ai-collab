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
    private final DashboardCacheService dashboardCache;

    public ProjectDashboardQueryService(
            ProjectDashboardMapper dashboardMapper,
            AuditLogQueryService auditLogQuery,
            ProjectAccessGuard accessGuard,
            DashboardCacheService dashboardCache) {
        this.dashboardMapper = dashboardMapper;
        this.auditLogQuery = auditLogQuery;
        this.accessGuard = accessGuard;
        this.dashboardCache = dashboardCache;
    }

    @Transactional(readOnly = true)
    public DashboardView getDashboard(UUID projectId, UUID userId) {
        accessGuard.requireMember(projectId, userId);

        var project = dashboardMapper.findProjectInfo(projectId, userId);
        // 权限校验通过后，只读取与用户无关的项目级聚合缓存。
        var taskStats = dashboardCache.getOrLoadStats(projectId, () -> dashboardMapper.countTasksByStatus(projectId));
        var milestones = dashboardCache.getOrLoadMilestones(projectId, () -> dashboardMapper.listMilestoneProgress(projectId));
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
