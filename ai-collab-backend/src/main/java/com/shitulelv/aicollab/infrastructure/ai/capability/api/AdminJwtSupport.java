package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.user.service.UserService;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

final class AdminJwtSupport {

    private AdminJwtSupport() {
    }

    static UUID requireAdmin(Jwt jwt, UserService userService) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        userService.requireSystemAdmin(userId);
        return userId;
    }
}
