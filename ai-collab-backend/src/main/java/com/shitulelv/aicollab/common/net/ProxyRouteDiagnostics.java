package com.shitulelv.aicollab.common.net;

import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Selection and optional CONNECT/TLS only: no model HTTP request, credentials or Prompt. */
public final class ProxyRouteDiagnostics {
    private ProxyRouteDiagnostics() {}

    /** Standalone JVM/IDE entry; does not start Spring, read credentials or load business data. */
    public static void main(String[] args) {
        try (HttpClient client = OutboundHttpClients.builder().build()) {
            if (args.length > 2) throw new IllegalArgumentException("Invalid diagnostic arguments");
            boolean tls = java.util.Arrays.asList(args).contains("--tls");
            String address = java.util.Arrays.stream(args).filter(arg -> !arg.equals("--tls")).findFirst()
                    .orElse("https://generativelanguage.googleapis.com/");
            URI target = URI.create(address);
            Map<String, Object> result = tls ? connectAndTls(client, target) : describe(client, target);
            System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));
            if (tls && !"VERIFIED".equals(result.get("tls"))) System.exit(1);
        } catch (Exception failure) {
            System.out.println("{\"status\":\"FAILED\",\"errorType\":\"" + failure.getClass().getSimpleName() + "\"}");
            System.exit(1);
        }
    }

    public static Map<String, Object> describe(HttpClient client, URI target) {
        new OutboundEndpointPolicy().requirePublicHttps(target);
        ProxySelector selector = client.proxy().orElse(ProxySelector.getDefault());
        Map<String, Object> result = new LinkedHashMap<>();
        if (selector instanceof EnvironmentProxySelector environment) result.putAll(environment.diagnostic(target));
        else {
            result.put("targetHost", target.getHost());
            result.put("selection", selector.select(target).getFirst().type() == Proxy.Type.DIRECT ? "DIRECT" : "PROXY");
            result.put("source", "INJECTED_SELECTOR");
        }
        result.put("processId", ProcessHandle.current().pid());
        result.put("parentProcessId", ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L));
        result.put("systemDiscoveryStartupArgument", ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch(arg -> arg.equals("-Djava.net.useSystemProxies=true")));
        result.put("systemDiscoveryProperty", System.getProperty("java.net.useSystemProxies", "UNSET"));
        result.put("nativeSystemProxySelected", "JDK_SYSTEM_SELECTOR".equals(result.get("source"))
                && Boolean.getBoolean("java.net.useSystemProxies"));
        result.put("connect", "NOT_TESTED");
        result.put("tls", "NOT_TESTED");
        result.put("providerReceipt", "NOT_TESTED_NO_HTTP_APPLICATION_REQUEST");
        result.put("chatRequests", 0);
        return result;
    }

    public static Map<String, Object> connectAndTls(HttpClient client, URI target) {
        Map<String, Object> result = new LinkedHashMap<>(describe(client, target));
        result.remove("connectTls"); // selection alone never proves transport; actual stages follow
        result.put("tcp", "NOT_TESTED");
        ProxySelector selector = client.proxy().orElse(ProxySelector.getDefault());
        Proxy proxy = selector.select(target).getFirst();
        int targetPort = target.getPort() == -1 ? 443 : target.getPort();
        String host = target.getHost();
        try (Socket socket = new Socket()) {
            SocketAddressChoice choice = choice(proxy, host, targetPort);
            socket.connect(choice.address(), 10_000);
            socket.setSoTimeout(10_000);
            result.put("tcp", "CONNECTED");
            if (choice.tunnel()) {
                String authority = host + ":" + targetPort;
                socket.getOutputStream().write(("CONNECT " + authority + " HTTP/1.1\r\nHost: "
                        + authority + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                int value;
                while (response.size() < 8192 && (value = socket.getInputStream().read()) != -1) {
                    response.write(value);
                    if (response.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break;
                }
                String[] status = response.toString(StandardCharsets.US_ASCII).split("\r\n", 2)[0].split(" ", 3);
                if (status.length < 2 || !status[1].equals("200")) {
                    result.put("connect", "REJECTED_OR_INVALID_RESPONSE");
                    return result;
                }
                result.put("connect", "HTTP_200_TUNNEL_ESTABLISHED");
            } else result.put("connect", "DIRECT_TCP_NO_PROXY_TUNNEL");
            try (SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(socket, host, targetPort, false)) {
                tls.setSoTimeout(10_000);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                result.put("tls", "VERIFIED");
                result.put("tlsProtocol", tls.getSession().getProtocol());
                result.put("hostnameVerification", true);
            }
        } catch (Exception failure) {
            if (!"CONNECTED".equals(result.get("tcp"))) result.put("tcp", "FAILED");
            result.put("transportFailureType", failure.getClass().getSimpleName());
            result.put("tls", "NOT_VERIFIED");
        }
        return result;
    }

    private static SocketAddressChoice choice(Proxy proxy, String host, int port) {
        if (proxy.type() == Proxy.Type.DIRECT) return new SocketAddressChoice(new InetSocketAddress(host, port), false);
        if (proxy.type() != Proxy.Type.HTTP || !(proxy.address() instanceof InetSocketAddress address))
            throw new IllegalArgumentException("Unsupported proxy type");
        return new SocketAddressChoice(new InetSocketAddress(address.getHostString(), address.getPort()), true);
    }

    private record SocketAddressChoice(InetSocketAddress address, boolean tunnel) {}
}
