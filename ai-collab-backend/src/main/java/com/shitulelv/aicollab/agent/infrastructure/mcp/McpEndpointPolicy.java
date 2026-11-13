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

    @org.springframework.beans.factory.annotation.Autowired
    public McpEndpointPolicy(
            @Value("${agent.mcp.allowed-hosts:}") String allowedHosts) {
        this(Arrays.stream(allowedHosts.split(","))
                .map(String::strip).filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet()),
                InetAddress::getAllByName);
    }

    McpEndpointPolicy(Set<String> allowedHosts, AddressResolver resolver) {
        this.allowedHosts = Set.copyOf(allowedHosts);
        this.resolver = resolver;
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
                    || endpoint.getFragment() != null
                    || (endpoint.getPort() != -1 && endpoint.getPort() != 443)) {
                throw forbidden();
            }
            String host = endpoint.getHost().toLowerCase(Locale.ROOT);
            if (!allowedHosts.contains(host)) {
                throw forbidden();
            }
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses.length == 0
                    || Arrays.stream(addresses).anyMatch(OutboundEndpointPolicy::forbiddenAddress)) {
                throw forbidden();
            }
            return new PinnedEndpoint(endpoint, List.of(addresses));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw forbidden();
        }
    }

    private static BusinessException forbidden() {
        return new BusinessException(ErrorCode.AGENT_MCP_ENDPOINT_FORBIDDEN);
    }
}
