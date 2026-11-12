package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityAssignmentService;
import com.shitulelv.aicollab.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ai/capability-assignments")
public class AdminCapabilityAssignmentController {

    private final CapabilityAssignmentService service;
    private final UserService userService;

    public AdminCapabilityAssignmentController(CapabilityAssignmentService service,
            UserService userService) {
        this.service = service;
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<List<CapabilityAssignmentView>> list(@AuthenticationPrincipal Jwt jwt) {
        AdminJwtSupport.requireAdmin(jwt, userService);
        return ApiResponse.success(service.listAll());
    }

    @PostMapping
    public ApiResponse<CapabilityAssignmentView> upsertGlobal(
            @Valid @RequestBody CapabilityAssignmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = AdminJwtSupport.requireAdmin(jwt, userService);
        return ApiResponse.success(service.upsertGlobal(adminId, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = AdminJwtSupport.requireAdmin(jwt, userService);
        service.deleteGlobal(adminId, id);
        return ApiResponse.success(null);
    }
}
