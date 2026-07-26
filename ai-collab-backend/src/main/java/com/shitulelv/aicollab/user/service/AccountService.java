package com.shitulelv.aicollab.user.service;

import com.shitulelv.aicollab.auth.dto.ChangePasswordRequest;
import com.shitulelv.aicollab.auth.dto.CurrentUserResponse;
import com.shitulelv.aicollab.auth.service.RefreshTokenService;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.user.dto.UpdateCurrentUserRequest;
import com.shitulelv.aicollab.user.entity.UserEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class AccountService {
    private final UserService users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;

    public AccountService(
            UserService users,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokens,
            AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
    }

    @Transactional
    public CurrentUserResponse updateProfile(UUID userId, UpdateCurrentUserRequest request) {
        UserEntity user = requireUser(userId);
        String email = normalizeEmail(request.email());
        users.findByEmail(email)
                .filter(existing -> !existing.getId().equals(userId))
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
                });
        if (!users.updateProfile(userId, request.displayName().trim(), email)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        audit.write(null, userId, "USER_PROFILE_UPDATED", "USER", userId);
        return CurrentUserResponse.from(requireUser(userId));
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        UserEntity user = requireUser(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_INVALID);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.NEW_PASSWORD_SAME_AS_CURRENT);
        }
        if (!users.updatePassword(userId, passwordEncoder.encode(request.newPassword()))) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        refreshTokens.revokeAllForUser(userId);
        audit.write(null, userId, "USER_PASSWORD_CHANGED", "USER", userId);
    }

    @Transactional
    public void logoutAll(UUID userId) {
        requireUser(userId);
        refreshTokens.revokeAllForUser(userId);
        audit.write(null, userId, "USER_LOGOUT_ALL", "USER", userId);
    }

    private UserEntity requireUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_UNAUTHORIZED));
    }

    private static String normalizeEmail(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
