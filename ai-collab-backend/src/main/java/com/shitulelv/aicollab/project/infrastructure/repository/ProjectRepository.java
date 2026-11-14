package com.shitulelv.aicollab.project.infrastructure.repository;

import com.shitulelv.aicollab.project.application.view.ProjectView;
import com.shitulelv.aicollab.project.domain.model.ProjectStatus;
import com.shitulelv.aicollab.project.domain.model.ProjectType;
import com.shitulelv.aicollab.project.infrastructure.entity.ProjectEntity;
import com.shitulelv.aicollab.project.infrastructure.mapper.ProjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectRepository {
    private final ProjectMapper mapper;
    private final JdbcTemplate jdbc;

    public ProjectRepository(ProjectMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    public void create(ProjectEntity entity) {
        mapper.insert(entity);
    }

    public List<ProjectView> listForUser(UUID userId) {
        return mapper.listForUser(userId);
    }

    public Optional<ProjectView> findForMember(UUID projectId, UUID userId) {
        return mapper.findForMember(projectId, userId);
    }

    public Optional<String> findNameById(UUID projectId) {
        return mapper.findNameById(projectId);
    }

    public Optional<ProjectStatus> findStatus(UUID projectId) {
        return mapper.findStatusById(projectId);
    }

    public boolean lock(UUID projectId) {
        return mapper.lockById(projectId).isPresent();
    }

    public Optional<ProjectStatus> lockStatus(UUID projectId) {
        return mapper.lockStatusById(projectId);
    }

    public int countDocuments(UUID projectId) {
        return mapper.countDocuments(projectId);
    }

    /**
     * 整项目删除是唯一允许释放“已确认规划不可删除”触发器保护的应用路径。
     * 调用方必须已校验 Owner、锁定项目且确认项目没有文档；本方法先锁定项目全部规划行，
     * 再把 CONFIRMED 显式推进到可删除终态，避免并发确认在删除窗口内重新制造触发器冲突。
     */
    public List<UUID> cancelConfirmedPlansForProjectDeletion(UUID projectId) {
        jdbc.queryForList(
                "SELECT id FROM ai_task_plan WHERE project_id=? ORDER BY id FOR UPDATE",
                UUID.class, projectId);
        return jdbc.query("""
                UPDATE ai_task_plan
                SET status='CANCELED', generation_seq=generation_seq+1,
                    active_attempt_id=NULL, canceled_at=now(), updated_at=now()
                WHERE project_id=? AND status='CONFIRMED'
                RETURNING id
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                projectId);
    }

    public boolean updateWithVersion(
            UUID projectId,
            String name,
            String description,
            ProjectType type,
            LocalDate startDate,
            LocalDate dueDate,
            ProjectStatus status,
            int version) {
        return mapper.updateWithVersion(projectId, name, description, type, startDate, dueDate, status, version) == 1;
    }

    public boolean transferOwnership(UUID projectId, UUID currentOwnerId, UUID newOwnerId) {
        return mapper.transferOwnership(projectId, currentOwnerId, newOwnerId) == 1;
    }

    public boolean delete(UUID projectId) {
        return mapper.deleteById(projectId) == 1;
    }
}
