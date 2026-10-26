package com.shitulelv.aicollab.common.net;

import java.net.http.HttpClient;

/** Shared client-level network policy; never replaces the JVM-wide ProxySelector. */
public final class OutboundHttpClients {
    private OutboundHttpClients() {}

    public static HttpClient.Builder builder() {
        return HttpClient.newBuilder().proxy(EnvironmentProxySelector.system()).followRedirects(HttpClient.Redirect.NEVER);
    }
}
