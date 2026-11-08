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
        // 仅缓存与用户无关的项目级聚合；权限校验在缓存读取之前，不会被命中绕过。
        // getOrLoad 内部完成：miss 同 key 互斥回源（防击穿）、空结果短 TTL 空标记（防穿透）、
        // 逻辑过期返回旧值 + 异步单飞重建（热点 key 防击穿），调用方只关心拿到的聚合结果
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
