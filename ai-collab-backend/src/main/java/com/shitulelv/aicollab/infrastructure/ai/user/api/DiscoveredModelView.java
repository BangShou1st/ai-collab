package com.shitulelv.aicollab.infrastructure.ai.user.api;

import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;

import java.util.List;

/**
 * 模型发现元数据视图：透传 {@code ai-model-bridge} 从上游 {@code /models} 识别的上下文窗口、
 * 输出上限与能力声明；对于未返回元数据的服务商（如 ModelScope），对应数值为 null，
 * 前端可据此提示并由管理员手动补充。
 */
public record DiscoveredModelView(
        String id,
        String displayName,
        String ownedBy,
        Integer contextWindowTokens,
        Integer maxInputTokens,
        Integer maxOutputTokens,
        boolean supportsTools,
        boolean supportsReasoning,
        boolean supportsStreaming,
        boolean supportsStructuredOutput,
        boolean metadataAvailable,
        List<String> reasoningLevels) {

    public static DiscoveredModelView from(ModelDescriptor descriptor) {
        Integer contextWindow = descriptor.contextWindow() != null
                ? descriptor.contextWindow()
                : descriptor.maxInputTokens();
        boolean hasMetadata = contextWindow != null
                || descriptor.maxOutputTokens() != null
                || descriptor.supportsTools()
                || descriptor.supportsReasoning();
        String name = descriptor.displayName() == null || descriptor.displayName().isBlank()
                ? descriptor.id()
                : descriptor.displayName();
        return new DiscoveredModelView(
                descriptor.id(),
                name,
                descriptor.ownedBy(),
                contextWindow,
                descriptor.maxInputTokens(),
                descriptor.maxOutputTokens(),
                descriptor.supportsTools(),
                descriptor.supportsReasoning(),
                descriptor.supportsStreaming(),
                descriptor.supportsStructuredOutput(),
                hasMetadata,
                descriptor.reasoningLevels());
    }
}
