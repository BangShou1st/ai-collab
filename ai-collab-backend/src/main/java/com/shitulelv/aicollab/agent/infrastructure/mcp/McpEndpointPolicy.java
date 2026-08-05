package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class McpEndpointPolicy {
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
        try {
            if (endpoint == null || !endpoint.isAbsolute()
                    || !"https".equalsIgnoreCase(endpoint.getScheme())
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null
                    || endpoint.getFragment() != null) {
                throw forbidden();
            }
            String host = endpoint.getHost().toLowerCase(Locale.ROOT);
            if (!allowedHosts.contains(host)) {
                throw forbidden();
            }
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(McpEndpointPolicy::forbidden)) {
                throw forbidden();
            }
            return endpoint;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw forbidden();
        }
    }

    private static boolean forbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address) {
            byte[] b = address.getAddress();
            int first = Byte.toUnsignedInt(b[0]);
            int second = Byte.toUnsignedInt(b[1]);
            return first == 0 || first == 127 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0)
                    || (first == 198 && (second == 18 || second == 19));
        }
        return false;
    }

    private static BusinessException forbidden() {
        return new BusinessException(ErrorCode.AGENT_MCP_ENDPOINT_FORBIDDEN);
    }
}
