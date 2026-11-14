package com.shitulelv.aicollab.common.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * local/test 独占的本机出站白名单。
 *
 * <p>生产、staging 等非 local/test profile 只要配置了非空白名单，启动即失败。
 * 即使在 local/test，白名单也只允许精确 host:port 且 DNS 全部解析为 loopback；
 * 私网、链路本地、任意地址和非白名单端口都不会被放行。</p>
 */
@Component
public final class LocalOutboundTargetPolicy implements InitializingBean {

    public record Target(String host, int port) {
    }

    private final Set<Target> targets;
    private final Environment environment;
    private final OutboundEndpointPolicy.AddressResolver resolver;

    @Autowired
    public LocalOutboundTargetPolicy(
            @Value("${ai.outbound.local-allowed-targets:}") String configuredTargets,
            Environment environment) {
        this(configuredTargets, environment, InetAddress::getAllByName);
    }

    LocalOutboundTargetPolicy(String configuredTargets, Environment environment,
            OutboundEndpointPolicy.AddressResolver resolver) {
        this.targets = parse(configuredTargets);
        this.environment = environment;
        this.resolver = resolver;
    }

    @Override
    public void afterPropertiesSet() {
        if (!targets.isEmpty() && !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException(
                    "ai.outbound.local-allowed-targets 只允许在 local/test profile 启用");
        }
    }

    boolean allows(URI endpoint) {
        if (targets.isEmpty() || endpoint == null || !endpoint.isAbsolute()
                || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            return false;
        }
        String scheme = endpoint.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return false;
        }
        String host = normalizeHost(endpoint.getHost());
        int port = endpoint.getPort() >= 0
                ? endpoint.getPort()
                : ("https".equalsIgnoreCase(scheme) ? 443 : 80);
        if (!targets.contains(new Target(host, port))) {
            return false;
        }
        try {
            InetAddress[] addresses = resolver.resolve(host);
            return addresses != null && addresses.length > 0
                    && Arrays.stream(addresses).allMatch(InetAddress::isLoopbackAddress);
        } catch (Exception exception) {
            return false;
        }
    }

    private static Set<Target> parse(String configuredTargets) {
        if (configuredTargets == null || configuredTargets.isBlank()) {
            return Set.of();
        }
        Set<Target> parsed = new HashSet<>();
        for (String raw : configuredTargets.split(",")) {
            String token = raw.strip();
            if (token.isEmpty()) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create("http://" + token);
            } catch (RuntimeException malformed) {
                throw invalidTarget();
            }
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw invalidTarget();
            }
            int port = uri.getPort();
            if (port < 1 || port > 65_535) {
                throw invalidTarget();
            }
            parsed.add(new Target(normalizeHost(uri.getHost()), port));
        }
        return Set.copyOf(parsed);
    }

    private static String normalizeHost(String host) {
        String normalized = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static IllegalArgumentException invalidTarget() {
        return new IllegalArgumentException(
                "ai.outbound.local-allowed-targets 必须为逗号分隔的精确 host:port");
    }
}
