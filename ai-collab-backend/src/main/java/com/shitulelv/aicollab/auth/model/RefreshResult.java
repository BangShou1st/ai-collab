package com.shitulelv.aicollab.auth.model;

import com.shitulelv.aicollab.auth.dto.AccessTokenResponse;

/**
 * 刷新用例的输出边界：JSON 数据与只交给 HTTP 层写 Cookie 的凭据分开保存，
 * 防止 Controller 直接序列化内部刷新凭据。
 */
public record RefreshResult(
        AccessTokenResponse accessTokenResponse,
        IssuedRefreshToken refreshToken) {
}
