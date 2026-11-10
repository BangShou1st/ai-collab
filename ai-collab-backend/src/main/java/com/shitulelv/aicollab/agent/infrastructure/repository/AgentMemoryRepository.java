package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.shitulelv.aicollab.agent.application.view.AgentMemoryRevisionView;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.agent.domain.model.AgentMemoryRevisionOperation;
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

    /**
     * excludeRecallUnsafe=true 时(召回与工具路径)过滤 STALE/CONFLICTED/SOURCE_REVOKED
     * 以及已被 SUPERSEDE 替代的记录(superseded_by 非空,防止新旧规则同时生效,验收矩阵 M-03);
     * UNVERIFIED 保留召回(legacy 兼容)。管理列表(activeOnly=false)不过滤,便于管理员复核。
     */
    public List<AgentMemoryView> list(UUID projectId, boolean activeOnly, int limit, boolean excludeRecallUnsafe) {
        return jdbc.query("""
                SELECT m.* FROM agent_memory m WHERE m.project_id=? AND (NOT ? OR m.status='ACTIVE')
                  AND (NOT ? OR (m.verification_state NOT IN ('STALE','CONFLICTED','SOURCE_REVOKED')
                    AND m.superseded_by IS NULL))
                  AND NOT EXISTS (SELECT 1 FROM connector_hidden_agent_memory h WHERE h.project_id=m.project_id AND h.memory_id=m.id AND h.cleared_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM connector_source s LEFT JOIN connector_oauth_authorization a
                    ON a.id=s.user_authorization_id AND a.project_id=s.project_id
                    WHERE s.project_id=m.project_id
                      AND (m.created_at<=GREATEST(s.updated_at,COALESCE(a.updated_at,s.updated_at))
                        OR (a.access_expires_at<=now() AND m.created_at<=a.access_expires_at))
                      AND (s.status<>'ACTIVE' OR (s.user_authorization_id IS NOT NULL
                        AND (a.id IS NULL OR a.status<>'ACTIVE' OR a.access_expires_at<=now()))))
                  AND NOT EXISTS (SELECT 1 FROM external_document e JOIN connector_source s ON s.id=e.source_id
                    WHERE s.project_id=m.project_id AND e.status='BLOCKED' AND m.created_at<=e.updated_at)
                ORDER BY m.updated_at DESC,m.id DESC LIMIT ?
                """, mapper(), projectId, activeOnly, excludeRecallUnsafe, limit);
    }

    /** 兼容保留:不做验证状态过滤(仅既有调用方:Connector 撤权回归等)。 */
    public List<AgentMemoryView> list(UUID projectId, boolean activeOnly, int limit) {
        return list(projectId, activeOnly, limit, false);
    }

    public Optional<AgentMemoryView> find(UUID projectId, UUID id) {
        return jdbc.query("""
                SELECT m.* FROM agent_memory m WHERE m.project_id=? AND m.id=?
                  AND NOT EXISTS (SELECT 1 FROM connector_hidden_agent_memory h WHERE h.project_id=m.project_id AND h.memory_id=m.id AND h.cleared_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM connector_source s LEFT JOIN connector_oauth_authorization a
                    ON a.id=s.user_authorization_id AND a.project_id=s.project_id
                    WHERE s.project_id=m.project_id
                      AND (m.created_at<=GREATEST(s.updated_at,COALESCE(a.updated_at,s.updated_at))
                        OR (a.access_expires_at<=now() AND m.created_at<=a.access_expires_at))
                      AND (s.status<>'ACTIVE' OR (s.user_authorization_id IS NOT NULL
                        AND (a.id IS NULL OR a.status<>'ACTIVE' OR a.access_expires_at<=now()))))
                  AND NOT EXISTS (SELECT 1 FROM external_document e JOIN connector_source s ON s.id=e.source_id
                    WHERE s.project_id=m.project_id AND e.status='BLOCKED' AND m.created_at<=e.updated_at)
                """, mapper(), projectId, id)
                .stream().findFirst();
    }

    /** 创建路径恒为 UNVERIFIED(依赖列默认值,不接收客户端验证状态),不伪造 VERIFIED。 */
    public AgentMemoryView create(UUID projectId, UUID userId, String type, String title,
                                  String content, String sourceType, UUID sourceId) {
        return jdbc.queryForObject("""
                INSERT INTO agent_memory(id,project_id,type,title,content,source_type,source_id,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?) RETURNING *
                """, mapper(), UUID.randomUUID(), projectId, type, title, content,
                sourceType, sourceId, userId, userId);
    }

    public boolean update(UUID projectId, UUID id, UUID userId, int version,
                          String type, String title, String content, String sourceType, UUID sourceId,
                          String verificationState, OffsetDateTime verifiedAt, UUID verifiedBy) {
        return jdbc.update("""
                UPDATE agent_memory SET type=?,title=?,content=?,source_type=?,source_id=?,
                  verification_state=?,verified_at=?,verified_by=?,
                  updated_by=?,version=version+1,updated_at=now()
                WHERE project_id=? AND id=? AND version=?
                """, type, title, content, sourceType, sourceId,
                verificationState, verifiedAt, verifiedBy, userId, projectId, id, version) == 1;
    }

    public boolean disable(UUID projectId, UUID id, UUID userId, int version) {
        return jdbc.update("""
                UPDATE agent_memory SET status='DISABLED',updated_by=?,version=version+1,updated_at=now()
                WHERE project_id=? AND id=? AND version=? AND status='ACTIVE'
                """, userId, projectId, id, version) == 1;
    }

    /** disable 路径同时转移验证状态(单条 CAS 更新,version 仍 +1)。 */
    public boolean disable(UUID projectId, UUID id, UUID userId, int version,
                           String verificationState, OffsetDateTime verifiedAt, UUID verifiedBy) {
        return jdbc.update("""
                UPDATE agent_memory SET status='DISABLED',verification_state=?,verified_at=?,verified_by=?,
                  updated_by=?,version=version+1,updated_at=now()
                WHERE project_id=? AND id=? AND version=? AND status='ACTIVE'
                """, verificationState, verifiedAt, verifiedBy, userId, projectId, id, version) == 1;
    }

    private static RowMapper<AgentMemoryView> mapper() {
        return (rs, row) -> new AgentMemoryView(rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("type"), rs.getString("title"),
                rs.getString("content"), rs.getString("source_type"), rs.getObject("source_id", UUID.class),
                rs.getString("status"), rs.getObject("created_by", UUID.class),
                rs.getObject("updated_by", UUID.class), rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                rs.getString("verification_state"), rs.getObject("verified_at", OffsetDateTime.class),
                rs.getObject("verified_by", UUID.class), rs.getObject("superseded_by", UUID.class));
    }

    /**
     * SUPERSEDE:管理员把旧记忆指向替代者。仅 ACTIVE 记忆可被替代,CAS version 乐观锁;
     * 不修改 status(替代关系由 superseded_by 单独表达,管理视图仍可见)。
     */
    public boolean supersede(UUID projectId, UUID id, UUID userId, int version, UUID targetId) {
        return jdbc.update("""
                UPDATE agent_memory SET superseded_by=?,updated_by=?,version=version+1,updated_at=now()
                WHERE project_id=? AND id=? AND version=? AND status='ACTIVE'
                """, targetId, userId, projectId, id, version) == 1;
    }

    /** SUPERSEDE 校验/环检测用的原始状态读取(不带召回可见性过滤):返回 [status, superseded_by]。 */
    public Optional<String[]> findRawState(UUID projectId, UUID id) {
        return jdbc.query("""
                SELECT status,superseded_by FROM agent_memory WHERE project_id=? AND id=?
                """, (rs, row) -> new String[]{rs.getString("status"),
                        rs.getString("superseded_by")}, projectId, id).stream().findFirst();
    }

    /** 行级锁(事务内调用):SUPERSEDE 校验链路加锁,串行化同记忆的并发替代,防环。 */
    public void lock(UUID projectId, UUID id) {
        jdbc.query("SELECT id FROM agent_memory WHERE project_id=? AND id=? FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), projectId, id);
    }

    /** 追加一条修订历史;与主表更新同事务由 Service 层 @Transactional 保证。 */
    public AgentMemoryRevisionView insertRevision(UUID projectId, UUID memoryId,
                                                  AgentMemoryRevisionOperation operation,
                                                  AgentMemoryView before, AgentMemoryView after,
                                                  UUID supersededByTargetId, String reason,
                                                  UUID actorId, int prevVersion) {
        return jdbc.queryForObject("""
                INSERT INTO agent_memory_revision(project_id,memory_id,operation,
                    before_content,after_content,before_state,after_state,
                    verification_state_before,verification_state_after,
                    superseded_by_target_id,reason,actor_id,prev_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *
                """, revisionMapper(), projectId, memoryId, operation.name(),
                before.content(), after.content(), before.status(), after.status(),
                before.verificationState(), after.verificationState(),
                supersededByTargetId, reason, actorId, prevVersion);
    }

    /** 修订历史(管理视图追溯):按记忆维度倒序。 */
    public List<AgentMemoryRevisionView> listRevisions(UUID projectId, UUID memoryId) {
        return jdbc.query("""
                SELECT r.* FROM agent_memory_revision r WHERE r.project_id=? AND r.memory_id=?
                ORDER BY r.created_at DESC,r.id DESC
                """, revisionMapper(), projectId, memoryId);
    }

    private static RowMapper<AgentMemoryRevisionView> revisionMapper() {
        return (rs, row) -> new AgentMemoryRevisionView(rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("memory_id", UUID.class),
                rs.getString("operation"), rs.getString("before_content"), rs.getString("after_content"),
                rs.getString("before_state"), rs.getString("after_state"),
                rs.getString("verification_state_before"), rs.getString("verification_state_after"),
                rs.getObject("superseded_by_target_id", UUID.class), rs.getString("reason"),
                rs.getObject("actor_id", UUID.class), rs.getInt("prev_version"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
