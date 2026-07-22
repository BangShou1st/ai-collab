package com.shitulelv.aicollab.auth;

import com.shitulelv.aicollab.TestcontainersConfiguration;
import com.shitulelv.aicollab.auth.model.IssuedRefreshToken;
import com.shitulelv.aicollab.auth.refresh.entity.RefreshTokenEntity;
import com.shitulelv.aicollab.auth.refresh.service.RefreshTokenRepositoryService;
import com.shitulelv.aicollab.auth.service.AccessTokenService;
import com.shitulelv.aicollab.auth.service.AuthService;
import com.shitulelv.aicollab.auth.service.RefreshTokenHashService;
import com.shitulelv.aicollab.auth.service.RefreshTokenService;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.mapper.UserMapper;
import com.shitulelv.aicollab.user.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.when;

/** Verifies refresh rotation atomicity through the real Spring proxy and PostgreSQL transaction manager. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "security.jwt.secret=test-only-secret-0123456789012345",
        "security.jwt.access-token-minutes=30"
})
class AuthRefreshTransactionIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenHashService refreshTokenHashService;

    @Autowired
    private RefreshTokenRepositoryService refreshTokenRepositoryService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AccessTokenService accessTokenService;

    private UserEntity user;

    @BeforeEach
    void createUser() {
        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("refresh_transaction_" + user.getId().toString().substring(0, 8));
        user.setPasswordHash("test-only-password-hash");
        user.setDisplayName("Refresh Transaction Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        userMapper.insert(user);
    }

    @AfterEach
    void removeTestData() {
        jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", user.getId());
        userMapper.deleteById(user.getId());
    }

    @Test
    void rollsBackTheEntireRotationWhenAccessTokenSigningFails() {
        IssuedRefreshToken oldCredential = refreshTokenService.createSession(user);
        RefreshTokenEntity oldToken = locate(oldCredential);
        when(accessTokenService.createAccessToken(any(UserEntity.class)))
                .thenThrow(new RuntimeException("simulated access token failure"));

        assertThatThrownBy(() -> authService.refresh(oldCredential.value()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated access token failure");

        RefreshTokenEntity persistedOldToken = refreshTokenRepositoryService
                .findByTokenHash(refreshTokenHashService.hash(oldCredential.value()))
                .orElseThrow();
        assertThat(persistedOldToken.getId()).isEqualTo(oldToken.getId());
        assertThat(persistedOldToken.getRevokedAt()).isNull();
        assertThat(persistedOldToken.getRevokeReason()).isNull();
        assertThat(persistedOldToken.getReplacedByTokenId()).isNull();
        assertThat(refreshTokenCount()).isEqualTo(1);
    }

    private RefreshTokenEntity locate(IssuedRefreshToken credential) {
        return refreshTokenRepositoryService
                .findByTokenHash(refreshTokenHashService.hash(credential.value()))
                .orElseThrow();
    }

    private int refreshTokenCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ?",
                Integer.class,
                user.getId());
        return count == null ? 0 : count;
    }
}
