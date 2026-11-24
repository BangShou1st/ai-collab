package com.shitulelv.aicollab.common.security;

import com.shitulelv.aicollab.auth.service.UserSessionStateService;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import com.shitulelv.aicollab.user.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaintenanceModeFilterTest {

    private final SystemConfigService configService = mock(SystemConfigService.class);
    private final UserSessionStateService sessionStates = mock(UserSessionStateService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MaintenanceModeFilter filter = new MaintenanceModeFilter(configService, sessionStates, objectMapper);

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void maintenanceOffAllowsAllRequests() throws Exception {
        when(configService.isMaintenanceMode()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void maintenanceOnBlocksRegularUserWith503AndMessage() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticate(userId);
        when(configService.isMaintenanceMode()).thenReturn(true);
        when(configService.effectiveMaintenanceMessage()).thenReturn("系统预计维护 30 分钟");
        when(sessionStates.find(userId)).thenReturn(Optional.of(
                new UserSessionStateService.UserSessionState(UserStatus.ACTIVE, 0, false)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(response.getContentAsString())
                .contains(ErrorCode.SYSTEM_MAINTENANCE.name())
                .contains("系统预计维护 30 分钟");
    }

    @Test
    void maintenanceOnBlocksAnonymousBusinessAndRegisterEndpoints() throws Exception {
        when(configService.isMaintenanceMode()).thenReturn(true);
        when(configService.effectiveMaintenanceMessage()).thenReturn("维护中");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void maintenanceOnAllowsActiveSystemAdmin() throws Exception {
        UUID adminId = UUID.randomUUID();
        authenticate(adminId);
        when(configService.isMaintenanceMode()).thenReturn(true);
        when(sessionStates.find(adminId)).thenReturn(Optional.of(
                new UserSessionStateService.UserSessionState(UserStatus.ACTIVE, 0, true)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void maintenanceOnAllowsLoginRefreshStatusAndHealth() throws Exception {
        when(configService.isMaintenanceMode()).thenReturn(true);

        for (String[] target : new String[][]{
                {"GET", "/api/v1/system/status"},
                {"POST", "/api/v1/auth/login"},
                {"POST", "/api/v1/auth/refresh"},
                {"POST", "/api/v1/auth/logout"},
                {"GET", "/api/v1/auth/me"},
                {"GET", "/actuator/health"},
                {"POST", "/internal/ai/v1/model-calls"},
                {"GET", "/index.html"},
                {"GET", "/assets/app.js"},
                {"OPTIONS", "/api/v1/projects"}
        }) {
            MockHttpServletRequest request = new MockHttpServletRequest(target[0], target[1]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(chain.getRequest()).as("%s %s should bypass maintenance filter", target[0], target[1]).isNotNull();
        }
    }

    private static void authenticate(UUID userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(userId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
