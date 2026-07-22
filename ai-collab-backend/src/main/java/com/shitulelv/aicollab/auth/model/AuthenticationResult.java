package com.shitulelv.aicollab.auth.model;

import com.shitulelv.aicollab.auth.dto.LoginResponse;

/**
 * 认证用例的输出边界：业务层返回登录 JSON 数据及仅供 HTTP 层落 Cookie 的刷新凭据。
 *
 * <p>这样 AuthService 无需依赖 Servlet API，Controller 也不会参与会话创建规则。</p>
 */
public record AuthenticationResult(LoginResponse loginResponse, IssuedRefreshToken refreshToken) {
}
