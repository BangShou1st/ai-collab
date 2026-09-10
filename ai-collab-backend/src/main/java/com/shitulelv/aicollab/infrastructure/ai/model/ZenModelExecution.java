package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Zen preset runtime execution. Single place where preset branches to DIRECT transport + registry authority. */
@Component
public class ZenModelExecution {
    private final ProviderPresetRegistry registry;
    private final ObjectMapper mapper;
    private final OutboundEndpointPolicy endpoints;
    private final JsonHttpModelClient zenHttp;
    private final OpenAiCompatibleModelAdapter zenAdapter;
    public ZenModelExecution(ProviderPresetRegistry registry, ObjectMapper mapper, OutboundEndpointPolicy endpoints) {
        this.registry = registry;
        this.mapper = mapper;
        this.endpoints = endpoints;
        HttpClient direct = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
        this.zenHttp = new JsonHttpModelClient(mapper, endpoints, direct);
        this.zenAdapter = new OpenAiCompatibleModelAdapter(mapper, zenHttp);
    }
    public boolean isZen(UserAiProvider p) {
        return p != null && ProviderPresetCode.OPENCODE_ZEN_FREE.name().equals(p.presetCode());
    }
    public boolean isZenConfig(ModelConfiguration c, String presetCode) {
        return ProviderPresetCode.OPENCODE_ZEN_FREE.name().equals(presetCode);
    }
    public ModelConfiguration runtimeConfig(UserAiProvider p) {
        ProviderPresetRegistry.PresetPolicy pol = registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE);
        return new ModelConfiguration(p.id(), p.userId(), pol.displayName(), pol.protocol(),
                pol.baseUrl(), pol.completionPath(), p.encryptedApiKey(), p.modelName(),
                p.enabled(), p.temperature(), p.maxOutputTokens(), pol.capabilities(), p.createdAt(), p.updatedAt());
    }
    public String runtimeEndpoint() {
        ProviderPresetRegistry.PresetPolicy pol = registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE);
        return registry.completionEndpoint(pol);
    }
    public JsonHttpModelClient zenHttp() { return zenHttp; }
    private String userAgent() { return registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE).userAgent(); }
    public ModelTurnResult turn(UserAiProvider p, String apiKey, ModelTurnCommand cmd, AiRequestMetadata md) {
        return zenAdapter.turnWithSession(runtimeConfig(p), apiKey, cmd, md, userAgent());
    }
    public ChatCompletionResult complete(UserAiProvider p, String apiKey, ChatCompletionCommand cmd, AiRequestMetadata md) {
        if (cmd.outputFormat() == ChatCompletionCommand.OutputFormat.JSON_OBJECT) {
            return zenAdapter.completeStreamingSyncWithSession(runtimeConfig(p), apiKey, forceJson(cmd), md, userAgent());
        }
        return zenAdapter.completeWithSession(runtimeConfig(p), apiKey, cmd, md, userAgent());
    }

    /**
     * Prompt-forced JSON over streaming (spec-agent production shape): no response_format on the
     * wire. Downstream strict parser/schema/domain validation stays the single source of truth.
     */
    static ChatCompletionCommand forceJson(ChatCompletionCommand cmd) {
        String forced = cmd.userPrompt() + "\n\n只输出合法 JSON 对象，不要输出其他文字、解释或 Markdown 代码块。";
        return new ChatCompletionCommand(cmd.projectId(), cmd.systemPrompt(), forced,
                ChatCompletionCommand.OutputFormat.TEXT, cmd.purpose(), cmd.outputSchema(),
                cmd.tools(), cmd.callerUserId());
    }
    public void completeStream(UserAiProvider p, String apiKey, ChatCompletionCommand cmd, AiRequestMetadata md,
            Consumer<String> onToken, Consumer<ChatCompletionResult> onDone, Consumer<Exception> onError) {
        zenAdapter.completeStreamWithSession(runtimeConfig(p), apiKey, cmd, md, userAgent(), onToken, onDone, onError);
    }
}
