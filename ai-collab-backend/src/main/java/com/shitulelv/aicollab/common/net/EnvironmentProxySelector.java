package com.shitulelv.aicollab.common.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Client-scoped policy. OS discovery remains entirely in the JDK native selector. */
public final class EnvironmentProxySelector extends ProxySelector {
    private final ProxySelector nativeSelector;
    private final Function<String, String> properties;
    private final Map<String, String> environment;

    public EnvironmentProxySelector(ProxySelector nativeSelector, Function<String, String> properties,
                                    Map<String, String> environment) {
        this.nativeSelector = nativeSelector;
        this.properties = properties;
        this.environment = Map.copyOf(environment);
    }

    public static EnvironmentProxySelector system() {
        return new EnvironmentProxySelector(ProxySelector.getDefault(), System::getProperty, System.getenv());
    }

    @Override
    public List<Proxy> select(URI uri) { return List.of(route(uri).proxy()); }

    public Route route(URI uri) {
        if (uri == null || uri.getHost() == null) throw invalid();
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (local(host)) return new Route(Proxy.NO_PROXY, "LOCAL_BYPASS");
        String protocol = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!protocol.equals("http") && !protocol.equals("https")) throw invalid();
        boolean explicit = false;
        for (String prefix : List.of(protocol + ".proxy", "proxy", "socksProxy")) {
            String configured = properties.apply(prefix + "Host");
            if (configured != null) {
                validateHost(configured);
                String port = properties.apply(prefix + "Port");
                if (port != null) port(port);
                explicit = true;
            }
        }
        Proxy nativeProxy;
        try {
            List<Proxy> candidates = nativeSelector == null ? List.of(Proxy.NO_PROXY) : nativeSelector.select(uri);
            if (candidates == null || candidates.isEmpty() || candidates.getFirst() == null) throw invalid();
            nativeProxy = candidates.getFirst();
        } catch (RuntimeException failure) { throw invalid(); }
        if (explicit || nativeProxy.type() != Proxy.Type.DIRECT) {
            validateProxy(nativeProxy);
            return new Route(nativeProxy, explicit ? "JVM_PROPERTIES" : "JDK_SYSTEM_SELECTOR");
        }
        // Native DIRECT is ambiguous (no OS setting or OS bypass). Only an explicit process
        // environment proxy can override it; NO_PROXY remains authoritative for that fallback.
        String configured = variable(protocol.equals("https") ? "HTTPS_PROXY" : "HTTP_PROXY");
        if (configured == null) configured = variable("ALL_PROXY");
        if (configured == null) return new Route(Proxy.NO_PROXY, "DIRECT_NO_PROXY_SELECTED");
        Proxy proxy = environmentProxy(configured); // reject invalid configuration even if excluded
        if (excluded(uri, variable("NO_PROXY"))) return new Route(Proxy.NO_PROXY, "ENV_NO_PROXY");
        return new Route(proxy, "ENVIRONMENT_FALLBACK");
    }

    /** No URI path/query/userinfo, raw configuration, exception message, headers or body. */
    public Map<String, Object> diagnostic(URI target) {
        Route route = route(target);
        String proxyHost = "";
        int proxyPort = 0;
        if (route.proxy().address() instanceof InetSocketAddress address) {
            proxyHost = address.getHostString();
            proxyPort = address.getPort();
        }
        return Map.of("targetHost", target.getHost(), "systemDiscoveryProperty",
                String.valueOf(properties.apply("java.net.useSystemProxies")), "source", route.source(),
                "selection", route.proxy().type() == Proxy.Type.DIRECT ? "DIRECT" : "PROXY",
                "proxyHost", proxyHost, "proxyPort", proxyPort,
                "connectTls", "NOT_TESTED_BY_SELECTION", "providerReceipt", "NOT_TESTED");
    }

    private String variable(String name) {
        String upper = environment.get(name);
        String lower = environment.get(name.toLowerCase(Locale.ROOT));
        String value = upper != null ? upper : lower;
        if (value == null) return null;
        if (value.isBlank()) return null;
        return value.strip();
    }

    private static Proxy environmentProxy(String value) {
        try {
            URI proxy = URI.create(value);
            if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null
                    || proxy.getUserInfo() != null || proxy.getRawQuery() != null || proxy.getRawFragment() != null
                    || !(proxy.getRawPath().isEmpty() || proxy.getRawPath().equals("/"))) throw invalid();
            validateHost(proxy.getHost());
            int proxyPort = proxy.getPort() == -1 ? 80 : port(Integer.toString(proxy.getPort()));
            return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.getHost(), proxyPort));
        } catch (RuntimeException failure) { throw invalid(); }
    }

    private static boolean excluded(URI target, String exclusions) {
        if (exclusions == null) return false;
        boolean matched = false;
        for (String item : exclusions.split(",", -1)) {
            String token = item.strip().toLowerCase(Locale.ROOT);
            if (token.equals("*")) { matched = true; continue; }
            if (token.isEmpty()) throw invalid();
            // URI handles bracketed IPv6 and optional port; no DNS lookup or OS parsing.
            String hostPart = token;
            int requiredPort = -1;
            if (token.startsWith("[")) {
                int end = token.indexOf(']');
                if (end < 0) throw invalid();
                hostPart = token.substring(0, end + 1);
                if (token.length() > end + 1) {
                    if (token.charAt(end + 1) != ':') throw invalid();
                    requiredPort = port(token.substring(end + 2));
                }
            } else if (token.indexOf(':') >= 0) {
                int colon = token.indexOf(':');
                hostPart = token.substring(0, colon);
                requiredPort = port(token.substring(colon + 1));
            }
            boolean suffix = hostPart.startsWith(".") || hostPart.startsWith("*.");
            if (hostPart.startsWith("*.")) hostPart = hostPart.substring(2);
            else if (hostPart.startsWith(".")) hostPart = hostPart.substring(1);
            validateHost(hostPart);
            String host = target.getHost().toLowerCase(Locale.ROOT);
            int targetPort = target.getPort() == -1 ? ("https".equalsIgnoreCase(target.getScheme()) ? 443 : 80) : target.getPort();
            boolean hostMatch = host.equals(hostPart) || (suffix && host.endsWith("." + hostPart));
            matched |= hostMatch && (requiredPort == -1 || requiredPort == targetPort);
        }
        return matched;
    }

    private static boolean local(String host) {
        return host.equals("localhost") || host.endsWith(".localhost") || host.equals("[::1]") || host.equals("::1")
                || host.equals("[::0]") || host.equals("::") || host.equals("0.0.0.0")
                || host.matches("127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");
    }

    private static void validateHost(String host) {
        if (host == null || host.isBlank() || !host.matches("[a-zA-Z0-9._\\-\\[\\]:]+")) throw invalid();
    }

    private static int port(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 65535) throw invalid();
            return parsed;
        } catch (RuntimeException failure) { throw invalid(); }
    }

    private static void validateProxy(Proxy proxy) {
        if (proxy.type() == Proxy.Type.DIRECT) return;
        if (proxy.type() != Proxy.Type.HTTP || !(proxy.address() instanceof InetSocketAddress address)) throw invalid();
        validateHost(address.getHostString());
        port(Integer.toString(address.getPort()));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid or unsupported HTTP proxy configuration (details redacted)");
    }

    @Override
    public void connectFailed(URI uri, SocketAddress address, IOException failure) {
        // Do not rotate proxies, fall back to DIRECT, or log potentially sensitive exception text.
    }

    public record Route(Proxy proxy, String source) {}
}
