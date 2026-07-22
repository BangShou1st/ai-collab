package com.shitulelv.aicollab.auth;

import com.shitulelv.aicollab.TestcontainersConfiguration;
import com.shitulelv.aicollab.auth.refresh.entity.RefreshTokenEntity;
import com.shitulelv.aicollab.auth.refresh.model.RefreshTokenRevokeReason;
import com.shitulelv.aicollab.auth.refresh.service.RefreshTokenRepositoryService;
import com.shitulelv.aicollab.auth.service.RefreshTokenHashService;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.mapper.UserMapper;
import com.shitulelv.aicollab.user.model.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 在真实 PostgreSQL 与完整 Spring Security 链上验证 Refresh Token 轮换契约。 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "security.jwt.secret=test-only-secret-0123456789012345",
        "security.jwt.access-token-minutes=30"
})
@AutoConfigureMockMvc
class AuthRefreshIntegrationTest {

    private static final String COOKIE_NAME = "ai_collab_refresh_token";
    private static final String PASSWORD = "refresh-integration-password";
    private static final String ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RefreshTokenHashService refreshTokenHashService;

    @Autowired
    private RefreshTokenRepositoryService refreshTokenRepositoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UserEntity user;

    @BeforeEach
    void createUser() {
        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("refresh_flow_" + user.getId().toString().substring(0, 8));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setDisplayName("Refresh Flow User");
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        userMapper.insert(user);
    }

    @AfterEach
    void removeUser() {
        if (userMapper.selectById(user.getId()) != null) {
            userMapper.deleteById(user.getId());
        }
    }

    @Test
    void refreshWithoutAccessTokenRotatesTheCookieAndPreservesTheSessionBoundary() throws Exception {
        LoginResult login = loginAndReadTokens();
        String oldCredential = login.refreshCredential();
        RefreshTokenEntity oldBefore = find(oldCredential);
        Instant requestStartedAt = Instant.now();

        MvcResult result = refresh(oldCredential)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(1800))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn();
        Instant requestFinishedAt = Instant.now();
        String refreshedAccessToken = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").stringValue();
        String replacementCredential = readRefreshCookie(result);
        RefreshTokenEntity oldAfter = find(oldCredential);
        RefreshTokenEntity replacement = find(replacementCredential);

