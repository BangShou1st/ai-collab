package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentMemoryRepository {
    private final JdbcTemplate jdbc;
    public AgentMemoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<AgentMemoryView> list(UUID projectId, boolean activeOnly, int limit) {
        return jdbc.query("SELECT * FROM agent_memory WHERE project_id=? "
                        + (activeOnly ? "AND status='ACTIVE' " : "")
                        + "ORDER BY updated_at DESC,id DESC LIMIT ?", mapper(), projectId, limit);
    }

    public Optional<AgentMemoryView> find(UUID projectId, UUID id) {
        return jdbc.query("SELECT * FROM agent_memory WHERE project_id=? AND id=?", mapper(), projectId, id)
                .stream().findFirst();
    }

    public AgentMemoryView create(UUID projectId, UUID userId, String type, String title,
                                  String content, String sourceType, UUID sourceId) {
        return jdbc.queryForObject("""
                INSERT INTO agent_memory(id,project_id,type,title,content,source_type,source_id,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?) RETURNING *
                """, mapper(), UUID.randomUUID(), projectId, type, title, content,
                sourceType, sourceId, userId, userId);
    }

    public boolean update(UUID projectId, UUID id, UUID userId, int version,
                          String type, String title, String content, String sourceType, UUID sourceId) {
        return jdbc.update("""
                UPDATE agent_memory SET type=?,title=?,content=?,source_type=?,source_id=?,
                  updated_by=?,version=version+1,updated_at=now()
                WHERE project_id=? AND id=? AND version=?
                """, type, title, content, sourceType, sourceId, userId, projectId, id, version) == 1;
    }

    public boolean disable(UUID projectId, UUID id, UUID userId, int version) {
        return jdbc.update("""
                UPDATE agent_memory SET status='DISABLED',updated_by=?,version=version+1,updated_at=now()
                WHERE project_id=? AND id=? AND version=? AND status='ACTIVE'
                """, userId, projectId, id, version) == 1;
    }

    private static RowMapper<AgentMemoryView> mapper() {
        return (rs, row) -> new AgentMemoryView(rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("type"), rs.getString("title"),
                rs.getString("content"), rs.getString("source_type"), rs.getObject("source_id", UUID.class),
                rs.getString("status"), rs.getObject("created_by", UUID.class),
                rs.getObject("updated_by", UUID.class), rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
