package com.shitulelv.aicollab.common.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * 浏览器认证端点使用的精确 CORS 来源白名单。
 * 允许凭据时不能使用通配符来源，因此空白列表在启动阶段即被拒绝。
 */
@ConfigurationProperties(prefix = "security.cors")
@Validated
public record CorsProperties(@DefaultValue("http://localhost:5173") @NotEmpty List<@NotBlank String> allowedOrigins) {

    /**
     * 认证 Cookie 端点只接受精确的 Web Origin，拒绝路径、通配符和其他 URI 组成部分。
     * 这样后续 CORS 与 Origin 校验可共享同一份不可放宽的来源配置。
     */
    @AssertTrue(message = "allowed-origins 必须是小写、无默认端口和路径的 canonical http/https Origin，且不能包含通配符")
    public boolean isAllowedOriginsValid() {
        return allowedOrigins != null && allowedOrigins.stream().allMatch(this::isHttpOrHttpsOrigin);
    }

    private boolean isHttpOrHttpsOrigin(String origin) {
        if (origin == null || origin.isBlank() || origin.contains("*")) {
            return false;
        }
        try {
            URI uri = new URI(origin);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            String rawAuthority = uri.getRawAuthority();
            boolean defaultPort = ("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443);
            return uri.isAbsolute()
                    && ("http".equals(scheme) || "https".equals(scheme))
                    && host != null
                    && host.equals(host.toLowerCase(Locale.ROOT))
                    && uri.getRawUserInfo() == null
                    && rawAuthority != null
                    && !rawAuthority.endsWith(":")
                    && !defaultPort
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && port <= 65535
                    && new URI(scheme, null, host, port, null, null, null).toString().equals(origin);
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
