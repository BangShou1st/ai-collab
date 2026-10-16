package com.sharedlibs.opencodezen;

/** Native endpoint protocol declared by OpenCode model metadata. */
public enum ZenModelProtocol {
    CHAT,
    RESPONSES,
    ANTHROPIC,
    OTHER,
    UNKNOWN;

    /** Infers protocol only from a recognized provider package; unknown stays unknown. */
    public static ZenModelProtocol fromNpmPackage(String npmPackage) {
        if (npmPackage == null || npmPackage.isBlank()) {
            return UNKNOWN;
        }
        String value = npmPackage.trim();
        if (value.contains("anthropic")) {
            return ANTHROPIC;
        }
        if ("@ai-sdk/openai".equals(value)) {
            return RESPONSES;
        }
        if (value.contains("openai-compatible")) {
            return CHAT;
        }
        return OTHER;
    }

    public boolean chatExecutable() {
        return this == CHAT;
    }
}