package com.shitulelv.aicollab.infrastructure.ai.model;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Server-owned trust source for provider presets. Frontend must never submit baseUrl/apiPath for presets. */
@Component
public class ProviderPresetRegistry {
    public record PresetPolicy(ProviderPresetCode code, String displayName, ModelProviderType protocol,
            String baseUrl, String completionPath, String modelsPath, String userAgent, boolean directNoProxy,
            java.util.EnumSet<ModelCapability> capabilities, double defaultTemperature, int defaultMaxOutputTokens) {}
    private static final java.util.EnumSet<ModelCapability> ZEN_FREE_CAPABILITIES = java.util.EnumSet.of(
            ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.STRUCTURED_OUTPUT,
            ModelCapability.NATIVE_TOOLS, ModelCapability.USAGE);
    // Free reasoning models emit long reasoning traces before content; the default budget must cover both.
    private static final PresetPolicy ZEN_FREE = new PresetPolicy(ProviderPresetCode.OPENCODE_ZEN_FREE,
            "OpenCode Zen Free", ModelProviderType.OPENAI_COMPATIBLE,
            "https://opencode.ai/zen/v1", "/chat/completions", "/models", "opencode/1.18.21", true,
            ZEN_FREE_CAPABILITIES, 0.2, 4000);
    public PresetPolicy require(ProviderPresetCode code) {
        PresetPolicy p = policies.get(code);
        if (p == null) throw new IllegalArgumentException("unknown preset: " + code);
        return new PresetPolicy(p.code(), p.displayName(), p.protocol(), p.baseUrl(), p.completionPath(),
                p.modelsPath(), p.userAgent(), p.directNoProxy(), java.util.EnumSet.copyOf(p.capabilities()),
                p.defaultTemperature(), p.defaultMaxOutputTokens());
    }
    private final Map<ProviderPresetCode, PresetPolicy> policies = Map.of(ProviderPresetCode.OPENCODE_ZEN_FREE, ZEN_FREE);
    public List<PresetPolicy> all() { return List.copyOf(policies.values()); }
    public String completionEndpoint(PresetPolicy p) { return join(p.baseUrl(), p.completionPath()); }
    public String modelsEndpoint(PresetPolicy p) { return join(p.baseUrl(), p.modelsPath()); }
    private static String join(String base, String path) {
        String b = base.strip();
        String q = path.strip();
        if (b.endsWith("/") && q.startsWith("/")) return b + q.substring(1);
        if (!b.endsWith("/") && !q.startsWith("/")) return b + "/" + q;
        return b + q;
    }
}
