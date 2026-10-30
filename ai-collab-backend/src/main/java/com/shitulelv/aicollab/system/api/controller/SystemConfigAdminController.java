package com.shitulelv.aicollab.system.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.system.api.dto.UpdateSystemConfigRequest;
import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import com.shitulelv.aicollab.system.application.view.SystemConfigAuditView;
import com.shitulelv.aicollab.system.application.view.SystemConfigView;
import com.shitulelv.aicollab.system.application.view.SystemRuntimeStatusView;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 动态系统配置中心后台接口。
 *
 * <p>必须具备系统管理员权限方可访问。提供配置查询、动态保存、合法性校验、
 * 审计日志追溯、版本回滚及运行时状态监控。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/config")
public class SystemConfigAdminController {
    private final SystemConfigService configService;

    public SystemConfigAdminController(SystemConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ApiResponse<SystemConfigView> getConfig(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(configService.getCurrentView(userId(jwt)));
    }

    @PutMapping
    public ApiResponse<SystemConfigView> updateConfig(
            @Valid @RequestBody UpdateSystemConfigRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(configService.updateConfig(userId(jwt), request, username(jwt)));
    }

    @PostMapping("/validate")
    public ApiResponse<Void> validateConfig(
            @Valid @RequestBody UpdateSystemConfigRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        configService.validateRequest(userId(jwt), request);
        return ApiResponse.success(null);
    }

    @GetMapping("/audit")
    public ApiResponse<List<SystemConfigAuditView>> listAudits(
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(configService.listAudits(userId(jwt), limit));
    }

    @PostMapping("/restore/{auditId}")
    public ApiResponse<SystemConfigView> restoreConfig(
            @PathVariable UUID auditId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(configService.restoreConfig(userId(jwt), auditId, username(jwt)));
    }

    @GetMapping("/runtime-status")
    public ApiResponse<SystemRuntimeStatusView> getRuntimeStatus(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(configService.getRuntimeStatus(userId(jwt)));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }

    private static String username(Jwt jwt) {
        String claim = jwt.getClaimAsString("username");
        return claim != null ? claim : jwt.getSubject();
    }
}
