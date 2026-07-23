package com.shitulelv.aicollab.project;

import com.shitulelv.aicollab.TestcontainersConfiguration;
import com.shitulelv.aicollab.project.api.dto.CreateProjectRequest;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.application.service.ProjectApplicationService;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.mapper.UserMapper;
import com.shitulelv.aicollab.user.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "security.jwt.secret=test-only-secret-0123456789012345",
        "security.jwt.access-token-minutes=30"
})
class ProjectTransactionIntegrationTest {

    @Autowired ProjectApplicationService projects;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean AuditService audit;

    private UUID userId;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM project");
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    @Test
    void auditFailureRollsBackProjectAndOwnerMemberTogether() {
        userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setUsername("rollback_" + userId.toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setDisplayName("Rollback");
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        users.insert(user);
        doThrow(new IllegalStateException("audit failed"))
                .when(audit).write(any(), any(), anyString(), anyString(), any());

        assertThatThrownBy(() -> projects.create(
                new CreateProjectRequest("Rollback Project", "", null, null), userId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM project WHERE created_by = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM project_member WHERE user_id = ?", Integer.class, userId)).isZero();
    }
}
