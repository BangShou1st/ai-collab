package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 目录协议识别与拒绝：只有 Chat 协议的可执行模型能作为 Zen 预设；
 * Responses / Anthropic 模型必须在配置阶段以明确的协议不匹配原因被拒绝。
 */
class OpenCodeZenModelCatalogProtocolTest {
    private final ZenFreeModelSelection selection = new ZenFreeModelSelection(
            List.of("chat-free", "big-pickle"),
            Map.of("muse-spark-free", ZenModelProtocol.RESPONSES, "muse", ZenModelProtocol.ANTHROPIC));

    private OpenCodeZenModelCatalog catalog() {
        return new OpenCodeZenModelCatalog(new OpenCodeZenTransport() {
            public JsonNode listModelsRaw(String apiKey, AiRequestMetadata metadata) { return null; }
            public ZenFreeModelSelection freeModelSelection(String apiKey, AiRequestMetadata metadata) { return selection; }
            public void validateCredential(String apiKey, String model, AiRequestMetadata metadata) { }
        });
    }

    @Test
    void executableModelsAreAccepted() {
        OpenCodeZenModelCatalog catalog = catalog();

        catalog.requireExecutableModel("chat-free", selection);
        catalog.requireExecutableModel("  big-pickle  ", selection);
    }

    @Test
    void responsesModelIsRejectedWithProtocolReason() {
        assertThatThrownBy(() -> catalog().requireExecutableModel("muse-spark-free", selection))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(error.getMessage()).contains("responses").contains("Chat Completions");
                });
    }

    @Test
    void anthropicModelIsRejectedWithProtocolReason() {
        assertThatThrownBy(() -> catalog().requireExecutableModel("muse", selection))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).contains("messages"));
    }

    @Test
    void unknownOrUnavailableModelIsRejected() {
        assertThatThrownBy(() -> catalog().requireExecutableModel("gone-free", selection))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> catalog().requireExecutableModel("  ", selection))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void nonChatModelsAreNeverOfferedAsFreeModels() {
        assertThat(catalog().freeModels("k", AiRequestMetadata.of("s")))
                .containsExactly("chat-free", "big-pickle");
    }
}
