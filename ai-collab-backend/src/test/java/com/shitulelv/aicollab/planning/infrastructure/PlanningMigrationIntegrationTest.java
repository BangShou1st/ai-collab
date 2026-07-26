package com.shitulelv.aicollab.planning.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.application.TaskPlanConfirmationService;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftValidator;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@Testcontainers(disabledWithoutDocker = true)
class PlanningMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void v5EnforcesImmutableVersionAndConfirmationKeysAndDeletesFailedConfirmation() {
        UUID user = UUID.randomUUID(), project = UUID.randomUUID(), plan = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)
                """, user, "p8-" + user.toString().substring(0, 8), "test-only-hash", "Phase 08");
        jdbc.update("""
                INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)
                """, project, "Phase 08", user, user);
        jdbc.update("""
                INSERT INTO ai_task_plan(id,project_id,title,goal,plan_start_date,plan_due_date,
                  max_task_count,status,created_by) VALUES (?,?,?,?,DATE '2026-08-01',DATE '2026-08-31',10,'READY',?)
                """, plan, project, "Plan", "Goal", user);

        UUID version = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_task_plan_version(id,plan_id,version_no,source_type,generation_seq,created_by)
                VALUES (?,?,1,'MANUAL_EDIT',1,?)
                """, version, plan, user);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ai_task_plan_version(id,plan_id,version_no,source_type,generation_seq,created_by)
                VALUES (?,?,1,'RESTORED',1,?)
                """, UUID.randomUUID(), plan, user)).isInstanceOf(Exception.class);

        jdbc.update("""
                INSERT INTO ai_task_plan_confirmation(project_id,plan_id,version_id,idempotency_key,
                  request_hash,status,created_by) VALUES (?,?,?,?,?,'FAILED',?)
                """, project, plan, version, UUID.randomUUID(), "0".repeat(64), user);
        jdbc.update("DELETE FROM ai_task_plan WHERE id=?", plan);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_task_plan_confirmation WHERE plan_id=?", Integer.class, plan)).isZero();
    }

    @Test
    void confirmedPlanCannotBeDeletedAndFormalSourceKeysStayUnique() {
        UUID user = UUID.randomUUID(), project = UUID.randomUUID(), plan = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "p8-" + user.toString().substring(0, 8), "test-only-hash", "Phase 08");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "Confirmed Phase 08", user, user);
        jdbc.update("""
                INSERT INTO ai_task_plan(id,project_id,title,goal,plan_start_date,plan_due_date,
                  max_task_count,status,created_by) VALUES (?,?,?,?,DATE '2026-08-01',DATE '2026-08-31',10,'CONFIRMED',?)
                """, plan, project, "Plan", "Goal", user);
        UUID version = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_task_plan_version(id,plan_id,version_no,source_type,generation_seq,created_by)
                VALUES (?,?,1,'AI_COMPLETE',1,?)
                """, version, plan, user);
        UUID milestone = UUID.randomUUID(), task = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO milestone(id,project_id,name,created_by,source_plan_id,source_plan_version_id,source_plan_milestone_key)
                VALUES (?,?,?,?,?,?,?)
                """, milestone, project, "Generated", user, plan, version, "m1");
        jdbc.update("""
                INSERT INTO project_task(id,project_id,milestone_id,title,created_by,source_plan_id,source_plan_version_id,source_plan_task_key)
                VALUES (?,?,?,?,?,?,?,?)
                """, task, project, milestone, "Generated", user, plan, version, "t1");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO milestone(id,project_id,name,created_by,source_plan_id,source_plan_version_id,source_plan_milestone_key)
                VALUES (?,?,?,?,?,?,?)
                """, UUID.randomUUID(), project, "Duplicate", user, plan, version, "m1"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ai_task_plan WHERE id=?", plan))
                .isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM milestone WHERE id=?", Integer.class, milestone)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_task WHERE id=?", Integer.class, task)).isOne();
    }

    @Test
    void confirmationCreatesOnceAndReplaysSameOrNewIdempotencyKey() {
        UUID user = UUID.randomUUID(), project = UUID.randomUUID(), plan = UUID.randomUUID(), version = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "p8-" + user.toString().substring(0, 8), "test-only-hash", "Phase 08");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by,start_date,due_date) VALUES (?,?,?,?,?,?)",
                project, "Confirmation Phase 08", user, user,
                java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 31));
        jdbc.update("""
                INSERT INTO ai_task_plan(id,project_id,title,goal,plan_start_date,plan_due_date,max_task_count,
                  status,created_by)
                VALUES (?,?,?,?,DATE '2026-08-01',DATE '2026-08-31',10,'READY',?)
                """, plan, project, "Plan", "Goal", user);
        jdbc.update("""
                INSERT INTO ai_task_plan_version(id,plan_id,version_no,source_type,generation_seq,summary,
                  milestones_json,tasks_json,created_by)
                VALUES (?,?,1,'AI_COMPLETE',1,'Ready',
                  '[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0,"sourceRefs":[]}]',
                  '[{"tempKey":"t1","milestoneTempKey":"m1","title":"T","objective":"O","description":"D",
                    "priority":"MEDIUM","estimatedHours":1,"startDate":null,"dueDate":null,
                    "suggestedAssigneeId":null,"assigneeId":null,"dependencyTempKeys":[],"sourceRefs":[],"sortOrder":0}]',?)
                """, version, plan, user);
        jdbc.update("UPDATE ai_task_plan SET latest_version_no=1,latest_version_id=? WHERE id=?", version, plan);

        var repository = new TaskPlanRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        var service = new TaskPlanConfirmationService(adminGuard(), repository, jdbc,
                new DataSourceTransactionManager(dataSource), new TaskPlanDraftValidator(), mock(AuditService.class));
        UUID firstKey = UUID.randomUUID();

        Map<String, Object> first = service.confirm(project, plan, version, firstKey, user);
        Map<String, Object> sameKey = service.confirm(project, plan, version, firstKey, user);
        Map<String, Object> newKey = service.confirm(project, plan, version, UUID.randomUUID(), user);

        assertThat(first.get("status")).isEqualTo("SUCCESS");
        assertThat(sameKey.get("confirmationId")).isEqualTo(first.get("confirmationId"));
        assertThat(newKey.get("confirmationId")).isEqualTo(first.get("confirmationId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM milestone WHERE source_plan_id=?", Integer.class, plan)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_task WHERE source_plan_id=?", Integer.class, plan)).isOne();
    }

    @Test
    void confirmationFailureRollsBackFormalRowsAndReturnsPlanToReady() {
        UUID user = UUID.randomUUID(), project = UUID.randomUUID(), plan = UUID.randomUUID(), version = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "p8-" + user.toString().substring(0, 8), "test-only-hash", "Phase 08");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by,start_date,due_date) VALUES (?,?,?,?,?,?)",
                project, "Rollback Phase 08", user, user,
                java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 31));
        jdbc.update("""
                INSERT INTO ai_task_plan(id,project_id,title,goal,plan_start_date,plan_due_date,max_task_count,status,created_by)
                VALUES (?,?,?,?,DATE '2026-08-01',DATE '2026-08-31',10,'READY',?)
                """, plan, project, "Plan", "Goal", user);
        jdbc.update("""
                INSERT INTO ai_task_plan_version(id,plan_id,version_no,source_type,generation_seq,summary,
                  milestones_json,tasks_json,created_by)
                VALUES (?,?,1,'AI_COMPLETE',1,'Ready',
                  '[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0,"sourceRefs":[]}]',
                  '[{"tempKey":"t1","milestoneTempKey":"m1","title":"T","objective":"O","description":"D",
                    "priority":"MEDIUM","estimatedHours":1,"startDate":null,"dueDate":null,
                    "suggestedAssigneeId":null,"assigneeId":null,"dependencyTempKeys":[],"sourceRefs":[],"sortOrder":0}]',?)
                """, version, plan, user);
        jdbc.update("UPDATE ai_task_plan SET latest_version_no=1,latest_version_id=? WHERE id=?", version, plan);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION phase08_fail_task_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected confirmation failure'; END $$;
                CREATE TRIGGER phase08_fail_task_insert BEFORE INSERT ON project_task
                FOR EACH ROW EXECUTE FUNCTION phase08_fail_task_insert()
                """);
        try {
            var repository = new TaskPlanRepository(jdbc, new ObjectMapper().findAndRegisterModules());
            AuditService failingAudit = mock(AuditService.class);
            doThrow(new IllegalStateException("injected audit failure")).when(failingAudit)
                    .write(any(), any(), anyString(), anyString(), any());
            var service = new TaskPlanConfirmationService(adminGuard(), repository, jdbc,
                    new DataSourceTransactionManager(dataSource), new TaskPlanDraftValidator(), failingAudit);

            assertThatThrownBy(() -> service.confirm(project, plan, version, UUID.randomUUID(), user))
                    .isInstanceOf(Exception.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM milestone WHERE source_plan_id=?", Integer.class, plan)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM project_task WHERE source_plan_id=?", Integer.class, plan)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM ai_task_plan WHERE id=?", String.class, plan)).isEqualTo("READY");
            assertThat(jdbc.queryForObject("SELECT status FROM ai_task_plan_confirmation WHERE plan_id=?",
                    String.class, plan)).isEqualTo("FAILED");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS phase08_fail_task_insert ON project_task");
            jdbc.execute("DROP FUNCTION IF EXISTS phase08_fail_task_insert()");
        }
    }

    private static ProjectAccessGuard adminGuard() {
        return new ProjectAccessGuard() {
            @Override public ProjectRole requireMember(UUID projectId, UUID userId) { return ProjectRole.OWNER; }
            @Override public void requireAdmin(UUID projectId, UUID userId) {}
            @Override public void requireOwner(UUID projectId, UUID userId) {}
        };
    }
}
