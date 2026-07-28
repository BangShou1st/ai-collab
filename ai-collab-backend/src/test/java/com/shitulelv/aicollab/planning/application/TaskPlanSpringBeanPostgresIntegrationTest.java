package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.infrastructure.TaskPlanIssueRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.document.infrastructure.storage.MinioDocumentStorageGateway;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "security.jwt.access-token-minutes=30",
        "storage.minio.endpoint=http://localhost:9000",
        "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret"
})
@Testcontainers(disabledWithoutDocker = true)
class TaskPlanSpringBeanPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired TaskPlanCommandService commands;
    @Autowired TaskPlanConfirmationService confirmations;
    @Autowired TaskPlanPartialRepairService partialRepair;
    @Autowired TaskPlanVersionCommitService commits;
    @Autowired TaskPlanRepository repository;
    @Autowired TaskPlanIssueRepository issues;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MinioDocumentStorageGateway documentStorage;

    @Test
    void productionPlanningBeansUseSpringTransactionsAndLatestFlywaySchema() {
        assertThat(commands).isNotNull();
        assertThat(confirmations).isNotNull();
        assertThat(partialRepair).isNotNull();
        assertThat(repository).isNotNull();
        assertThat(issues).isNotNull();
        assertThat(AopUtils.isAopProxy(commits)).isTrue();
        assertThat(jdbc.queryForObject(
                "select version from flyway_schema_history where success=true order by installed_rank desc limit 1",
                String.class)).isEqualTo("10");
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name='ai_task_plan'",
                Integer.class)).isEqualTo(1);
    }
}
