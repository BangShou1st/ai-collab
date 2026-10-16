package com.sharedlibs.opencodezen;

/** Credential selection rules for free and paid OpenCode Zen models. */
public final class OpenCodeZenCredentialPolicy {

    private OpenCodeZenCredentialPolicy() {
    }

    /**
     * Selects the upstream credential for one model. Free models always use the
     * public credential, even when the application has a paid-model key stored.
     * Unknown models must be classified as paid by the caller.
     */
    public static String credentialForModel(boolean freeModel, String apiKey) {
        if (freeModel) {
            return OpenCodeZenProtocolPolicy.FREE_TIER_CREDENTIAL;
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("An API key is required for paid OpenCode models");
        }
        return apiKey;
    }

    /** Builds the Authorization header using the model-aware credential rule. */
    public static String authorizationHeader(boolean freeModel, String apiKey) {
        return "Bearer " + credentialForModel(freeModel, apiKey);
    }
}
