package com.shitulelv.aicollab.notification;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.persistence.PostgresUuidTypeHandler;
import com.shitulelv.aicollab.notification.application.service.NotificationApplicationService;
import com.shitulelv.aicollab.notification.infrastructure.mapper.NotificationMapper;
import com.shitulelv.aicollab.notification.infrastructure.repository.NotificationRepository;
import com.shitulelv.aicollab.project.domain.policy.DatabaseProjectAccessGuard;
import com.shitulelv.aicollab.project.infrastructure.mapper.ProjectMemberMapper;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class NotificationNavigationPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static SqlSessionFactory factory;

    @BeforeAll static void setup() {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
        configuration.getTypeHandlerRegistry().register(UUID.class, PostgresUuidTypeHandler.class);
        configuration.addMapper(NotificationMapper.class);
        configuration.addMapper(ProjectMemberMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }
    record Fixture(UUID user, UUID other, UUID project) {}
    Fixture fixture() {
        UUID user = UUID.randomUUID(), other = UUID.randomUUID(), project = UUID.randomUUID();
        for (UUID id : new UUID[]{user, other}) jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,'test-hash','synthetic')", id, id.toString());
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'synthetic notification',?,?)", project, other, other);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'MEMBER')", project, user);
        return new Fixture(user, other, project);
    }
    UUID notification(Fixture f) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notification(id,project_id,user_id,type,title,entity_type,entity_id) VALUES (?,?,?,'TASK_ASSIGNED','synthetic','TASK',?)", id, f.project(), f.user(), UUID.randomUUID());
        return id;
    }
    NotificationApplicationService service(SqlSession session) {
        return new NotificationApplicationService(new NotificationRepository(session.getMapper(NotificationMapper.class)),
                new DatabaseProjectAccessGuard(new ProjectMemberRepository(session.getMapper(ProjectMemberMapper.class))),
                new com.shitulelv.aicollab.document.infrastructure.repository.DocumentVisibilityRepository(jdbc));
    }
    @Test void readPersistsInFreshUserAndProjectQueriesAndCannotBeWrittenByAnotherUser() {
        var f = fixture(); UUID id = notification(f);
        try (var session = factory.openSession(true)) {
            var mapper = session.getMapper(NotificationMapper.class);
            assertThat(mapper.markAsRead(f.other(), id)).isZero();
            assertThat(mapper.countUnread(f.user())).isOne();
            assertThat(mapper.markAsRead(f.user(), id)).isOne();
        }
        try (var session = factory.openSession(true)) {
            var mapper = session.getMapper(NotificationMapper.class);
            assertThat(mapper.listByUser(f.user(), 20, 0).getFirst().isRead()).isTrue();
            assertThat(mapper.listByProject(f.user(), f.project(), 20, 0).getFirst().isRead()).isTrue();
            UUID taskId = mapper.listByUser(f.user(), 20, 0).getFirst().getEntityId();
            assertThat(mapper.findByEntity(f.user(), "TASK", taskId).orElseThrow().isRead()).isTrue();
            assertThat(mapper.countUnread(f.user())).isZero();
            assertThat(mapper.listByUser(f.other(), 20, 0)).isEmpty();
        }
    }
    @Test void paginationAndReadAllRemainScopedAndPersistOnEveryPage() {
        var f = fixture(); for (int i = 0; i < 22; ++i) notification(f);
        try (var session = factory.openSession(true)) {
            var mapper = session.getMapper(NotificationMapper.class);
            assertThat(mapper.listByUser(f.user(), 20, 0)).hasSize(20);
            assertThat(mapper.listByUser(f.user(), 20, 20)).hasSize(2);
            assertThat(mapper.markAllAsRead(f.other())).isZero();
            assertThat(mapper.markAllAsRead(f.user())).isEqualTo(22);
        }
        try (var session = factory.openSession(true)) {
            var service = service(session);
            assertThat(service.list(f.user(), 0, 20)).allMatch(n -> n.read());
            assertThat(service.list(f.user(), 1, 20)).hasSize(2).allMatch(n -> n.read());
            assertThat(service.countUnread(f.user())).isZero();
        }
    }
    @Test void personalHistorySurvivesMembershipRemovalButProjectScopeStillRequiresMembership() {
        var f = fixture(); UUID id = notification(f);
        jdbc.update("DELETE FROM project_member WHERE project_id=? AND user_id=?", f.project(), f.user());
        try (var session = factory.openSession(true)) {
            var service = service(session);
            assertThat(service.list(f.user(), 0, 20)).hasSize(1);
            service.markAsRead(f.user(), id);
            assertThat(service.list(f.user(), 0, 20).getFirst().read()).isTrue();
            assertThatThrownBy(() -> service.listByProject(f.project(), f.user(), 0, 20)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.markAsRead(f.other(), id)).isInstanceOf(BusinessException.class);
        }
    }
}
