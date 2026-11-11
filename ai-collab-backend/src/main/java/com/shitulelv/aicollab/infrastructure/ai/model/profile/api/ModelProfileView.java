package com.shitulelv.aicollab.infrastructure.ai.model.profile.api;

import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfile;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 模型窗口档案的对外视图；{@code verified} 由来源派生，客户端不需要自行判断。 */
public record ModelProfileView(
        UUID id,
        String providerType,
        String baseUrl,
        String apiPath,
        String modelName,
        int contextWindowTokens,
        Integer maxOutputTokens,
        boolean supportsUsage,
        String tokenizerKind,
        String pricingVersion,
        String source,
        boolean verified,
        OffsetDateTime verifiedAt,
        UUID verifiedBy,
        OffsetDateTime updatedAt) {

    public static ModelProfileView from(ModelProfile profile) {
        return new ModelProfileView(
                profile.id(), profile.providerType(), profile.baseUrl(), profile.apiPath(), profile.modelName(),
                profile.contextWindowTokens(), profile.maxOutputTokens(), profile.supportsUsage(),
                profile.tokenizerKind(), profile.pricingVersion(),
                profile.source() == null ? null : profile.source().name(), profile.verified(),
                profile.verifiedAt(), profile.verifiedBy(), profile.updatedAt());
    }
}
