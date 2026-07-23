package com.shitulelv.aicollab.project;

import com.shitulelv.aicollab.TestcontainersConfiguration;
import com.shitulelv.aicollab.auth.model.AuthenticationResult;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.api.dto.AcceptInvitationRequest;
import com.shitulelv.aicollab.project.api.dto.CreateInvitationRequest;
import com.shitulelv.aicollab.project.api.dto.CreateProjectRequest;
import com.shitulelv.aicollab.project.application.service.InvitationApplicationService;
import com.shitulelv.aicollab.project.application.service.ProjectApplicationService;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.mapper.UserMapper;
import com.shitulelv.aicollab.user.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "security.jwt.secret=test-only-secret-0123456789012345",
        "security.jwt.access-token-minutes=30"
})
class InvitationConcurrencyIntegrationTest {

    @Autowired InvitationApplicationService invitations;
    @Autowired ProjectApplicationService projects;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM project");
        jdbcTemplate.update("DELETE FROM refresh_token");
        jdbcTemplate.update("DELETE FROM app_user");
    }

    @Test
    void concurrentAcceptanceLocksInvitationAndSucceedsOnlyOnce() throws Exception {
        UserEntity owner = createOwner();
        UUID projectId = projects.create(
                new CreateProjectRequest("Concurrent Invite", "", null, null), owner.getId()).id();
        String code = invitations.create(
                projectId,
                new CreateInvitationRequest(ProjectRole.MEMBER, null, 24),
                owner.getId()).code();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> acceptAfterBarrier(
                    barrier, code, new AcceptInvitationRequest("race_one", "race-password", "Race One", null)));
            Future<Object> second = executor.submit(() -> acceptAfterBarrier(
                    barrier, code, new AcceptInvitationRequest("race_two", "race-password", "Race Two", null)));
            List<Object> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(AuthenticationResult.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(BusinessException.class::isInstance)
                    .singleElement()
                    .satisfies(result -> assertThat(((BusinessException) result).getErrorCode())
                            .isEqualTo(ErrorCode.INVITATION_ALREADY_USED));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM project_member WHERE project_id = ? AND role = 'MEMBER'",
                    Integer.class, projectId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Object acceptAfterBarrier(
            CyclicBarrier barrier,
            String code,
            AcceptInvitationRequest request) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return invitations.accept(code, request);
        } catch (BusinessException exception) {
            return exception;
        } catch (Exception exception) {
            throw new IllegalStateException("并发邀请测试线程失败", exception);
        }
    }

    private UserEntity createOwner() {
        UserEntity owner = new UserEntity();
        owner.setId(UUID.randomUUID());
        owner.setUsername("invite_owner_" + owner.getId().toString().substring(0, 8));
        owner.setPasswordHash(passwordEncoder.encode("owner-password"));
        owner.setDisplayName("Invite Owner");
        owner.setStatus(UserStatus.ACTIVE);
        owner.setTokenVersion(0);
        users.insert(owner);
        return owner;
    }
}
