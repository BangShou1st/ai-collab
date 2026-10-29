package com.shitulelv.aicollab.infrastructure.storage;

import java.net.URI;

/** Validates an origin without echoing potentially sensitive configuration. */
public final class MinioPublicEndpointValidator {
    private MinioPublicEndpointValidator() {
    }

    public static void validate(String endpoint, boolean required) {
        if (endpoint == null || endpoint.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("MINIO_PUBLIC_ENDPOINT 必须配置为单个 HTTPS Origin");
            }
            return;
        }
        try {
            URI uri = URI.create(endpoint);
            boolean loopbackHttp = !required && "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                    || "[::1]".equals(uri.getHost()));
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !loopbackHttp)
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("MINIO_PUBLIC_ENDPOINT 必须配置为单个安全的 Origin");
        }
    }
}
