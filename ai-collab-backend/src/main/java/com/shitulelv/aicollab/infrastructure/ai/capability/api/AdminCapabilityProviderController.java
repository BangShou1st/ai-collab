package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProviderService;
import com.shitulelv.aicollab.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ai/capability-providers")
public class AdminCapabilityProviderController {

    private final CapabilityProviderService service;
    private final UserService userService;

    public AdminCapabilityProviderController(CapabilityProviderService service, UserService userService) {
        this.service = service;
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<List<CapabilityProviderView>> list(@AuthenticationPrincipal Jwt jwt) {
        AdminJwtSupport.requireAdmin(jwt, userService);
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<CapabilityProviderView> get(@PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        AdminJwtSupport.requireAdmin(jwt, userService);
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<CapabilityProviderView> create(
            @Valid @RequestBody CapabilityProviderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = AdminJwtSupport.requireAdmin(jwt, userService);
        return ApiResponse.success(service.create(adminId, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<CapabilityProviderView> update(@PathVariable UUID id,
            @Valid @RequestBody CapabilityProviderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = AdminJwtSupport.requireAdmin(jwt, userService);
        return ApiResponse.success(service.update(adminId, id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = AdminJwtSupport.requireAdmin(jwt, userService);
        service.delete(adminId, id);
        return ApiResponse.success(null);
    }
}
