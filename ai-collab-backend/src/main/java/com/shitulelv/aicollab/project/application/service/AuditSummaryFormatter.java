package com.shitulelv.aicollab.project.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 审计摘要格式化器。
 * <p>
 * 从 audit_log 的 action、entityType 和 detail 生成人类可读摘要。
 * 历史空 detail 有稳定回退，不会显示空白或 [object Object]。
 */
@Component
public class AuditSummaryFormatter {
    private static final Logger log = LoggerFactory.getLogger(AuditSummaryFormatter.class);

    private final ObjectMapper objectMapper;

    public AuditSummaryFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static final Map<String, String> ACTION_LABELS = Map.ofEntries(
            Map.entry("PROJECT_CREATED", "创建了项目"),
            Map.entry("PROJECT_UPDATED", "更新了项目"),
            Map.entry("PROJECT_DELETED", "删除了项目"),
            Map.entry("PROJECT_INVITATION_CREATED", "创建了项目邀请"),
            Map.entry("PROJECT_INVITATION_ACCEPTED", "接受了项目邀请"),
            Map.entry("PROJECT_MEMBER_ROLE_CHANGED", "变更了成员角色"),
            Map.entry("PROJECT_MEMBER_REMOVED", "移除了项目成员"),
            Map.entry("MILESTONE_CREATED", "创建了里程碑"),
            Map.entry("MILESTONE_UPDATED", "更新了里程碑"),
            Map.entry("MILESTONE_DELETED", "删除了里程碑"),
            Map.entry("TASK_CREATED", "创建了任务"),
            Map.entry("TASK_UPDATED", "更新了任务"),
            Map.entry("TASK_DELETED", "删除了任务"),
            Map.entry("TASK_STATUS_CHANGED", "变更了任务状态"),
            Map.entry("TASK_DEPENDENCIES_REPLACED", "更新了任务依赖"),
            Map.entry("TASK_COMMENT_CREATED", "发表了任务评论"),
            Map.entry("TASK_COMMENT_UPDATED", "更新了任务评论"),
            Map.entry("TASK_COMMENT_DELETED", "删除了任务评论"),
            Map.entry("DOCUMENT_UPLOADED", "上传了文档"),
            Map.entry("DOCUMENT_INDEXED", "完成了文档处理"),
            Map.entry("DOCUMENT_PROCESSING_FAILED", "文档处理失败"),
            Map.entry("DOCUMENT_RETRY_REQUESTED", "重新处理了文档"),
            Map.entry("DOCUMENT_DELETED", "删除了文档"),
            Map.entry("TASK_PLAN_CREATED", "创建了 AI 任务规划"),
            Map.entry("TASK_PLAN_CANCELED", "取消了 AI 任务规划"),
            Map.entry("TASK_PLAN_DETAIL_RETRIED", "重试了 AI 规划细节生成"),
            Map.entry("TASK_PLAN_REGENERATED", "重新生成了 AI 任务规划"),
            Map.entry("TASK_PLAN_VERSION_SAVED", "保存了 AI 规划版本"),
            Map.entry("TASK_PLAN_VERSION_RESTORED", "恢复了 AI 规划版本"),
            Map.entry("TASK_PLAN_DELETED", "删除了 AI 任务规划"),
            Map.entry("TASK_PLAN_CONFIRMATION_FAILED", "确认 AI 任务规划失败"),
            Map.entry("TASK_PLAN_CONFIRMED", "确认了 AI 任务规划")
    );

    private static final Map<String, String> ENTITY_LABELS = Map.of(
            "PROJECT", "项目",
            "PROJECT_INVITATION", "项目邀请",
            "PROJECT_MEMBER", "项目成员",
            "MILESTONE", "里程碑",
            "TASK", "任务",
            "TASK_COMMENT", "任务评论",
            "PROJECT_DOCUMENT", "文档",
            "AI_TASK_PLAN", "AI 任务规划",
            "AI_TASK_PLAN_VERSION", "AI 规划版本"
    );

    /**
     * 生成摘要。detail 为空或缺失时使用安全回退。
     *
     * @param userDisplayName 操作人显示名
     * @param action          操作类型
     * @param entityType      实体类型
     * @param detailJson      结构化详情 JSON 字符串（可能为 null 或 "{}"）
     * @return 人类可读摘要
     */
    public String format(String userDisplayName, String action, String entityType, String detailJson) {
        String actionLabel = ACTION_LABELS.get(action);
        String name = extractName(detailJson);

        if (actionLabel != null && name != null && !name.isEmpty()) {
            return userDisplayName + actionLabel + "「" + name + "」";
        }
        if (actionLabel != null) {
            return userDisplayName + actionLabel;
        }
        // 未知内部枚举不直接暴露给用户；已知对象仍可提供安全的中文上下文。
        String entityLabel = ENTITY_LABELS.get(entityType);
        return entityLabel == null
                ? userDisplayName + "执行了操作"
                : userDisplayName + "对" + entityLabel + "执行了操作";
    }

    /**
     * 从 detail JSON 中提取实体名称。
     * 常见字段名：title、name、displayName、filename、originalFilename。
     */
    private String extractName(String detailJson) {
        if (detailJson == null || detailJson.isBlank() || "{}".equals(detailJson.trim())) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(detailJson);
            for (String field : new String[]{"title", "name", "displayName", "filename", "originalFilename"}) {
                JsonNode fieldNode = node.get(field);
                if (fieldNode != null && fieldNode.isTextual()) {
                    String value = fieldNode.asText();
                    if (!value.isEmpty()) {
                        return value.length() > 50 ? value.substring(0, 50) + "…" : value;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse audit detail JSON", e);
        }
        return null;
    }
}
