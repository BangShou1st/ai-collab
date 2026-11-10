package com.shitulelv.aicollab.agent.infrastructure.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** 记忆来源权威核验查询(M-09):只做存在性 + 项目归属 + 当前可访问性判断,不做字符串拼接。 */
@Repository
public class AgentMemorySourceRepository {
    private final JdbcTemplate jdbc;
    public AgentMemorySourceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * DOCUMENT 来源核验:文档必须存在、归属同一项目,且当前可读
     * (connector_document_visibility.readable 为 fail-closed 视图,覆盖 status/access_blocked/Connector 撤权)。
     * 跨项目或不存在均返回 false,由调用方拒绝。
     */
    public boolean documentReadableInProject(UUID projectId, UUID documentId) {
        Boolean result = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM project_document d
                  JOIN connector_document_visibility v ON v.id=d.id AND v.project_id=d.project_id
                  WHERE d.project_id=? AND d.id=? AND v.readable)
                """, Boolean.class, projectId, documentId);
        return Boolean.TRUE.equals(result);
    }
}
