package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfile;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 模型窗口的唯一解析入口：全局模型档案优先，未命中再回退
 * {@code agent.context.window-overrides}，仍未命中即视为未知。
 *
 * <p>档案来源为 {@code UNVERIFIED} 时窗口数值仍可使用，但沿用"估算"语义，
 * 不能向调用方宣称经核实；只有 PROVIDER_DOCUMENT / ADMIN_VERIFIED 才标记为已确认。</p>
 */
@Component
public class ModelWindowResolver {
    private final ModelProfileService profiles;
    private final AgentContextProperties properties;

    @Autowired
    public ModelWindowResolver(ModelProfileService profiles, AgentContextProperties properties) {
        this.profiles = profiles;
        this.properties = properties;
    }

    public AgentContextBudget.ModelWindow resolve(String providerType, String baseUrl, String apiPath,
                                                  String modelName) {
        if (profiles != null) {
            Optional<ModelProfile> profile = profiles.resolve(providerType, baseUrl, apiPath, modelName);
            if (profile.isPresent()) {
                ModelProfile value = profile.get();
                boolean verified = value.verified();
                return new AgentContextBudget.ModelWindow(value.contextWindowTokens(),
                        (verified ? "PROFILE:" : "PROFILE_UNVERIFIED:") + value.id(), !verified);
            }
        }
        return AgentContextBudget.resolveWindow(providerType, modelName,
                properties == null ? AgentContextProperties.defaults().windowOverrides()
                        : properties.windowOverrides());
    }

    /** 便捷装配（无档案数据源，仅按 window-overrides 解析）。 */
    public static ModelWindowResolver overridesOnly(AgentContextProperties properties) {
        return new ModelWindowResolver(null, properties);
    }
}
