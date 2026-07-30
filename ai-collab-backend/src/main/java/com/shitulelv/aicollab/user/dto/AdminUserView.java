package com.shitulelv.aicollab.user.dto;

import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminUserView(
        UUID id,
        String username,
        String displayName,
        String email,
        UserStatus status,
        boolean systemAdmin,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt) {

    public static AdminUserView from(UserEntity user) {
        return new AdminUserView(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getStatus(), Boolean.TRUE.equals(user.getSystemAdmin()),
                user.getLastLoginAt(), user.getCreatedAt());
    }
}
