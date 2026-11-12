package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProfileService;
import com.shitulelv.aicollab.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ai/capability-profiles")
public class AdminCapabilityProfileController {

    private final CapabilityProfileService service;
    private final UserService userService;

    public AdminCapabilityProfileController(CapabilityProfileService service, UserService userService) {
        this.service = service;
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<List<CapabilityProfileView>> list(@AuthenticationPrincipal Jwt jwt) {
        AdminJwtSupport.requireAdmin(jwt, userService);
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<CapabilityProfileView> get(@PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        AdminJwtSupport.requireAdmin(jwt, userService);
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<CapabilityProfileView> create(
            @Valid @RequestBody CapabilityProfileRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = AdminJwtSupport.requireAdmin(jwt, userService);
        return ApiResponse.success(service.create(adminId, request));
    }

    /**
     * 管理员能力测试。Lane B 用同一端点跑 8/20/50 候选：
     * 传 query + documents（1–50，id/title/text）+ 可选 topK；一次请求只调用一次 /rerank。
     */
    @PostMapping("/{id}/test")
    public ApiResponse<CapabilityTestResponse> test(@PathVariable UUID id,
            @Valid @RequestBody(required = false) CapabilityTestRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = AdminJwtSupport.requireAdmin(jwt, userService);
        return ApiResponse.success(service.test(adminId, id, request));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<CapabilityProfileView> publish(@PathVariable UUID id,
            @Valid @RequestBody(required = false) CapabilityActionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = AdminJwtSupport.requireAdmin(jwt, userService);
        return ApiResponse.success(service.publish(adminId, id,
                request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/rollback")
    public ApiResponse<CapabilityProfileView> rollback(@PathVariable UUID id,
            @Valid @RequestBody(required = false) CapabilityActionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = AdminJwtSupport.requireAdmin(jwt, userService);
        return ApiResponse.success(service.rollback(adminId, id,
                request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<CapabilityProfileView> disable(@PathVariable UUID id,
            @Valid @RequestBody(required = false) CapabilityActionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = AdminJwtSupport.requireAdmin(jwt, userService);
        return ApiResponse.success(service.disable(adminId, id,
                request == null ? null : request.reason()));
    }
}
