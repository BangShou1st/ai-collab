package com.shitulelv.aicollab.infrastructure.ai.model;

import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import java.util.List;

/**
 * Zen wire boundary. Endpoint and identity come from {@link ProviderPresetRegistry}; only the session
 * header name lives here. HTTP-only, no DB.
 *
 * <p>免费准入的唯一事实来源是共享库的 {@code OpenCodeZenFreeAdmissionPolicy}：这里只负责把它与上游
 * 实时列表相交，供界面列出可选模型；准入判定本身不依赖本接口。</p>
 */
public interface OpenCodeZenTransport {
    String SESSION_HEADER = OpenCodeZenProtocolPolicy.SESSION_HEADER;

    /**
     * 当前可用于 Zen 预设的免费 Chat 模型：共享库已评审白名单 ∩ 上游实时列表。
     * 上游列表不可用时退回白名单本身（准入始终以白名单为准，不臆测可用性）。
     */
    List<String> admittedModels(String apiKey, AiRequestMetadata metadata);

    void validateCredential(String apiKey, String model, AiRequestMetadata metadata);
}
