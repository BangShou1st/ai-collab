package com.shitulelv.aicollab.common.security;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 对会签发、轮换或清除 Refresh Token Cookie 的请求执行精确来源校验。
 * Origin 存在时绝不回退到 Referer，避免攻击者用可信 Referer 掩盖不可信 Origin。
 */
@Component
public class AuthRequestOriginValidator {

    private final CorsProperties corsProperties;

    public AuthRequestOriginValidator(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    public void validate(HttpServletRequest request) {
        Optional<String> origin = readSingleHeader(request, HttpHeaders.ORIGIN);
        if (origin.isPresent()) {
            requireAllowed(origin.orElseThrow());
            return;
        }

        String referer = readSingleHeader(request, HttpHeaders.REFERER)
                .orElseThrow(this::forbidden);
        requireAllowed(extractOrigin(referer));
    }

    /** CORS 请求只校验显式 Origin；无 Origin 的非 Cookie 请求可以继续由认证与授权规则处理。 */
    public void validateCorsOriginIfPresent(HttpServletRequest request) {
        readSingleHeader(request, HttpHeaders.ORIGIN).ifPresent(this::requireAllowed);
    }

    private void requireAllowed(String origin) {
        if (origin == null || !corsProperties.allowedOrigins().contains(origin)) {
            throw forbidden();
        }
    }

    private Optional<String> readSingleHeader(HttpServletRequest request, String headerName) {
        Enumeration<String> enumeration = request.getHeaders(headerName);
        List<String> values = new ArrayList<>(2);
        if (enumeration != null) {
            while (enumeration.hasMoreElements()) {
                values.add(enumeration.nextElement());
            }
        }
        if (values.isEmpty()) {
            return Optional.empty();
        }
        if (values.size() != 1) {
            throw forbidden();
        }
        String value = values.getFirst();
        if (value == null || value.isBlank() || value.contains(",")) {
            throw forbidden();
        }
        return Optional.of(value);
    }

    private String extractOrigin(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(referer);
            String scheme = uri.getScheme();
            String normalizedScheme = scheme == null ? null : scheme.toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!("http".equals(normalizedScheme) || "https".equals(normalizedScheme))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawAuthority() == null
                    || uri.getRawAuthority().endsWith(":")) {
                return null;
            }
            int port = uri.getPort();
            if (("http".equals(normalizedScheme) && port == 80)
                    || ("https".equals(normalizedScheme) && port == 443)) {
                port = -1;
            }
            return new URI(
                    normalizedScheme,
                    null,
                    host.toLowerCase(Locale.ROOT),
                    port,
                    null,
                    null,
                    null).toString();
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private BusinessException forbidden() {
        return new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
}
