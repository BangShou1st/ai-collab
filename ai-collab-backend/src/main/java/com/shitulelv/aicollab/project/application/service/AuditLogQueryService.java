package com.shitulelv.aicollab.project.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.project.application.view.AuditLogItemView;
import com.shitulelv.aicollab.project.application.view.AuditLogPageView;
import com.shitulelv.aicollab.project.application.view.DashboardActivityView;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.infrastructure.mapper.AuditLogMapper;
import com.shitulelv.aicollab.project.infrastructure.mapper.AuditLogRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditLogQueryService {
    private static final Logger log = LoggerFactory.getLogger(AuditLogQueryService.class);

    private final AuditLogMapper mapper;
    private final ProjectAccessGuard accessGuard;
    private final AuditSummaryFormatter formatter;
    private final ObjectMapper objectMapper;

    public AuditLogQueryService(
            AuditLogMapper mapper,
            ProjectAccessGuard accessGuard,
            AuditSummaryFormatter formatter,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.accessGuard = accessGuard;
        this.formatter = formatter;
        this.objectMapper = objectMapper;
    }

    /**
     * 分页查询审计日志。仅 OWNER/ADMIN 可调用。
     */
    @Transactional(readOnly = true)
    public AuditLogPageView page(UUID projectId, UUID userId, int page, int size) {
        accessGuard.requireAdmin(projectId, userId);
        int offset = (page - 1) * size;
        long total = mapper.countByProjectId(projectId);
        List<AuditLogItemView> items = mapper.page(projectId, size, offset).stream()
                .map(this::toView)
                .toList();
        return new AuditLogPageView(items, page, size, total);
    }

    /**
     * 获取最近活动（Dashboard 使用，脱敏）。所有项目成员可调用。
     * 返回 DashboardActivityView，不暴露 requestId 和完整 detail。
     */
    @Transactional(readOnly = true)
    public List<DashboardActivityView> recentDashboardActivities(UUID projectId, UUID userId, int limit) {
        accessGuard.requireMember(projectId, userId);
        return mapper.recentActivities(projectId, limit).stream()
                .map(this::toDashboardActivityView)
                .toList();
    }

    /**
     * AuditLogRow → AuditLogItemView 安全转换。
     * detailJson 为空、null、非法 JSON 时降级为空对象。
     */
    private AuditLogItemView toView(AuditLogRow row) {
        Object detail = parseDetail(row.detailJson());
        String summary = formatter.format(
                row.userDisplayName() != null ? row.userDisplayName() : "未知用户",
                row.action(),
                row.entityType(),
                row.detailJson());
        return new AuditLogItemView(
                row.id(),
                row.userId(),
                row.userDisplayName(),
                row.action(),
                row.entityType(),
                row.entityId(),
                detail,
                summary,
                row.requestId(),
                row.createdAt());
    }

    /**
     * AuditLogRow → DashboardActivityView（脱敏，无 requestId/detail）。
     */
    private DashboardActivityView toDashboardActivityView(AuditLogRow row) {
        String summary = formatter.format(
                row.userDisplayName() != null ? row.userDisplayName() : "未知用户",
                row.action(),
                row.entityType(),
                row.detailJson());
        return new DashboardActivityView(
                row.id(),
                row.userDisplayName(),
                row.action(),
                row.entityType(),
                row.entityId(),
                summary,
                row.createdAt());
    }

    /**
     * 安全解析 detail JSON 字符串为 Object（Map/List）。
     * 降级为空 Map，不会返回 null 或原始字符串。
     */
    private Object parseDetail(String detailJson) {
        if (detailJson == null || detailJson.isBlank() || "{}".equals(detailJson.trim())) {
            return Collections.emptyMap();
        }
        try {
            JsonNode node = objectMapper.readTree(detailJson);
            if (node.isObject()) {
                return objectMapper.convertValue(node, Map.class);
            }
            return Collections.emptyMap();
        } catch (Exception e) {
            log.debug("Failed to parse audit detail, falling back to empty object: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
