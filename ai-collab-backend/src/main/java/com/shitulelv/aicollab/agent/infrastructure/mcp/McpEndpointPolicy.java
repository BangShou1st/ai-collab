package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class McpEndpointPolicy {
    public record PinnedEndpoint(URI endpoint, List<InetAddress> addresses) {
        public PinnedEndpoint {
            addresses = List.copyOf(addresses);
        }
    }

    @FunctionalInterface
    public interface AddressResolver {
        InetAddress[] resolve(String host) throws Exception;
    }

    private final Set<String> allowedHosts;
    private final AddressResolver resolver;
    private final boolean testLoopbackEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public McpEndpointPolicy(
            @Value("${agent.mcp.allowed-hosts:}") String allowedHosts,
            @Value("${agent.mcp.test-loopback-enabled:false}") boolean testLoopbackEnabled) {
        this(Arrays.stream(allowedHosts.split(","))
                .map(String::strip).filter(value -> !value.isEmpty())
                .map(McpEndpointPolicy::normalizedHost)
                .collect(Collectors.toUnmodifiableSet()),
                InetAddress::getAllByName, testLoopbackEnabled);
    }

    McpEndpointPolicy(Set<String> allowedHosts, AddressResolver resolver) {
        this(allowedHosts, resolver, false);
    }

    McpEndpointPolicy(Set<String> allowedHosts, AddressResolver resolver,
            boolean testLoopbackEnabled) {
        this.allowedHosts = allowedHosts.stream()
                .map(McpEndpointPolicy::normalizedHost)
                .collect(Collectors.toUnmodifiableSet());
        this.resolver = resolver;
        this.testLoopbackEnabled = testLoopbackEnabled;
    }

    AddressResolver resolver() {
        return resolver;
    }

    public URI validate(URI endpoint) {
        return validateAndPin(endpoint).endpoint();
    }

    /**
     * 解析并固定本次连接可使用的地址，调用方必须让实际 socket 连接使用这份结果，
     * 不能再次按 hostname 解析（DNS rebinding 防护）。
     */
    public PinnedEndpoint validateAndPin(URI endpoint) {
        try {
            if (endpoint == null || !endpoint.isAbsolute()
                    || !"https".equalsIgnoreCase(endpoint.getScheme())
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null
                    || endpoint.getFragment() != null || endpoint.getPort() == 0) {
                throw forbidden();
            }
            String host = normalizedHost(endpoint.getHost());
            if (!allowedHosts.contains(host)) {
                throw forbidden();
            }
            boolean testLoopback = testLoopbackHost(host);
            if (testLoopback) {
                if (!testLoopbackEnabled) {
                    throw forbidden();
                }
            } else if (endpoint.getPort() != -1 && endpoint.getPort() != 443) {
                throw forbidden();
            }
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses.length == 0) {
                throw forbidden();
            }
            if (testLoopback) {
                if (Arrays.stream(addresses).anyMatch(address -> !address.isLoopbackAddress())) {
                    throw forbidden();
                }
            } else if (Arrays.stream(addresses).anyMatch(OutboundEndpointPolicy::forbiddenAddress)) {
                throw forbidden();
            }
            return new PinnedEndpoint(endpoint, List.of(addresses));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw forbidden();
        }
    }

    private static boolean testLoopbackHost(String host) {
        return "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equals(host);
    }

    private static String normalizedHost(String host) {
        String normalized = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static BusinessException forbidden() {
        return new BusinessException(ErrorCode.AGENT_MCP_ENDPOINT_FORBIDDEN);
    }
}
