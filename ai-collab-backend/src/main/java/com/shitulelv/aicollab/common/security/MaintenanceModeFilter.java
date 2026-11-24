package com.shitulelv.aicollab.common.security;

import com.shitulelv.aicollab.auth.service.UserSessionStateService;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import com.shitulelv.aicollab.user.model.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 系统维护停站过滤器，位于 {@link AuthSessionGuardFilter} 之后。
 *
 * <p>关闭维护模式时直接零分配放行；开启维护模式后：</p>
 * <ul>
 *   <li>放行系统状态探针、登录/刷新/登出/当前用户接口（登录与刷新端点在业务层按管理员放行），防止管理员锁死；</li>
 *   <li>放行健康探针与内部机器端点（各有独立边界守卫）；</li>
 *   <li>已认证且活跃的系统管理员全部放行；</li>
 *   <li>普通用户及未放行的匿名请求统一返回 HTTP 503 {@link ErrorCode#SYSTEM_MAINTENANCE}。</li>
 * </ul>
 */
public class MaintenanceModeFilter extends OncePerRequestFilter {

    private static final String RETRY_AFTER_SECONDS = "60";
    private static final Set<String> ALLOWLIST_PATHS = Set.of(
            "/api/v1/system/status",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/me",
            "/api/v1/auth/registration-policy"
    );

    private final SystemConfigService systemConfigService;
    private final UserSessionStateService sessionStates;
    private final ObjectMapper objectMapper;

    public MaintenanceModeFilter(
            SystemConfigService systemConfigService,
            UserSessionStateService sessionStates,
            ObjectMapper objectMapper) {
        this.systemConfigService = systemConfigService;
        this.sessionStates = sessionStates;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (systemConfigService == null || !systemConfigService.isMaintenanceMode()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (isAlwaysAllowed(request) || isActiveSystemAdmin()) {
            filterChain.doFilter(request, response);
            return;
        }
        writeMaintenanceResponse(response, systemConfigService.effectiveMaintenanceMessage());
    }

    private boolean isAlwaysAllowed(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        return !uri.startsWith("/api/")
                || uri.startsWith("/actuator/")
                || uri.equals("/actuator")
                || uri.startsWith("/internal/")
                || ALLOWLIST_PATHS.contains(uri);
    }

    private boolean isActiveSystemAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            return false;
        }
        UUID userId = parseUserId(jwtAuth.getToken().getSubject());
        if (userId == null) {
            return false;
        }
        return sessionStates.find(userId)
                .filter(state -> state.status() == UserStatus.ACTIVE)
                .map(UserSessionStateService.UserSessionState::systemAdmin)
                .orElse(false);
    }

    private static UUID parseUserId(String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void writeMaintenanceResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(ErrorCode.SYSTEM_MAINTENANCE.httpStatus().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResponse<Map<String, Object>> payload = new ApiResponse<>(
                ErrorCode.SYSTEM_MAINTENANCE.name(),
                message,
                Map.of(
                        "maintenanceMode", true,
                        "maintenanceMessage", message));
        objectMapper.writeValue(response.getOutputStream(), payload);
    }
}
