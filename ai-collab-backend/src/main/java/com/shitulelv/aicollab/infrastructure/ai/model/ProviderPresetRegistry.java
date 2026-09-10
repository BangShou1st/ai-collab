package com.shitulelv.aicollab.infrastructure.ai.model;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Server-owned trust source for provider presets. Frontend must never submit baseUrl/apiPath for presets. */
@Component
public class ProviderPresetRegistry {
    public record PresetPolicy(ProviderPresetCode code, String displayName, ModelProviderType protocol,
            String baseUrl, String completionPath, String modelsPath, String userAgent, boolean directNoProxy) {}
    private static final PresetPolicy ZEN_FREE = new PresetPolicy(ProviderPresetCode.OPENCODE_ZEN_FREE,
            "OpenCode Zen Free", ModelProviderType.OPENAI_COMPATIBLE,
            "https://opencode.ai/zen/v1", "/chat/completions", "/models", "opencode/1.18.21", true);
    private final Map<ProviderPresetCode, PresetPolicy> policies = Map.of(ProviderPresetCode.OPENCODE_ZEN_FREE, ZEN_FREE);
    public PresetPolicy require(ProviderPresetCode code) {
        PresetPolicy p = policies.get(code);
        if (p == null) throw new IllegalArgumentException("unknown preset: " + code);
        return p;
    }
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
