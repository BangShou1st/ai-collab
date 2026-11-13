package com.shitulelv.aicollab.common.security;

import com.shitulelv.aicollab.auth.service.UserSessionStateService;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.user.model.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Bearer 认证之后的会话状态闸门。
 *
 * <p>只拦截已由 Resource Server 验证签名与过期的用户 JWT；内部机器令牌不会进入该分支。
 * 旧格式 Access Token 没有 tv 时按 0 处理；用户 token_version 一旦大于 0，旧 Token 立即失效。</p>
 */
public class AuthSessionGuardFilter extends OncePerRequestFilter {

    static final String TOKEN_VERSION_CLAIM = "tv";

    private final UserSessionStateService sessionStates;
    private final ObjectMapper objectMapper;

    public AuthSessionGuardFilter(UserSessionStateService sessionStates, ObjectMapper objectMapper) {
        this.sessionStates = sessionStates;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication) {
            Jwt jwt = authentication.getToken();
            UUID userId = subject(jwt);
            Integer tokenVersion = tokenVersion(jwt);
            boolean active = userId != null
                    && tokenVersion != null
                    && sessionStates.find(userId)
                    .filter(state -> state.status() == UserStatus.ACTIVE)
                    .filter(state -> state.tokenVersion() == tokenVersion)
                    .isPresent();
            if (!active) {
                SecurityContextHolder.clearContext();
                unauthorized(response);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private static UUID subject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Integer tokenVersion(Jwt jwt) {
        Object claim = jwt.getClaim(TOKEN_VERSION_CLAIM);
        if (claim == null) {
            return 0;
        }
        if (!(claim instanceof Number number)) {
            return null;
        }
        long value = number.longValue();
        if (value < 0 || value > Integer.MAX_VALUE || number.doubleValue() != value) {
            return null;
        }
        return (int) value;
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.AUTH_SESSION_REVOKED.httpStatus().value());
        response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(ErrorCode.AUTH_SESSION_REVOKED));
    }
}
