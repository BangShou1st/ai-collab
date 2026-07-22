package com.shitulelv.aicollab.auth.dto;

/** 登录成功响应，包含短期 Access Token 及其对应用户的安全视图。 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        CurrentUserResponse user) {
}
