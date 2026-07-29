package com.shitulelv.aicollab.project.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.project.infrastructure.mapper.AuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_DETAIL_LENGTH = 4096;

    private final AuditLogMapper mapper;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 保留旧重载：写入空 detail，保持所有历史调用可编译。
     */
    public void write(UUID projectId, UUID userId, String action, String entityType, UUID entityId) {
        mapper.insert(UUID.randomUUID(), projectId, userId, action, entityType, entityId, UUID.randomUUID());
    }

    /**
     * 新增重载：写入安全结构化 detail。
     * <p>
     * detail 只保存展示和审计需要的短字段，不保存描述全文、文档正文、模型 Prompt、Token 或密钥。
     * 序列化失败时降级为写入空 detail，不写入任意 toString()。
     * 超大内容通过逐字段截断保证始终生成合法 JSON。
     */
    public void write(UUID projectId, UUID userId, String action, String entityType,
                      UUID entityId, Map<String, ?> detail) {
        String detailJson = serializeDetail(detail);
        mapper.insertWithDetail(UUID.randomUUID(), projectId, userId, action, entityType, entityId,
                detailJson, UUID.randomUUID());
    }

    /**
     * 安全序列化 detail。逐字段限制文本值长度，保证输出始终是合法 JSON。
     */
    private String serializeDetail(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) {
            return "{}";
        }
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<String, ?> entry : detail.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String s) {
                    safe.put(entry.getKey(), s.length() > 200 ? s.substring(0, 200) + "…" : s);
                } else {
                    safe.put(entry.getKey(), value);
                }
            }
            String json = objectMapper.writeValueAsString(safe);
            // 二次保护：整体超长时降级为空对象（保证合法 JSON）
            if (json.length() > MAX_DETAIL_LENGTH) {
                log.warn("Audit detail too large ({} bytes), falling back to empty", json.length());
                return "{}";
            }
            return json;
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit detail, falling back to empty", e);
            return "{}";
        }
    }
}