        assertThat(oldCredential.equals(replacementCredential)).isFalse();
        assertThat(login.accessToken().equals(refreshedAccessToken)).isFalse();
        assertThat(oldAfter.getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.ROTATED);
        assertThat(oldAfter.getReplacedByTokenId()).isEqualTo(replacement.getId());
        assertThat(replacement.getSessionId()).isEqualTo(oldBefore.getSessionId());
        assertThat(replacement.getSessionExpiresAt()).isEqualTo(oldBefore.getSessionExpiresAt());
        assertThat(replacement.getExpiresAt().toInstant())
                .isBetween(requestStartedAt.plus(Duration.ofDays(14)).minusSeconds(1),
                        requestFinishedAt.plus(Duration.ofDays(14)).plusSeconds(1));
    }

    @Test
    void allMissingUnknownMalformedExpiredAndUnavailableCredentialsShareOne401Contract() throws Exception {
        expectUnauthorized(mockMvc.perform(post("/api/v1/auth/refresh")
                .header(HttpHeaders.ORIGIN, ORIGIN)));
        expectUnauthorized(refresh("Z".repeat(43)));
        expectUnauthorized(refresh("malformed"));

        String expiredCredential = loginAndReadRefreshCookie();
        RefreshTokenEntity expired = find(expiredCredential);
        jdbcTemplate.update("UPDATE refresh_token SET expires_at = now() - interval '1 second' WHERE id = ?",
                expired.getId());
        expectUnauthorized(refresh(expiredCredential));
        assertThat(find(expiredCredential).getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.EXPIRED);

        String expiredSessionCredential = loginAndReadRefreshCookie();
        RefreshTokenEntity expiredSession = find(expiredSessionCredential);
        jdbcTemplate.update(
                "UPDATE refresh_token SET session_expires_at = now() - interval '1 second' WHERE id = ?",
                expiredSession.getId());
        expectUnauthorized(refresh(expiredSessionCredential));
        assertThat(find(expiredSessionCredential).getRevokeReason())
                .isEqualTo(RefreshTokenRevokeReason.EXPIRED);

        String revokedCredential = loginAndReadRefreshCookie();
        RefreshTokenEntity revoked = find(revokedCredential);
        refreshTokenRepositoryService.revokeActiveTokensBySessionId(
                revoked.getSessionId(), RefreshTokenRevokeReason.LOGOUT,
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        expectUnauthorized(refresh(revokedCredential));

        String disabledCredential = loginAndReadRefreshCookie();
        user.setStatus(UserStatus.DISABLED);
        userMapper.updateById(user);
        expectUnauthorized(refresh(disabledCredential));
        assertThat(find(disabledCredential).getRevokeReason())
                .isEqualTo(RefreshTokenRevokeReason.USER_UNAVAILABLE);

        user.setStatus(UserStatus.ACTIVE);
        userMapper.updateById(user);
        String deletedCredential = loginAndReadRefreshCookie();
        userMapper.deleteById(user.getId());
        expectUnauthorized(refresh(deletedCredential));
    }

    @Test
    void reuseOfRotatedCredentialRevokesOnlyItsSessionAndLeavesAnotherLoginUsable() throws Exception {
        String compromisedOldCredential = loginAndReadRefreshCookie();
        String otherSessionCredential = loginAndReadRefreshCookie();
        UUID compromisedSessionId = find(compromisedOldCredential).getSessionId();
        UUID otherSessionId = find(otherSessionCredential).getSessionId();

        String compromisedReplacement = readRefreshCookie(
                refresh(compromisedOldCredential).andExpect(status().isOk()).andReturn());
        expectUnauthorized(refresh(compromisedOldCredential));

        RefreshTokenEntity revokedReplacement = find(compromisedReplacement);
        RefreshTokenEntity untouchedOtherSession = find(otherSessionCredential);
        assertThat(revokedReplacement.getSessionId()).isEqualTo(compromisedSessionId);
        assertThat(revokedReplacement.getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.REUSE_DETECTED);
        assertThat(untouchedOtherSession.getSessionId()).isEqualTo(otherSessionId);
        assertThat(untouchedOtherSession.getRevokedAt()).isNull();
        refresh(otherSessionCredential).andExpect(status().isOk());
    }

    @Test
    void logoutIsPublicIdempotentClearsConfiguredCookieAndRevokesOnlyTheCurrentSession() throws Exception {
        String currentSessionCredential = loginAndReadRefreshCookie();
        String otherSessionCredential = loginAndReadRefreshCookie();
        RefreshTokenEntity currentBefore = find(currentSessionCredential);
        RefreshTokenEntity otherBefore = find(otherSessionCredential);

        MvcResult logoutResult = mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie(COOKIE_NAME, currentSessionCredential)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();

        String clearCookie = logoutResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(clearCookie).contains(COOKIE_NAME + "=")
                .contains("Path=/api/v1/auth")
                .contains("SameSite=Strict")
                .contains("HttpOnly")
                .contains("Max-Age=0")
                .contains("Secure");
        assertThat(find(currentSessionCredential).getSessionId()).isEqualTo(currentBefore.getSessionId());
        assertThat(find(currentSessionCredential).getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.LOGOUT);
        assertThat(find(otherSessionCredential).getSessionId()).isEqualTo(otherBefore.getSessionId());
        assertThat(find(otherSessionCredential).getRevokedAt()).isNull();

        expectUnauthorized(refresh(currentSessionCredential));
        refresh(otherSessionCredential).andExpect(status().isOk());

        RefreshTokenEntity loggedOutBeforeRetry = find(currentSessionCredential);
        MvcResult repeatedLogout = mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie(COOKIE_NAME, currentSessionCredential)))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult missingCookieLogout = mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult unknownCookieLogout = mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie(COOKIE_NAME, "Z".repeat(43))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(repeatedLogout.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
        assertThat(missingCookieLogout.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
        assertThat(unknownCookieLogout.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
        RefreshTokenEntity loggedOutAfterRetry = find(currentSessionCredential);
        assertThat(loggedOutAfterRetry.getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.LOGOUT);
        assertThat(loggedOutAfterRetry.getRevokedAt()).isEqualTo(loggedOutBeforeRetry.getRevokedAt());
    }

    @Test
    void logoutOfTheOldRotatedCredentialRevokesItsReplacementAfterRotationCommitted() throws Exception {
        String oldCredential = loginAndReadRefreshCookie();
        String replacementCredential = readRefreshCookie(refresh(oldCredential)
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie(COOKIE_NAME, oldCredential)))
                .andExpect(status().isOk());

        assertThat(find(oldCredential).getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.ROTATED);
        assertThat(find(replacementCredential).getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.LOGOUT);
        expectUnauthorized(refresh(replacementCredential));
    }

    @Test
    void logoutDoesNotWriteLogoutReasonForAnExpiredCredentialOrSession() throws Exception {
        String expiredCredential = loginAndReadRefreshCookie();
        RefreshTokenEntity expiredToken = find(expiredCredential);
        jdbcTemplate.update("UPDATE refresh_token SET expires_at = now() - interval '1 second' WHERE id = ?",
                expiredToken.getId());
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie(COOKIE_NAME, expiredCredential)))
                .andExpect(status().isOk());
        assertThat(find(expiredCredential).getRevokedAt()).isNull();
        assertThat(find(expiredCredential).getRevokeReason()).isNull();

        String expiredSessionCredential = loginAndReadRefreshCookie();
        RefreshTokenEntity expiredSession = find(expiredSessionCredential);
        jdbcTemplate.update("UPDATE refresh_token SET session_expires_at = now() - interval '1 second' WHERE id = ?",
                expiredSession.getId());
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie(COOKIE_NAME, expiredSessionCredential)))
                .andExpect(status().isOk());
        assertThat(find(expiredSessionCredential).getRevokedAt()).isNull();
        assertThat(find(expiredSessionCredential).getRevokeReason()).isNull();
    }

    private String loginAndReadRefreshCookie() throws Exception {
        return loginAndReadTokens().refreshCredential();
    }

    private LoginResult loginAndReadTokens() throws Exception {
        String request = objectMapper.writeValueAsString(
                Map.of("username", user.getUsername(), "password", PASSWORD));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").stringValue();
        return new LoginResult(accessToken, readRefreshCookie(result));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String credential) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(new Cookie(COOKIE_NAME, credential)));
    }

    private void expectUnauthorized(org.springframework.test.web.servlet.ResultActions request) throws Exception {
        request.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("当前登录状态无效，请重新登录"));
    }

    private RefreshTokenEntity find(String credential) {
        return refreshTokenRepositoryService.findByTokenHash(refreshTokenHashService.hash(credential)).orElseThrow();
    }

    private String readRefreshCookie(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        int valueStart = COOKIE_NAME.length() + 1;
        return setCookie.substring(valueStart, setCookie.indexOf(';', valueStart));
    }

    private record LoginResult(String accessToken, String refreshCredential) {
    }
}
