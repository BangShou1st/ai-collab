package com.shitulelv.aicollab.auth.dto;

/** 刷新成功的 JSON 只包含新的短期 Access Token，不暴露刷新凭据或用户持久化信息。 */
public record AccessTokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds) {
}
