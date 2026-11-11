package com.shitulelv.aicollab.internal.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.retrieval.rerank.RerankMode;
import com.shitulelv.aicollab.retrieval.rerank.RerankProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部模型网关端点测试：token fail-closed / 401 / 200 / 服务账号缺失 / JSON_OBJECT 降级 / 超时映射。
 *
 * <p>令牌使用明显的 test-only 固定字符串（"test-only-internal-token"），不来自也不写入任何生产配置。</p>
 */
class InternalModelCallControllerTest {

    private static final String TEST_ONLY_TOKEN = "test-only-internal-token";
    private static final String MODEL_USER_ID = "99999999-9999-9999-9999-999999999999";
    private static final UUID MODEL_USER_UUID = UUID.fromString(MODEL_USER_ID);
    private static final String ENDPOINT = "/internal/ai/v1/model-calls";

    private ChatModelGateway chatModelGateway;

    @BeforeEach
    void setUp() {
        chatModelGateway = mock(ChatModelGateway.class);
    }

    @Test
    void missingConfiguredTokenFailsClosedWith503() throws Exception {
        MockMvc mvc = mvc("", MODEL_USER_ID);

        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(requestBody("PROMPT_JSON")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.success").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(chatModelGateway);
    }

    @Test
    void wrongTokenIsRejectedWith401() throws Exception {
        MockMvc mvc = mvc(TEST_ONLY_TOKEN, MODEL_USER_ID);

        mvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("PROMPT_JSON")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.success").doesNotExist());

        verifyNoInteractions(chatModelGateway);
    }

    @Test
    void correctTokenCallsGatewayWithServiceAccountAndDefaultsToPromptJson() throws Exception {
        MockMvc mvc = mvc(TEST_ONLY_TOKEN, MODEL_USER_ID);
        when(chatModelGateway.complete(any())).thenReturn(
                new ChatCompletionResult("{\"score\":0.8}", "prov", "model-x", 3, 4, 12L));

        mvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer " + TEST_ONLY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBodyWithoutOutputFormat()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.content").value("{\"score\":0.8}"))
                .andExpect(jsonPath("$.usage.inputTokens").value(3))
                .andExpect(jsonPath("$.usage.outputTokens").value(4))
                .andExpect(jsonPath("$.usage.source").value("PROVIDER"))
                .andExpect(jsonPath("$.modelAlias").value("model-x"))
                .andExpect(jsonPath("$.latencyMs").value(12));

        ArgumentCaptor<ChatCompletionCommand> captor = ArgumentCaptor.forClass(ChatCompletionCommand.class);
        verify(chatModelGateway).complete(captor.capture());
        ChatCompletionCommand command = captor.getValue();
        assertThat(command.projectId()).isNull();
        assertThat(command.callerUserId()).isEqualTo(MODEL_USER_UUID);
        assertThat(command.purpose()).isEqualTo(ModelPurpose.KNOWLEDGE_CHAT);
        assertThat(command.outputFormat()).isEqualTo(ChatCompletionCommand.OutputFormat.PROMPT_JSON);
        assertThat(command.tools()).isEmpty();
    }

    @Test
    void missingModelUserIdFailsClosedWith503() throws Exception {
        MockMvc mvc = mvc(TEST_ONLY_TOKEN, "");

        mvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer " + TEST_ONLY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("PROMPT_JSON")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));

        verifyNoInteractions(chatModelGateway);
    }

    @Test
    void invalidRequestRejectedWith422AndFlatEnvelope() throws Exception {
        MockMvc mvc = mvc(TEST_ONLY_TOKEN, MODEL_USER_ID);
        String body = """
                {"schemaVersion":1,"purpose":"KNOWLEDGE_CHAT","systemPrompt":"打分",
                 "userPrompt":"候选","outputFormat":"PROMPT_JSON","deadlineMs":2500}
                """;

        mvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer " + TEST_ONLY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.success").doesNotExist());

        verifyNoInteractions(chatModelGateway);
    }

    @Test
    void jsonObjectDowngradesToPromptJsonOnceWhenStructuredOutputUnsupported() throws Exception {
        MockMvc mvc = mvc(TEST_ONLY_TOKEN, MODEL_USER_ID);
        when(chatModelGateway.complete(any()))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                        "当前模型配置不支持 STRUCTURED_OUTPUT"))
                .thenReturn(new ChatCompletionResult("{\"ok\":true}", "prov", "model-x", 1, 2, 5L));

        mvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer " + TEST_ONLY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("JSON_OBJECT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("{\"ok\":true}"));

        ArgumentCaptor<ChatCompletionCommand> captor = ArgumentCaptor.forClass(ChatCompletionCommand.class);
        verify(chatModelGateway, times(2)).complete(captor.capture());
        assertThat(captor.getAllValues().get(0).outputFormat())
                .isEqualTo(ChatCompletionCommand.OutputFormat.JSON_OBJECT);
        assertThat(captor.getAllValues().get(1).outputFormat())
                .isEqualTo(ChatCompletionCommand.OutputFormat.PROMPT_JSON);
    }

    @Test
    void providerUnavailableMapsTo503WithoutEchoingUpstreamMessage() throws Exception {
        MockMvc mvc = mvc(TEST_ONLY_TOKEN, MODEL_USER_ID);
        when(chatModelGateway.complete(any()))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "上游密钥 sk-secret 无效"));

        mvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer " + TEST_ONLY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("PROMPT_JSON")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("模型服务不可用"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sk-secret"))));
    }

    @Test
    void deadlineExceededMapsTo504() throws Exception {
        MockMvc mvc = mvc(TEST_ONLY_TOKEN, MODEL_USER_ID);
        when(chatModelGateway.complete(any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new ChatCompletionResult("late", "prov", "model-x", 1, 1, 500L);
        });

        mvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer " + TEST_ONLY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("PROMPT_JSON", 1)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("DEADLINE_EXCEEDED"));
    }

    // ---------- helpers ----------

    private MockMvc mvc(String serviceToken, String modelUserId) {
        RerankProperties properties = new RerankProperties(RerankMode.JAVA, "http://127.0.0.1:1", 3000,
                Duration.ofSeconds(2), serviceToken, 20, 8, modelUserId);
        InternalModelCallController controller = new InternalModelCallController(chatModelGateway, properties);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InternalAiExceptionHandler())
                .addFilters(new InternalAiTokenFilter(properties, new ObjectMapper()))
                .build();
    }

    private static String requestBody(String outputFormat) {
        return requestBody(outputFormat, 2500);
    }

    private static String requestBody(String outputFormat, int deadlineMs) {
        return """
                {"schemaVersion":1,"purpose":"KNOWLEDGE_CHAT","systemPrompt":"打分",
                 "userPrompt":"候选","outputFormat":"%s","deadlineMs":%d,"callId":"call-1"}
                """.formatted(outputFormat, deadlineMs);
    }

    private static String requestBodyWithoutOutputFormat() {
        return """
                {"schemaVersion":1,"purpose":"KNOWLEDGE_CHAT","systemPrompt":"打分",
                 "userPrompt":"候选","deadlineMs":2500,"callId":"call-1"}
                """;
    }
}
