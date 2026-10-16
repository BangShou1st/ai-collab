package com.sharedlibs.opencodezen;

/** Price, retirement, and protocol facts for one discovered Zen model. */
public record ZenModelMetadata(
        String modelId,
        boolean free,
        boolean retired,
        ZenModelProtocol protocol) {

    public ZenModelMetadata {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("model id is required");
        }
        protocol = protocol == null ? ZenModelProtocol.UNKNOWN : protocol;
    }

    public boolean chatExecutable() {
        return !retired && protocol.chatExecutable();
    }
}