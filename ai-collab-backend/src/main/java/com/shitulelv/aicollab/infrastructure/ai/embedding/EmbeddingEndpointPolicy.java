package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.util.*;

/** Local exceptions are restricted to administrator-managed embedding, never chat or MCP. */
@Component
public class EmbeddingEndpointPolicy {
    private final OutboundEndpointPolicy publicEndpoints;
    private final Set<String> localTargets;
    public EmbeddingEndpointPolicy(OutboundEndpointPolicy publicEndpoints,
            @Value("${embedding.local-allowed-targets:127.0.0.1:11434,localhost:11434,[::1]:11434}") String targets) {
        this.publicEndpoints = publicEndpoints;
        this.localTargets = new HashSet<>(Arrays.asList(targets.toLowerCase(Locale.ROOT).split(",")));
    }
    public URI require(String provider, String baseUrl, String path) {
        URI base = URI.create(baseUrl.strip());
        URI suffix = URI.create(path.strip());
        if (!base.isAbsolute() || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || suffix.isAbsolute() || suffix.getRawAuthority() != null || suffix.getQuery() != null
                || suffix.getFragment() != null || !path.startsWith("/") || path.contains(".."))
            throw new IllegalArgumentException("Invalid embedding endpoint");
        URI endpoint = URI.create(baseUrl.replaceAll("/+$", "") + path);
        if (!"OLLAMA".equals(provider)) { publicEndpoints.requirePublicHttps(endpoint); return endpoint; }
        if (!"/v1/embeddings".equals(path) || !(base.getPath().isEmpty() || "/".equals(base.getPath())))
            throw new IllegalArgumentException("Ollama requires /v1/embeddings and an origin base URL");
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https"))) throw new IllegalArgumentException("Invalid scheme");
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        int port = endpoint.getPort() >= 0 ? endpoint.getPort() : scheme.equals("https") ? 443 : 80;
        if (!localTargets.contains(host + ":" + port)) throw new IllegalArgumentException("Embedding target not allowed");
        try {
            InetAddress[] resolved = InetAddress.getAllByName(host);
            if (resolved.length == 0) throw new IllegalArgumentException("Unresolved target");
            for (InetAddress address : resolved) {
                if (address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress())
                    throw new IllegalArgumentException("Unsafe resolved target");
                if ((host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"))
                        && !address.isLoopbackAddress()) throw new IllegalArgumentException("Invalid loopback resolution");
            }
        } catch (UnknownHostException failed) { throw new IllegalArgumentException("Unresolved embedding target", failed); }
        return endpoint;
    }
}
