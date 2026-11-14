package com.shitulelv.aicollab.infrastructure.ai.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * RERANK 运行时解析：项目绑定 → 全局绑定 → Java 启发式。
 *
 * <p>只解析 PUBLISHED profile + 可用 Provider；显式存在但失效的项目绑定不会静默
 * 落到全局（避免项目配置被绕过），直接返回失败原因供调用方回退。</p>
 */
@Component
public class RerankCapabilityResolver {

    private static final Logger log = LoggerFactory.getLogger(RerankCapabilityResolver.class);

    private final CapabilityAssignmentRepository assignments;
    private final CapabilityProfileRepository profiles;
    private final CapabilityProviderRepository providers;
    private final RerankSettingsSchema settingsSchema;

    public RerankCapabilityResolver(CapabilityAssignmentRepository assignments,
            CapabilityProfileRepository profiles, CapabilityProviderRepository providers,
            RerankSettingsSchema settingsSchema) {
        this.assignments = assignments;
        this.profiles = profiles;
        this.providers = providers;
        this.settingsSchema = settingsSchema;
    }

    public Resolution resolve(UUID projectId) {
        Optional<CapabilityAssignment> scoped = projectId == null
                ? Optional.empty()
                : assignments.findEnabledProject(projectId, CapabilityType.RERANK,
                        CapabilityPurpose.KNOWLEDGE_RERANK);
        if (scoped.isPresent()) {
            return validate(scoped.get());
        }
        Optional<CapabilityAssignment> global = assignments.findEnabledGlobal(CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK);
        if (global.isPresent()) {
            return validate(global.get());
        }
        return Resolution.unavailable("no_assignment");
    }

    private Resolution validate(CapabilityAssignment assignment) {
        if (assignment.capability() != CapabilityType.RERANK
                || !CapabilityPurpose.KNOWLEDGE_RERANK.equals(assignment.purpose())) {
            return unavailable("assignment_capability_mismatch");
        }
        CapabilityProfile profile = profiles.findById(assignment.modelCapabilityProfileId()).orElse(null);
        if (profile == null) {
            return unavailable("profile_missing");
        }
        if (!profile.isPublished()) {
            return unavailable("profile_not_published");
        }
        CapabilityProvider provider = providers.findById(profile.providerId()).orElse(null);
        if (provider == null) {
            return unavailable("provider_missing");
        }
        if (!provider.isAvailable()) {
            return unavailable("provider_disabled");
        }
        if (!profile.capability().name().equals(CapabilityType.RERANK.name())
                || !profile.protocol().equals(provider.protocol())) {
            return unavailable("provider_protocol_mismatch");
        }
        RerankSettings settings;
        try {
            settings = settingsSchema.parse(profile.settingsJson());
        } catch (RerankSettingsSchema.RerankSettingsInvalidException invalid) {
            log.warn("rerank profile settings 非法: profileId={} version={} reason={}",
                    profile.id(), profile.version(), invalid.getMessage());
            return unavailable("invalid_settings");
        }
        return new Resolution(new ResolvedRerankConfiguration(assignment, profile, provider, settings),
                null);
    }

    private static Resolution unavailable(String reason) {
        return Resolution.unavailable(reason);
    }

    public record Resolution(ResolvedRerankConfiguration configuration, String fallbackReason) {

        public boolean available() {
            return configuration != null;
        }

        public static Resolution unavailable(String reason) {
            return new Resolution(null, reason);
        }
    }
}
