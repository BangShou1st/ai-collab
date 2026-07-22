package com.shitulelv.aicollab.auth.refresh.mapper;

import com.shitulelv.aicollab.TestcontainersConfiguration;
import com.shitulelv.aicollab.auth.refresh.entity.RefreshTokenEntity;
import com.shitulelv.aicollab.auth.refresh.mapper.RefreshTokenMapper;
import com.shitulelv.aicollab.auth.refresh.model.RefreshTokenRevokeReason;
import com.shitulelv.aicollab.auth.refresh.service.RefreshTokenRepositoryService;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.mapper.UserMapper;
import com.shitulelv.aicollab.user.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refresh Token 持久化层必须在真实 PostgreSQL 上验证，因为 uuid、timestamptz 和行锁行为都不是内存数据库可以替代的。
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "security.jwt.secret=test-only-secret-0123456789012345",
        "security.jwt.access-token-minutes=30"
})
class RefreshTokenMapperIntegrationTest {

    private static final OffsetDateTime FIXED_TIME = OffsetDateTime.of(2026, 7, 21, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private RefreshTokenMapper refreshTokenMapper;

    @Autowired
    private RefreshTokenRepositoryService refreshTokenRepositoryService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void removeTestData() {
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    @Test
    void mapsUuidOffsetDateTimeAndRevokeReasonWithoutPersistingRawToken() {
        UUID userId = createUser();
        UUID tokenId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID replacementId = UUID.randomUUID();
        RefreshTokenEntity token = token(tokenId, userId, sessionId, "a".repeat(64));
        RefreshTokenEntity replacement = token(replacementId, userId, sessionId, "9".repeat(64));
        token.setRevokedAt(FIXED_TIME.plusHours(1));
        token.setRevokeReason(RefreshTokenRevokeReason.ROTATED);
        token.setReplacedByTokenId(replacementId);

        refreshTokenRepositoryService.create(replacement);
        refreshTokenRepositoryService.create(token);
        RefreshTokenEntity reloaded = refreshTokenMapper.selectById(tokenId);

        assertThat(reloaded.getId()).isEqualTo(tokenId);
        assertThat(reloaded.getUserId()).isEqualTo(userId);
        assertThat(reloaded.getSessionId()).isEqualTo(sessionId);
        assertThat(reloaded.getExpiresAt()).isEqualTo(FIXED_TIME.plusDays(14));
        assertThat(reloaded.getSessionExpiresAt()).isEqualTo(FIXED_TIME.plusDays(30));
        assertThat(reloaded.getRevokedAt()).isEqualTo(FIXED_TIME.plusHours(1));
        assertThat(reloaded.getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.ROTATED);
        assertThat(reloaded.getReplacedByTokenId()).isEqualTo(replacementId);
        assertThat(Arrays.stream(RefreshTokenEntity.class.getDeclaredFields())
                .map(Field::getName))
                .doesNotContain("token", "rawToken", "refreshToken", "refreshTokenValue");
    }

    @Test
    void findsInsertedTokenByHashThroughRepositoryFacade() {
        UUID userId = createUser();
        UUID tokenId = UUID.randomUUID();
        String tokenHash = "b".repeat(64);
        refreshTokenRepositoryService.create(token(tokenId, userId, UUID.randomUUID(), tokenHash));

        var found = refreshTokenRepositoryService.findByTokenHash(tokenHash);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getId()).isEqualTo(tokenId);
    }

    @Test
    void selectForUpdatePreventsAnotherPostgresTransactionFromLockingTheSameRow() {
        UUID userId = createUser();
        UUID tokenId = UUID.randomUUID();
        String tokenHash = "c".repeat(64);
        refreshTokenRepositoryService.create(token(tokenId, userId, UUID.randomUUID(), tokenHash));

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(refreshTokenRepositoryService.findByTokenHashForUpdate(tokenHash)).isPresent();
            assertThat(canAcquireRowLockFromAnotherConnection(tokenId)).isFalse();
        });
    }

    @Test
    void sessionAdvisoryLockSerializesDifferentTokenRowsFromTheSameSession() {
        UUID userId = createUser();
        UUID sessionId = UUID.randomUUID();
        String firstTokenHash = "1".repeat(64);
        String secondTokenHash = "2".repeat(64);
        refreshTokenRepositoryService.create(token(UUID.randomUUID(), userId, sessionId, firstTokenHash));
        refreshTokenRepositoryService.create(token(UUID.randomUUID(), userId, sessionId, secondTokenHash));

        transactionTemplate.executeWithoutResult(status -> {
            RefreshTokenEntity first = refreshTokenRepositoryService.findByTokenHash(firstTokenHash).orElseThrow();
            refreshTokenRepositoryService.lockSession(first.getSessionId());

            assertThat(canAcquireSessionLockFromAnotherConnection(secondTokenHash)).isFalse();
        });
    }

    @Test
    void marksAnActiveTokenAsRotatedAndLinksItsReplacement() {
        UUID userId = createUser();
        UUID sessionId = UUID.randomUUID();
        RefreshTokenEntity oldToken = token(UUID.randomUUID(), userId, sessionId, "7".repeat(64));
        RefreshTokenEntity replacement = token(UUID.randomUUID(), userId, sessionId, "8".repeat(64));
        refreshTokenRepositoryService.create(oldToken);
        refreshTokenRepositoryService.create(replacement);

        int updatedCount = refreshTokenRepositoryService.markRotated(
                oldToken.getId(), replacement.getId(), FIXED_TIME.plusMinutes(5));

        assertThat(updatedCount).isEqualTo(1);
        RefreshTokenEntity reloaded = refreshTokenMapper.selectById(oldToken.getId());
        assertThat(reloaded.getRevokedAt()).isEqualTo(FIXED_TIME.plusMinutes(5));
        assertThat(reloaded.getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.ROTATED);
        assertThat(reloaded.getReplacedByTokenId()).isEqualTo(replacement.getId());

        int secondUpdate = refreshTokenRepositoryService.markRotated(
                oldToken.getId(), replacement.getId(), FIXED_TIME.plusMinutes(10));
        RefreshTokenEntity afterSecondCall = refreshTokenMapper.selectById(oldToken.getId());
        assertThat(secondUpdate).isZero();
        assertThat(afterSecondCall.getRevokedAt()).isEqualTo(FIXED_TIME.plusMinutes(5));
        assertThat(afterSecondCall.getReplacedByTokenId()).isEqualTo(replacement.getId());
    }

    @Test
    void revokesOnlyStillActiveTokensInTheSpecifiedSession() {
        UUID userId = createUser();
        UUID targetSessionId = UUID.randomUUID();
        UUID otherSessionId = UUID.randomUUID();
        RefreshTokenEntity active = token(UUID.randomUUID(), userId, targetSessionId, "d".repeat(64));
        RefreshTokenEntity alreadyRevoked = token(UUID.randomUUID(), userId, targetSessionId, "e".repeat(64));
        alreadyRevoked.setRevokedAt(FIXED_TIME.minusMinutes(1));
        alreadyRevoked.setRevokeReason(RefreshTokenRevokeReason.ROTATED);
        RefreshTokenEntity otherSession = token(UUID.randomUUID(), userId, otherSessionId, "f".repeat(64));
        refreshTokenRepositoryService.create(active);
        refreshTokenRepositoryService.create(alreadyRevoked);
        refreshTokenRepositoryService.create(otherSession);

        int revokedCount = refreshTokenRepositoryService.revokeActiveTokensBySessionId(
                targetSessionId, RefreshTokenRevokeReason.LOGOUT, FIXED_TIME);

        assertThat(revokedCount).isEqualTo(1);
        RefreshTokenEntity reloadedActive = refreshTokenMapper.selectById(active.getId());
        RefreshTokenEntity reloadedAlreadyRevoked = refreshTokenMapper.selectById(alreadyRevoked.getId());
        RefreshTokenEntity reloadedOtherSession = refreshTokenMapper.selectById(otherSession.getId());
        assertThat(reloadedActive.getRevokedAt()).isEqualTo(FIXED_TIME);
        assertThat(reloadedActive.getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.LOGOUT);
        assertThat(reloadedAlreadyRevoked.getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.ROTATED);
        assertThat(reloadedOtherSession.getRevokedAt()).isNull();
    }

    private UUID createUser() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setUsername("refresh_repo_" + userId.toString().substring(0, 8));
        user.setPasswordHash("test-only-password-hash");
        user.setDisplayName("Refresh Repository Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        userMapper.insert(user);
        createdUserIds.add(userId);
        return userId;
    }

    private RefreshTokenEntity token(UUID tokenId, UUID userId, UUID sessionId, String tokenHash) {
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.setId(tokenId);
        token.setUserId(userId);
        token.setSessionId(sessionId);
        token.setTokenHash(tokenHash);
        token.setExpiresAt(FIXED_TIME.plusDays(14));
        token.setSessionExpiresAt(FIXED_TIME.plusDays(30));
        token.setCreatedAt(FIXED_TIME);
        return token;
    }

    private boolean canAcquireRowLockFromAnotherConnection(UUID tokenId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '100ms'");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM refresh_token WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, tokenId);
                statement.executeQuery();
                return true;
            } catch (SQLException exception) {
                assertThat(exception.getSQLState()).isEqualTo("55P03");
                return false;
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("无法验证 PostgreSQL 行锁语义", exception);
        }
    }

    private boolean canAcquireSessionLockFromAnotherConnection(String tokenHash) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '100ms'");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT pg_advisory_xact_lock(hashtextextended(session_id::text, 0))
                    FROM refresh_token
                    WHERE token_hash = ?
                    """)) {
                statement.setString(1, tokenHash);
                statement.executeQuery();
                return true;
            } catch (SQLException exception) {
                assertThat(exception.getSQLState()).isEqualTo("55P03");
                return false;
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("无法验证 PostgreSQL Session 事务锁语义", exception);
        }
    }
}
