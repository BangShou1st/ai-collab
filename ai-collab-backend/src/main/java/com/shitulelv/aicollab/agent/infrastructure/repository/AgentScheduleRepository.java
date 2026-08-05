package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentScheduleView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentScheduleRepository {
    private final JdbcTemplate jdbc;
    private final AgentRepository runs;

    public AgentScheduleRepository(JdbcTemplate jdbc, AgentRepository runs) {
        this.jdbc = jdbc;
        this.runs = runs;
    }

    public AgentScheduleView create(
            UUID projectId, UUID creatorId, UUID sessionId, String name, String goal,
            String skillCode, String frequency, String zone, LocalTime time, Integer weeklyDay,
            OffsetDateTime nextFireAt) {
        return jdbc.queryForObject("""
                INSERT INTO agent_schedule(
                  id,project_id,creator_id,session_id,name,goal,skill_code,frequency,time_zone,
                  local_time,weekly_day,next_fire_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *
                """, mapper(), UUID.randomUUID(), projectId, creatorId, sessionId,
                name, goal, skillCode, frequency, zone, time, weeklyDay, nextFireAt);
    }

    public AgentScheduleView create(
            UUID projectId, UUID creatorId, UUID sessionId, String name, String goal,
            String frequency, String zone, LocalTime time, Integer weeklyDay,
            OffsetDateTime nextFireAt) {
        return create(projectId, creatorId, sessionId, name, goal, null,
                frequency, zone, time, weeklyDay, nextFireAt);
    }

    public List<AgentScheduleView> list(UUID projectId) {
        return jdbc.query("""
                SELECT * FROM agent_schedule WHERE project_id=?
                ORDER BY created_at DESC,id DESC
                """, mapper(), projectId);
    }

    public Optional<AgentScheduleView> find(UUID projectId, UUID id) {
        return jdbc.query("SELECT * FROM agent_schedule WHERE project_id=? AND id=?",
                mapper(), projectId, id).stream().findFirst();
    }

    public boolean setEnabled(UUID projectId, UUID id, int version, boolean enabled) {
        return jdbc.update("""
                UPDATE agent_schedule SET enabled=?,version=version+1,updated_at=now()
                WHERE project_id=? AND id=? AND version=?
                """, enabled, projectId, id, version) == 1;
    }

    public List<AgentScheduleView> due(OffsetDateTime now, int limit) {
        return jdbc.query("""
                SELECT * FROM agent_schedule
                WHERE enabled=true AND next_fire_at<=?
                ORDER BY next_fire_at,id LIMIT ?
                """, mapper(), now, limit);
    }

    @Transactional
    public Optional<AgentRunView> fire(AgentScheduleView schedule, OffsetDateTime nextFireAt) {
        int claimed = jdbc.update("""
                UPDATE agent_schedule SET next_fire_at=?,version=version+1,updated_at=now()
                WHERE id=? AND project_id=? AND enabled=true
                  AND version=? AND next_fire_at=?
                """, nextFireAt, schedule.id(), schedule.projectId(),
                schedule.version(), schedule.nextFireAt());
        if (claimed != 1) return Optional.empty();
        AgentRunView run = runs.createRun(
                schedule.projectId(), schedule.sessionId(), schedule.creatorId(),
                schedule.goal(), true, schedule.skillCode(), null);
        int inserted = jdbc.update("""
                INSERT INTO agent_schedule_fire(id,schedule_id,scheduled_for,run_id)
                VALUES (?,?,?,?) ON CONFLICT (schedule_id,scheduled_for) DO NOTHING
                """, UUID.randomUUID(), schedule.id(), schedule.nextFireAt(), run.id());
        if (inserted == 0) {
            throw new IllegalStateException("定时运行触发记录冲突");
        }
        jdbc.update("""
                UPDATE agent_schedule SET last_run_id=?,last_status='QUEUED',updated_at=now()
                WHERE id=? AND project_id=? AND version=?
                """, run.id(), schedule.id(), schedule.projectId(), schedule.version() + 1);
        return Optional.of(run);
    }

    private RowMapper<AgentScheduleView> mapper() {
        return (rs, row) -> new AgentScheduleView(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("creator_id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getString("name"), rs.getString("goal"), rs.getString("skill_code"), rs.getString("frequency"),
                rs.getString("time_zone"), rs.getObject("local_time", LocalTime.class),
                integer(rs, "weekly_day"), rs.getBoolean("enabled"),
                rs.getObject("next_fire_at", OffsetDateTime.class),
                rs.getObject("last_run_id", UUID.class), rs.getString("last_status"),
                rs.getInt("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static Integer integer(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
