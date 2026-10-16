package com.sharedlibs.opencodezen.autoconfigure;

import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import com.sharedlibs.opencodezen.OpenCodeZenCredentialPolicy;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Objects;

/** Builds requests with the shared Zen identity and bearer-header policy. */
public final class OpenCodeZenRequestFactory {

    private final String userAgent;

    public OpenCodeZenRequestFactory(String userAgent) {
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
        OpenCodeZenProtocolPolicy.validateUserAgent(userAgent);
    }

    /**
     * Returns a request builder with the common identity and authorization headers applied.
     * The caller owns the request body, timeout, and HTTP method.
     */
    public HttpRequest.Builder newRequestBuilder(
            URI endpoint, String resolvedCredential, String sessionId, String requestId) {
        Objects.requireNonNull(endpoint, "endpoint");
        String scheme = endpoint.getScheme();
        if (!endpoint.isAbsolute() || endpoint.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("OpenCode Zen endpoint must be an absolute HTTP(S) URI");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint);
        OpenCodeZenProtocolPolicy.applyIdentityHeaders(builder, sessionId, requestId, userAgent);
        return builder.header("Authorization",
                OpenCodeZenProtocolPolicy.authorizationHeader(resolvedCredential));
    }

    /** Resolves free/public versus paid/key credentials before applying request headers. */
    public HttpRequest.Builder newModelRequestBuilder(
            URI endpoint, boolean freeModel, String apiKey, String sessionId, String requestId) {
        String credential = OpenCodeZenCredentialPolicy.credentialForModel(freeModel, apiKey);
        return newRequestBuilder(endpoint, credential, sessionId, requestId);
    }

    public String userAgent() {
        return userAgent;
    }
}
