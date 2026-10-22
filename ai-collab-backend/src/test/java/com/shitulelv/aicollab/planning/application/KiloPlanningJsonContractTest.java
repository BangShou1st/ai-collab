package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.KiloGatewayTransport;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ProviderPresetRegistry;
import com.shitulelv.aicollab.infrastructure.ai.model.KiloGatewayModelExecution;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kilo 预设的规划出站契约：规划请求不得携带原生 JSON 模式。
 *
 * <p>这条契约来自一次真实故障：Kilo 免费目录上的 MiniMax 拒绝 union 类型的 JSON Schema
 * （{@code "type":["string","null"]}），网关直接以 400 拒绝请求，规划在骨架阶段即失败
 * （{@code PLANNING_MODEL_INVALID_OUTPUT}）。原生模式还会在部分模型上被静默忽略，
 * 返回不合规 JSON。提示词 JSON 路径在实测的免费模型目录上合规率更高，
 * 且输出契约由应用侧的严格解析器保证。</p>
 */
class KiloPlanningJsonContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private UserAiProvider kiloProvider() {
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "Kilo Gateway Free",
                ModelProviderType.OPENAI_COMPATIBLE, "https://ai-gateway.kilo.ai/api/v1", "/chat/completions",
                null, "minimax/minimax-m3.1-flash:free", true, 0.2, 6000,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.NATIVE_TOOLS, ModelCapability.USAGE),
                true, OffsetDateTime.now(), OffsetDateTime.now(), "KILO_GATEWAY_FREE");
    }

    @Test
    void planningPromptJsonCommandSendsNoNativeJsonModeOnTheWire() throws Exception {
        KiloGatewayTransport transport = mock(KiloGatewayTransport.class);
        when(transport.chat(any())).thenReturn(mapper.readTree(
                "{\"model\":\"minimax/minimax-m3.1-flash:free\",\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}"));
        var exec = new KiloGatewayModelExecution(new ProviderPresetRegistry(), mapper, transport);

        // 规划层下发的命令形态：提示词 JSON，不携带 schema。
        ChatCompletionCommand command = new ChatCompletionCommand(null, "system", "user",
                ChatCompletionCommand.OutputFormat.PROMPT_JSON,
                com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose.PLANNING,
                null, List.of(), UUID.randomUUID());

        ChatCompletionResult result = exec.complete(kiloProvider(), command);

        assertThat(result.content()).isEqualTo("{\"ok\":true}");
        ArgumentCaptor<ObjectNode> body = ArgumentCaptor.forClass(ObjectNode.class);
        verify(transport).chat(body.capture());
        JsonNode sent = body.getValue();
        assertThat(sent.has("response_format")).isFalse();
        // 提示词路径必须自带“只输出 JSON”的强制指令，否则模型可能返回 Markdown 包裹的 JSON。
        assertThat(sent.path("messages").get(1).path("content").asText()).contains("只输出合法 JSON 对象");
    }

    @Test
    void planningSchemaIsCarriedByThePromptInsteadOfTheWire() throws Exception {
        KiloGatewayTransport transport = mock(KiloGatewayTransport.class);
        when(transport.chat(any())).thenReturn(mapper.readTree(
                "{\"model\":\"m:free\",\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}"));
        var exec = new KiloGatewayModelExecution(new ProviderPresetRegistry(), mapper, transport);

        // 传输层仍支持原生 schema（其他调用方可用），但规划层不会走到这条路。
        var schema = mapper.readTree("{\"type\":\"object\",\"required\":[\"tasks\"],\"properties\":{\"tasks\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"additionalProperties\":false}");
        exec.complete(kiloProvider(), new ChatCompletionCommand(null, "system", "user",
                ChatCompletionCommand.OutputFormat.JSON_OBJECT,
                com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose.PLANNING,
                schema, List.of(), UUID.randomUUID()));

        ArgumentCaptor<ObjectNode> body = ArgumentCaptor.forClass(ObjectNode.class);
        verify(transport).chat(body.capture());
        assertThat(body.getValue().path("response_format").path("type").asText()).isEqualTo("json_schema");
    }
}
