package com.shitulelv.aicollab.auth.controller;

import com.shitulelv.aicollab.auth.dto.CurrentUserResponse;
import com.shitulelv.aicollab.auth.dto.AccessTokenResponse;
import com.shitulelv.aicollab.auth.dto.LoginResponse;
import com.shitulelv.aicollab.auth.model.AuthenticationResult;
import com.shitulelv.aicollab.auth.model.IssuedRefreshToken;
import com.shitulelv.aicollab.auth.model.RefreshResult;
import com.shitulelv.aicollab.auth.service.AuthService;
import com.shitulelv.aicollab.auth.service.RefreshTokenCookieService;
import com.shitulelv.aicollab.common.security.RestAccessDeniedHandler;
import com.shitulelv.aicollab.common.security.RestAuthenticationEntryPoint;
import com.shitulelv.aicollab.common.security.AuthRequestOriginValidator;
import com.shitulelv.aicollab.common.security.CorsProperties;
import com.shitulelv.aicollab.common.security.SecurityConfig;
import com.shitulelv.aicollab.user.model.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.Cookie;

import java.util.UUID;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, AuthRequestOriginValidator.class,
        AuthControllerTest.HealthStub.class, AuthControllerTest.TestCorsConfiguration.class})
class AuthControllerTest {

    private static final UUID USER_ID = UUID.fromString("99cb9d98-9128-4930-a56c-86360a641851");
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RefreshTokenCookieService refreshTokenCookieService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void loginIsPublicAndReturnsUnifiedResponse() throws Exception {
        CurrentUserResponse user = currentUser();
        when(authService.login(any())).thenReturn(new AuthenticationResult(
                new LoginResponse("signed.jwt", "Bearer", 1800, user),
                new IssuedRefreshToken("refresh-token", Instant.parse("2026-08-01T00:00:00Z"))));
        when(refreshTokenCookieService.createCookie(any(), any())).thenReturn(ResponseCookie
                .from("ai_collab_refresh_token", "refresh-token")
                .httpOnly(true)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(1)
                .build());

        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"owner\",\"password\":\"12345678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").value("signed.jwt"))
                .andExpect(jsonPath("$.data.user.username").value("owner"))
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")));
    }

    @Test
    void blankLoginRequestReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void refreshIsPublicReadsOnlyTheCookieAndRotatesIt() throws Exception {
        Instant expiresAt = Instant.parse("2026-08-01T00:00:00Z");
        when(refreshTokenCookieService.readCookie(any()))
                .thenReturn(Optional.of("submitted-placeholder"));
        when(authService.refresh("submitted-placeholder")).thenReturn(new RefreshResult(
                new AccessTokenResponse("new.access.jwt", "Bearer", 1800),
                new IssuedRefreshToken("replacement-placeholder", expiresAt)));
        when(refreshTokenCookieService.createCookie("replacement-placeholder", expiresAt))
                .thenReturn(ResponseCookie.from("ai_collab_refresh_token", "replacement-placeholder")
                        .httpOnly(true)
                        .sameSite("Strict")
                        .path("/api/v1/auth")
                        .maxAge(1)
                        .build());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .cookie(new Cookie("custom_refresh", "submitted-placeholder")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").value("new.access.jwt"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(1800))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("replacement-placeholder")));
        verify(refreshTokenCookieService).readCookie(any());
    }

    @Test
    void logoutKeepsInfrastructureFailuresAs500ButStillClearsTheCookie() throws Exception {
        when(refreshTokenCookieService.readCookie(any())).thenReturn(Optional.of("submitted-placeholder"));
        when(refreshTokenCookieService.clearCookie()).thenReturn(ResponseCookie
                .from("ai_collab_refresh_token", "")
                .httpOnly(true)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(0)
                .build());
        doThrow(new IllegalStateException("database unavailable"))
                .when(authService).logout("submitted-placeholder");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .cookie(new Cookie("ai_collab_refresh_token", "submitted-placeholder")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("ai_collab_refresh_token="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"))));
        verify(authService).logout("submitted-placeholder");
    }

    @Test
    void cookieEndpointsRequireAnExplicitBrowserSourceEvenThoughTheyAreAnonymous() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"owner\",\"password\":\"12345678\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        verifyNoInteractions(authService);
    }

    @Test
    void missingSourceIsRejectedBeforeMalformedOrEmptyLoginBodyParsing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        verifyNoInteractions(authService);
    }

    @Test
    void contextPathDoesNotBypassMandatorySourceOnAnyCookieEndpoint() throws Exception {
        mockMvc.perform(post("/x/api/v1/auth/login")
                        .contextPath("/x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(post("/x/api/v1/auth/refresh")
                        .contextPath("/x"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(post("/x/api/v1/auth/logout")
                        .contextPath("/x"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        verifyNoInteractions(authService);
    }

    @Test
    void repeatedOriginOrRefererHeadersAreRejectedBeforeControllerInvocation() throws Exception {
        String body = "{\"username\":\"owner\",\"password\":\"12345678\"}";
        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN, "https://app.example.test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.REFERER,
                                ALLOWED_ORIGIN + "/login", "https://app.example.test/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        verifyNoInteractions(authService);
    }

    @Test
    void untrustedCorsOriginReturnsUnified403WithoutLeakingTheAllowlist() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://evil.invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"owner\",\"password\":\"12345678\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andExpect(content().string(not(containsString(ALLOWED_ORIGIN))));

        verifyNoInteractions(authService);
    }

    @Test
    void allowedPreflightReturnsCredentialedExactCorsPolicy() throws Exception {
        mockMvc.perform(options("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "Authorization, Content-Type, Cache-Control"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("OPTIONS")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Authorization")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Content-Type")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Cache-Control")));
    }

    @Test
    void untrustedPreflightAlsoReturnsUnified403() throws Exception {
        mockMvc.perform(options("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "https://evil.invalid")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andExpect(content().string(not(containsString(ALLOWED_ORIGIN))));
    }

    @Test
    void allowedGetCorsRequestKeepsJwtProtectionAndCorsHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void allowedActualSuccessResponseIncludesCredentialedCorsHeaders() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void preflightPolicyAppliesToArbitraryApiPathsBeforeJwtAuthorization() throws Exception {
        mockMvc.perform(options("/api/v1/projects/any-path")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Authorization")));
    }

    @Test
    void meWithoutTokenReturnsJson401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    void meWithInvalidTokenReturnsJson401() throws Exception {
        when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("invalid"));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    void meWithValidTokenReadsSubjectAndReloadsUser() throws Exception {
        when(authService.getCurrentUser(USER_ID)).thenReturn(currentUser());

        mockMvc.perform(get("/api/v1/auth/me")
                        .with(jwt().jwt(builder -> builder.subject(USER_ID.toString()).claim("username", "owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void meWithMissingSubjectReturnsJson401InsteadOf500() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(jwt().jwt(builder -> builder.claims(claims -> claims.remove("sub")))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    void meWithMalformedSubjectReturnsJson401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(jwt().jwt(builder -> builder.subject("not-a-uuid"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private CurrentUserResponse currentUser() {
        return new CurrentUserResponse(USER_ID, "owner", "Local Owner", null, UserStatus.ACTIVE);
    }

    @RestController
    static class HealthStub {
        @GetMapping("/actuator/health")
        java.util.Map<String, String> health() {
            return java.util.Map.of("status", "UP");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestCorsConfiguration {

        @Bean
        CorsProperties corsProperties() {
            return new CorsProperties(List.of(ALLOWED_ORIGIN, "https://app.example.test"));
        }
    }
}
