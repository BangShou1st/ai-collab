package com.shitulelv.aicollab.auth;

import com.shitulelv.aicollab.TestcontainersConfiguration;
import com.shitulelv.aicollab.auth.refresh.entity.RefreshTokenEntity;
import com.shitulelv.aicollab.auth.refresh.service.RefreshTokenRepositoryService;
import com.shitulelv.aicollab.auth.service.RefreshTokenHashService;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.mapper.UserMapper;
import com.shitulelv.aicollab.user.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "security.jwt.secret=test-only-secret-0123456789012345",
        "security.jwt.access-token-minutes=30"
})
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    private static final String PASSWORD = "integration-password";
    private static final String ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RefreshTokenHashService refreshTokenHashService;

    @Autowired
    private RefreshTokenRepositoryService refreshTokenRepositoryService;

    private UserEntity user;

    @BeforeEach
    void createUser() {
        UUID id = UUID.randomUUID();
        user = new UserEntity();
        user.setId(id);
        user.setUsername("auth_flow_" + id.toString().substring(0, 8));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setDisplayName("Auth Flow User");
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
    void loginTokenPassesRealResourceServerAndLoadsCurrentUserFromPostgres() throws Exception {
        String token = loginAndReadToken();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.username").value(user.getUsername()))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void validTokenReturnsSame401AfterUserIsDisabledOrDeleted() throws Exception {
        String token = loginAndReadToken();
        user.setStatus(UserStatus.DISABLED);
        userMapper.updateById(user);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));

        userMapper.deleteById(user.getId());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    void loginCreatesAnIndependentHashedRefreshSessionAndKeepsTokenOutOfJson() throws Exception {
        String request = objectMapper.writeValueAsString(
                java.util.Map.of("username", user.getUsername(), "password", PASSWORD));

        String firstCookie = loginAndReadRefreshCookie(request);
        String secondCookie = loginAndReadRefreshCookie(request);

        assertThat(firstCookie).isNotEqualTo(secondCookie);
        RefreshTokenEntity firstSession = refreshTokenRepositoryService
                .findByTokenHash(refreshTokenHashService.hash(firstCookie))
                .orElseThrow();
        RefreshTokenEntity secondSession = refreshTokenRepositoryService
                .findByTokenHash(refreshTokenHashService.hash(secondCookie))
                .orElseThrow();
        assertThat(firstSession.getTokenHash()).isNotEqualTo(firstCookie);
        assertThat(secondSession.getTokenHash()).isNotEqualTo(secondCookie);
        assertThat(firstSession.getSessionId()).isNotEqualTo(secondSession.getSessionId());
        assertThat(firstSession.getRevokedAt()).isNull();
        assertThat(secondSession.getRevokedAt()).isNull();
    }

    private String loginAndReadToken() throws Exception {
        String request = objectMapper.writeValueAsString(
                java.util.Map.of("username", user.getUsername(), "password", PASSWORD));
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").stringValue();
    }

    private String loginAndReadRefreshCookie(String request) throws Exception {
        var response = mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn()
                .getResponse();
        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("ai_collab_refresh_token=")
                .contains("Path=/api/v1/auth")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Max-Age=")
                .contains("; Secure");
        long maxAge = Long.parseLong(setCookie.substring(
                setCookie.indexOf("Max-Age=") + "Max-Age=".length(),
                setCookie.indexOf(';', setCookie.indexOf("Max-Age="))));
        assertThat(maxAge).isBetween(
                Duration.ofDays(14).minusSeconds(1).toSeconds(), Duration.ofDays(14).toSeconds());
        return setCookie.substring(
                "ai_collab_refresh_token=".length(), setCookie.indexOf(';'));
    }
}
