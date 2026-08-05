package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AgentEventRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static AgentEventRepository events;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        events = new AgentEventRepository(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM agent_session");
    }

    @Test
    void appendAllocatesStrictSequenceAndReplayIsProjectScoped() {
        Fixture fixture = fixture();
        UUID otherProject = project(fixture.user());

        var first = events.append(fixture.project(), fixture.run(), AgentEventType.RUN_CREATED,
                new ObjectMapper().createObjectNode().put("status", "QUEUED"));
        var second = events.append(fixture.project(), fixture.run(), AgentEventType.PLAN_CREATED,
                new ObjectMapper().createObjectNode().put("planVersion", 1));

        assertThat(first.sequence()).isEqualTo(1);
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(events.list(fixture.project(), fixture.run(), 1, 100))
                .extracting(event -> event.sequence()).containsExactly(2L);
        assertThat(events.list(otherProject, fixture.run(), 0, 100)).isEmpty();
    }

    private static Fixture fixture() {
        UUID user = user();
        UUID project = project(user);
        UUID session = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_session(id,project_id,creator_id,title) VALUES (?,?,?,?)",
                session, project, user, "events");
        jdbc.update("""
                INSERT INTO agent_run(id,session_id,project_id,requester_id,goal,status)
                VALUES (?,?,?,?,?,'QUEUED')
                """, run, session, project, user, "events");
        return new Fixture(user, project, run);
    }

    private static UUID user() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "event-" + user.toString().substring(0, 8), "test-only-hash", "Event User");
        return user;
    }

    private static UUID project(UUID user) {
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "Event project", user, user);
        return project;
    }

    private record Fixture(UUID user, UUID project, UUID run) {}
}
