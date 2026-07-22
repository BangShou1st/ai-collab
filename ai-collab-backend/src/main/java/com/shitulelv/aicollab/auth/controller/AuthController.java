package com.shitulelv.aicollab.auth.controller;

import com.shitulelv.aicollab.auth.dto.CurrentUserResponse;
import com.shitulelv.aicollab.auth.dto.AccessTokenResponse;
import com.shitulelv.aicollab.auth.dto.LoginRequest;
import com.shitulelv.aicollab.auth.dto.LoginResponse;
import com.shitulelv.aicollab.auth.model.AuthenticationResult;
import com.shitulelv.aicollab.auth.model.RefreshResult;
import com.shitulelv.aicollab.auth.service.AuthService;
import com.shitulelv.aicollab.auth.service.RefreshTokenCookieService;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 认证模块的 HTTP 入口。
 * Controller 只接收和校验 DTO、读取 SecurityContext 中的 Jwt 并调用 AuthService，不直接访问 Mapper 或编排业务规则。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookieService refreshTokenCookieService;

    public AuthController(
            AuthService authService,
            RefreshTokenCookieService refreshTokenCookieService) {
        this.authService = authService;
        this.refreshTokenCookieService = refreshTokenCookieService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        AuthenticationResult result = authService.login(request);
        response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookieService
                .createCookie(result.refreshToken().value(), result.refreshToken().expiresAt())
                .toString());
        return ApiResponse.success(result.loginResponse());
    }

    /**
     * 刷新端点只从 HttpOnly Cookie 接收长期凭据，不要求也不读取已经过期的 Access Token。
     */
    @PostMapping("/refresh")
    public ApiResponse<AccessTokenResponse> refresh(
            HttpServletRequest request,
            HttpServletResponse response) {
        String submittedToken = refreshTokenCookieService.readCookie(request)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_UNAUTHORIZED));
        RefreshResult result = authService.refresh(submittedToken);
        response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookieService
                .createCookie(result.refreshToken().value(), result.refreshToken().expiresAt())
                .toString());
        return ApiResponse.success(result.accessTokenResponse());
    }

    /**
     * 登出不要求 Access Token。无论凭据是否存在、有效或已撤销，都清除浏览器端 Cookie，
     * 以便客户端可以安全地重复调用这一端点。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        try {
            refreshTokenCookieService.readCookie(request)
                    .filter(value -> !value.isBlank())
                    .ifPresent(authService::logout);
            return ApiResponse.success(null);
        } finally {
            response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookieService.clearCookie().toString());
        }
    }

    /**
     * Resource Server 已在进入方法前验证签名与过期时间；这里使用 sub 定位用户，
     * 再由服务查询数据库确认账号此刻仍然有效，而不是盲目信任签发时的状态。
     */
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        try {
            return ApiResponse.success(authService.getCurrentUser(UUID.fromString(subject)));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
