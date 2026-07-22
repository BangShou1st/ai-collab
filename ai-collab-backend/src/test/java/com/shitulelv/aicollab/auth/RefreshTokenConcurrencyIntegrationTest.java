package com.shitulelv.aicollab.auth;

import com.shitulelv.aicollab.TestcontainersConfiguration;
import com.shitulelv.aicollab.auth.model.IssuedRefreshToken;
import com.shitulelv.aicollab.auth.model.RefreshResult;
import com.shitulelv.aicollab.auth.refresh.entity.RefreshTokenEntity;
import com.shitulelv.aicollab.auth.refresh.mapper.RefreshTokenMapper;
import com.shitulelv.aicollab.auth.refresh.model.RefreshTokenRevokeReason;
import com.shitulelv.aicollab.auth.refresh.service.RefreshTokenRepositoryService;
import com.shitulelv.aicollab.auth.service.AuthService;
import com.shitulelv.aicollab.auth.service.RefreshTokenHashService;
import com.shitulelv.aicollab.auth.service.RefreshTokenService;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.mapper.UserMapper;
import com.shitulelv.aicollab.user.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * Refresh Token 的事务与锁语义必须在真实 PostgreSQL 连接上验证；
 * 单元测试中的调用顺序无法证明两个独立事务会观察到彼此提交后的状态。
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "security.jwt.secret=test-only-secret-0123456789012345",
        "security.jwt.access-token-minutes=30"
})
class RefreshTokenConcurrencyIntegrationTest {

    private static final long FUTURE_TIMEOUT_SECONDS = 10;

    @Autowired
    private AuthService authService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @MockitoSpyBean
    private RefreshTokenRepositoryService refreshTokenRepositoryService;

    @Autowired
    private RefreshTokenHashService refreshTokenHashService;

    @Autowired
    private RefreshTokenMapper refreshTokenMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID createdUserId;

    @AfterEach
    void removeTestData() {
        if (createdUserId != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", createdUserId);
        }
    }

    @Test
    void concurrentReuseRevokesTheRotatedSessionButLeavesAnotherSessionRotatable() throws Exception {
        UserEntity user = createActiveUser();
        IssuedRefreshToken contestedCredential = refreshTokenService.createSession(user);
        IssuedRefreshToken independentCredential = refreshTokenService.createSession(user);
        RefreshTokenEntity contestedBefore = locate(contestedCredential);
        RefreshTokenEntity independentBefore = locate(independentCredential);
        CyclicBarrier bothInitialLookupsCompleted = new CyclicBarrier(2);
        AtomicInteger initialLookupSequence = new AtomicInteger();
        reset(refreshTokenRepositoryService);
        doAnswer(invocation -> {
            Object located = invocation.callRealMethod();
            // 只拦截两个争用请求的首次无锁定位；独立 session 的后续轮换直接调用真实实现。
            if (initialLookupSequence.getAndIncrement() < 2) {
                bothInitialLookupsCompleted.await(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return located;
        }).when(refreshTokenRepositoryService).findByTokenHash(anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        try {
            Future<Object> first = executor.submit(() -> refreshAfterBarrier(startTogether, contestedCredential));
            Future<Object> second = executor.submit(() -> refreshAfterBarrier(startTogether, contestedCredential));

            List<Object> outcomes = List.of(
                    first.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertThat(outcomes).allMatch(outcome ->
                    outcome instanceof RefreshResult || outcome instanceof BusinessException);
            assertThat(outcomes).filteredOn(RefreshResult.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(BusinessException.class::isInstance)
                    .singleElement()
                    .satisfies(outcome -> assertThat(((BusinessException) outcome).getErrorCode())
                            .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        RefreshTokenEntity rotatedOriginal = refreshTokenMapper.selectById(contestedBefore.getId());
        assertThat(rotatedOriginal.getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.ROTATED);
        assertThat(rotatedOriginal.getReplacedByTokenId()).isNotNull();

        RefreshTokenEntity revokedReplacement = refreshTokenMapper.selectById(rotatedOriginal.getReplacedByTokenId());
        assertThat(revokedReplacement.getId()).isEqualTo(rotatedOriginal.getReplacedByTokenId());
        assertThat(revokedReplacement.getSessionId()).isEqualTo(rotatedOriginal.getSessionId());
        assertThat(revokedReplacement.getUserId()).isEqualTo(rotatedOriginal.getUserId());
        assertThat(revokedReplacement.getReplacedByTokenId()).isNull();
        assertThat(revokedReplacement.getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.REUSE_DETECTED);
        assertThat(revokedReplacement.getRevokedAt()).isNotNull();
        assertThat(credentialCount(contestedBefore.getSessionId())).isEqualTo(2);
        assertThat(activeCredentialCount(contestedBefore.getSessionId())).isZero();

        assertThat(activeCredentialCount(independentBefore.getSessionId())).isEqualTo(1);
        assertThat(authService.refresh(independentCredential.value())).isInstanceOf(RefreshResult.class);
        RefreshTokenEntity rotatedIndependent = refreshTokenMapper.selectById(independentBefore.getId());
        assertThat(rotatedIndependent.getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.ROTATED);
        assertThat(activeCredentialCount(independentBefore.getSessionId())).isEqualTo(1);
    }

    private Object refreshAfterBarrier(CyclicBarrier barrier, IssuedRefreshToken credential) {
        try {
            barrier.await(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return authService.refresh(credential.value());
        } catch (BusinessException exception) {
            return exception;
        } catch (Exception exception) {
            throw new IllegalStateException("并发刷新测试线程执行失败", exception);
        }
    }

    private RefreshTokenEntity locate(IssuedRefreshToken credential) {
        return refreshTokenRepositoryService
                .findByTokenHash(refreshTokenHashService.hash(credential.value()))
                .orElseThrow();
    }

    private int activeCredentialCount(UUID sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE session_id = ? AND revoked_at IS NULL",
                Integer.class,
                sessionId);
        return count == null ? 0 : count;
    }

    private int credentialCount(UUID sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE session_id = ?",
                Integer.class,
                sessionId);
        return count == null ? 0 : count;
    }

    private UserEntity createActiveUser() {
        createdUserId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(createdUserId);
        user.setUsername("refresh_concurrency_" + createdUserId.toString().substring(0, 8));
        user.setPasswordHash("test-only-password-hash");
        user.setDisplayName("Refresh Concurrency Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        userMapper.insert(user);
        return user;
    }
}
