package com.shitulelv.aicollab.common.security;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 出站 endpoint 通用安全策略，供 Model Provider、Embedding Provider、MCP 复用。
 * 要求 HTTPS、无 userinfo，DNS 解析后拒绝 loopback/private/link-local/multicast/CGNAT。
 * 默认不自动跟随 redirect；必须跟随处逐跳重验并限制 hop 数。
 */
public class OutboundEndpointPolicy {

    @FunctionalInterface
    public interface AddressResolver {
        InetAddress[] resolve(String host) throws Exception;
    }

    @FunctionalInterface
    public interface RedirectFetcher {
        Optional<URI> redirectTarget(URI current) throws Exception;
    }

    private final AddressResolver resolver;

    public OutboundEndpointPolicy() {
        this(InetAddress::getAllByName);
    }

    OutboundEndpointPolicy(AddressResolver resolver) {
        this.resolver = resolver;
    }

    public URI requirePublicHttps(URI endpoint) {
        try {
            if (endpoint == null || !endpoint.isAbsolute()
                    || !"https".equalsIgnoreCase(endpoint.getScheme())
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null
                    || endpoint.getFragment() != null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "外部接口地址无效");
            }
            requirePublicResolution(endpoint.getHost());
            return endpoint;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "外部接口地址无效");
        }
    }

    public URI followRedirects(URI start, RedirectFetcher fetcher, int maxHops) {
        try {
            URI current = requirePublicHttps(start);
            for (int hop = 0; hop < maxHops; hop++) {
                Optional<URI> next = fetcher.redirectTarget(current);
                if (next.isEmpty() || next.get() == null) {
                    return current;
                }
                current = requirePublicHttps(next.get());
            }
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "外部接口重定向次数过多");
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "外部接口地址无效");
        }
    }

    private void requirePublicResolution(String host) throws Exception {
        InetAddress[] addresses = resolver.resolve(host.toLowerCase(Locale.ROOT));
        if (addresses.length == 0
                || Arrays.stream(addresses).anyMatch(OutboundEndpointPolicy::forbiddenAddress)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "外部接口地址不允许");
        }
    }

    public static boolean forbiddenAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address) {
            byte[] raw = address.getAddress();
            int first = Byte.toUnsignedInt(raw[0]);
            int second = Byte.toUnsignedInt(raw[1]);
            return first == 0 || first == 127 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0)
                    || (first == 198 && (second == 18 || second == 19));
        }
        return false;
    }
}
