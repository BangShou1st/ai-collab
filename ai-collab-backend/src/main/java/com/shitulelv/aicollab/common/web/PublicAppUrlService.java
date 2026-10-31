package com.shitulelv.aicollab.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Optional;

/**
 * 统一可信公开地址来源（APP_PUBLIC_URL）。
 *
 * 敏感链接（邀请、密码重置、邮箱验证）只能基于该部署级配置生成，
 * 绝不允许从 Host / X-Forwarded-Host 等请求头推断。配置存在但非法时直接启动失败，
 * 避免把错误地址静默写进安全敏感邮件。未配置时返回空，由调用方决定降级行为。
 */
@Service
public class PublicAppUrlService {
    private static final Logger log = LoggerFactory.getLogger(PublicAppUrlService.class);

    private final String configuredUrl;

    public PublicAppUrlService(@Value("${app.public-url:}") String configuredUrl) {
        String trimmed = configuredUrl == null ? "" : configuredUrl.trim();
        if (!trimmed.isEmpty()) {
            validate(trimmed);
            log.info("已启用统一公开地址配置 APP_PUBLIC_URL");
        }
        this.configuredUrl = trimmed.isEmpty() ? null : trimmed.replaceAll("/+$", "");
    }

    public Optional<String> publicUrl() {
        return Optional.ofNullable(configuredUrl);
    }

    /**
     * 生成项目邀请链接；未配置 APP_PUBLIC_URL 时返回空，前端回退到当前页面 Origin。
     */
    public Optional<String> invitationUrl(String invitationCode) {
        return publicUrl().map(base -> base + "/invite/" + invitationCode);
    }

    private static void validate(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("APP_PUBLIC_URL 不是合法 URL: " + value, exception);
        }
        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalStateException("APP_PUBLIC_URL 必须以 http(s) 开头: " + value);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException("APP_PUBLIC_URL 缺少主机名: " + value);
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalStateException("APP_PUBLIC_URL 只允许源（origin），不得携带路径或查询参数: " + value);
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("APP_PUBLIC_URL 只允许源（origin），不得携带路径或查询参数: " + value);
        }
    }
}
