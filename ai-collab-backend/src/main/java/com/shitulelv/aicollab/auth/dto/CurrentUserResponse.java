package com.shitulelv.aicollab.auth.dto;

import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;

import java.util.UUID;

/**
 * 可安全返回给前端的用户视图。
 * 独立 DTO 明确排除 passwordHash、tokenVersion 等内部字段，防止 Entity 演进时意外泄密。
 */
public record CurrentUserResponse(
        UUID id,
        String username,
        String displayName,
        String email,
        UserStatus status) {

    public static CurrentUserResponse from(UserEntity user) {
        return new CurrentUserResponse(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(), user.getStatus());
    }
}
