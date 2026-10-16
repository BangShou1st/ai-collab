package com.sharedlibs.opencodezen;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared, versioned OpenCode Zen wire policy.
 *
 * <p>This class contains provider protocol facts only. Applications own credential
 * storage, persistence, routing, budgets, and provider-specific business flows.
 */
public final class OpenCodeZenProtocolPolicy {

    public static final String DEFAULT_BASE_URL = "https://opencode.ai/zen/v1";
    public static final String INFERENCE_BASE_URL = "https://opencode.ai/inference/v1";
    public static final String USER_AGENT =
            "opencode/1.19.0 ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14";

    public static final String MODELS_PATH = "/models";
    public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    public static final String RESPONSES_PATH = "/responses";
    public static final String ANTHROPIC_MESSAGES_PATH = "/messages";

    public static final String CLIENT_HEADER = "x-opencode-client";
    public static final String REQUEST_HEADER = "x-opencode-request";
    public static final String PROJECT_HEADER = "x-opencode-project";
    public static final String SESSION_HEADER = "x-opencode-session";
    public static final String SESSION_ID_HEADER = "x-opencode-session-id";

    public static final String CLIENT_ID = "cli";
    public static final String GLOBAL_PROJECT = "global";
    public static final String FREE_TIER_CREDENTIAL = "public";

    private static final List<Map<String, Object>> RESERVED_TOOLS = List.of(
            functionTool("bash", "command"),
            functionTool("read", "filePath"));

    private OpenCodeZenProtocolPolicy() {
    }

    /** Adds the verified OpenCode CLI identity headers to an outgoing request. */
    public static void applyIdentityHeaders(
            HttpRequest.Builder builder, String sessionId, String requestId) {
        applyIdentityHeaders(builder, sessionId, requestId, USER_AGENT);
    }

    /** Adds the verified CLI identity headers with an application-configured User-Agent. */
    public static void applyIdentityHeaders(
            HttpRequest.Builder builder, String sessionId, String requestId, String userAgent) {
        Objects.requireNonNull(builder, "builder");
        requireSingleLine(sessionId, "session id");
        requireSingleLine(requestId, "request id");
        validateUserAgent(userAgent);
        builder.header("User-Agent", userAgent)
                .header(CLIENT_HEADER, CLIENT_ID)
                .header(REQUEST_HEADER, requestId)
                .header(PROJECT_HEADER, GLOBAL_PROJECT)
                .header(SESSION_HEADER, sessionId)
                .header(SESSION_ID_HEADER, sessionId);
    }

    /** Validates an application-configured User-Agent before request construction. */
    public static void validateUserAgent(String userAgent) {
        requireSingleLine(userAgent, "User-Agent");
    }

    /** Builds the bearer value, using the public credential when no key is configured. */
    public static String authorizationHeader(String apiKey) {
        String credential = apiKey == null || apiKey.isBlank() ? FREE_TIER_CREDENTIAL : apiKey;
        return "Bearer " + credential;
    }

    /** The immutable reserved tool declarations required by the verified free-tier envelope. */
    public static List<Map<String, Object>> reservedToolDeclarations() {
        return RESERVED_TOOLS;
    }

    /** Adds the required CLI-shaped envelope to an outgoing chat payload. */
    public static void applyRequiredChatEnvelope(Map<String, Object> payload, boolean includeUsage) {
        Objects.requireNonNull(payload, "payload");
        payload.put("stream", true);
        if (includeUsage) {
            payload.put("stream_options", Map.of("include_usage", true));
        }
        payload.put("tools", RESERVED_TOOLS);
    }

    /** Appends Zen's reserved declarations after application-authorized tools. */
    public static List<Object> appendReservedTools(Collection<?> applicationTools) {
        List<Object> tools = new ArrayList<>();
        if (applicationTools != null) {
            tools.addAll(applicationTools);
        }
        tools.addAll(RESERVED_TOOLS);
        return List.copyOf(tools);
    }

    /** True when a user-authorized tool attempts to claim a provider-reserved name. */
    public static boolean hasReservedToolName(Collection<String> toolNames) {
        if (toolNames == null) {
            return false;
        }
        return toolNames.stream().anyMatch(name -> "bash".equals(name) || "read".equals(name));
    }

    private static Map<String, Object> functionTool(String name, String propertyName) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", "Reserved by the transport. Never call this tool.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(propertyName, Map.of("type", "string")))));
    }

    private static void requireSingleLine(String value, String label) {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("OpenCode " + label + " must be non-blank and single-line");
        }
    }
}
