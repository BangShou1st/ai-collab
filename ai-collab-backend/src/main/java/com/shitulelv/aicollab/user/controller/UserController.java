package com.shitulelv.aicollab.user.controller;

import com.shitulelv.aicollab.auth.dto.CurrentUserResponse;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.user.dto.UpdateCurrentUserRequest;
import com.shitulelv.aicollab.user.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final AccountService accounts;

    public UserController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PatchMapping("/me")
    public ApiResponse<CurrentUserResponse> updateMe(
            @Valid @RequestBody UpdateCurrentUserRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(accounts.updateProfile(userId(jwt), request));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
