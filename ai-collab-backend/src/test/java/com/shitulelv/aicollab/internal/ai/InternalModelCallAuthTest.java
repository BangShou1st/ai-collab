package com.shitulelv.aicollab.internal.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.retrieval.rerank.RerankMode;
import com.shitulelv.aicollab.retrieval.rerank.RerankProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
 * 内部模型端点鉴权补充（冻结契约 §2/§7.8）：只补 {@link InternalModelCallControllerTest} 未覆盖的
 * 鉴权面，不重复其既有用例。
 *
 * <p>已在 S3 覆盖、此处刻意不重复的用例：</p>
 * <ul>
 *   <li>{@code missingConfiguredTokenFailsClosedWith503}：未配置 {@code rerank.service-token} 时 fail closed；</li>
 *   <li>{@code wrongTokenIsRejectedWith401}：错误 token → 401 + {@code WWW-Authenticate: Bearer}；</li>
 *   <li>{@code missingModelUserIdFailsClosedWith503}：正确 token 但 {@code rerank.model-user-id}
 *       为空 → 503 MODEL_UNAVAILABLE 且不调用网关；</li>
 *   <li>{@code correctTokenCallsGatewayWithServiceAccountAndDefaultsToPromptJson}：200 + 服务账号调用。</li>
 * </ul>
 *
 * <p>本类补的缺口：</p>
 * <ol>
 *   <li><b>完全不带 Authorization 头</b>必须 401（而不是 422 参数错误或 503 未配置令牌），
 *       且响应是扁平错误信封（不含 {@code ApiResponse} 的 {@code success}/{@code data}）；</li>
 *   <li>"没带令牌"与"令牌错误"必须<b>不可区分</b>（同码同文案），且响应绝不回显令牌；</li>
 *   <li>鉴权通过后 {@code usage.source} 只能落在 {@code PROVIDER|UNKNOWN} 闭集内——
 *       上游未给 token 用量时必须是 {@code UNKNOWN}，不得用 0 冒充已知。</li>
 * </ol>
 */
class InternalModelCallAuthTest {

    private static final String TEST_ONLY_TOKEN = "test-only-internal-token-w3s4";
    private static final String MODEL_USER_ID = "99999999-9999-9999-9999-999999999999";
    private static final String ENDPOINT = "/internal/ai/v1/model-calls";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatModelGateway chatModelGateway;

    @BeforeEach
    void setUp() {
        chatModelGateway = mock(ChatModelGateway.class);
    }

    @Test
    void missingAuthorizationHeaderIs401WithFlatEnvelopeAndBearerChallenge() throws Exception {
        MvcResult result = mvc(TEST_ONLY_TOKEN, MODEL_USER_ID)
                .perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andReturn();

        // 鉴权失败不是参数错误：401 而不是 422（契约信封的 INVALID_REQUEST 默认码是 422）。
        assertThat(result.getResponse().getStatus()).isNotIn(422, 503);

        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        // 扁平信封的 4 个声明字段中 details 为空（NON_NULL）时不序列化，失败路径稳定为 3 个字段。
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("code", "message", "retryable");
        assertThat(body.get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(body.get("retryable").asBoolean()).isFalse();
        assertThat(body.get("message").asText()).isNotBlank();
        assertThat(body.has("details")).isFalse();
        // 绝不落回业务 ApiResponse 包装。
        assertThat(body.has("success")).isFalse();
        assertThat(body.has("data")).isFalse();

        verifyNoInteractions(chatModelGateway);
    }

    @Test
    void missingAndInvalidTokenProduceIndistinguishableEnvelopesAndNeverReachGateway() throws Exception {
        MockMvc mvc = mvc(TEST_ONLY_TOKEN, MODEL_USER_ID);

        MvcResult missing = mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult invalid = mvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer not-the-configured-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andReturn();

        String missingBody = missing.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String invalidBody = invalid.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 调用方拿不到"是漏带还是带错"的区分信息（也拿不到任何令牌原文）。
        assertThat(invalidBody).isEqualTo(missingBody);
        assertThat(invalidBody).doesNotContain(TEST_ONLY_TOKEN);
        assertThat(invalidBody).doesNotContain("not-the-configured-token");

        verifyNoInteractions(chatModelGateway);
    }

    @Test
    void authorizedCallReturnsSchemaVersionOneWithUsageSourceInFrozenSet() throws Exception {
        when(chatModelGateway.complete(any()))
                .thenReturn(new ChatCompletionResult("{\"ok\":true}", "prov", "model-x", 3, 4, 12L))
                .thenReturn(new ChatCompletionResult("{\"ok\":true}", "prov", "model-y", null, null, 7L));
        MockMvc mvc = mvc(TEST_ONLY_TOKEN, MODEL_USER_ID);

        // PROVIDER：上游确实报了 token 用量。
        mvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer " + TEST_ONLY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.usage.source").value("PROVIDER"))
                .andExpect(jsonPath("$.usage.inputTokens").value(3))
                .andExpect(jsonPath("$.usage.outputTokens").value(4));

        // UNKNOWN：上游没给 token 用量时不得用 0 冒充，tokens 保持 null。
        MvcResult unknownResult = mvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer " + TEST_ONLY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andReturn();

        JsonNode unknown = objectMapper.readTree(
                unknownResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(unknown.get("schemaVersion").asInt()).isEqualTo(1);
        String source = unknown.path("usage").path("source").asText();
        assertThat(source).isIn("PROVIDER", "UNKNOWN");
        assertThat(source).isEqualTo("UNKNOWN");
        assertThat(unknown.path("usage").path("inputTokens").isNull()).isTrue();
        assertThat(unknown.path("usage").path("outputTokens").isNull()).isTrue();

        verify(chatModelGateway, times(2)).complete(any());
    }

    // ---------- helpers ----------

    private MockMvc mvc(String serviceToken, String modelUserId) {
        RerankProperties properties = new RerankProperties(RerankMode.JAVA, "http://127.0.0.1:1", 3000,
                Duration.ofSeconds(2), serviceToken, 20, 8, modelUserId);
        InternalModelCallController controller = new InternalModelCallController(chatModelGateway, properties);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InternalAiExceptionHandler())
                .addFilters(new InternalAiTokenFilter(properties, objectMapper))
                .build();
    }

    private static String validRequestBody() {
        return """
                {"schemaVersion":1,"purpose":"KNOWLEDGE_CHAT","systemPrompt":"打分",
                 "userPrompt":"候选","outputFormat":"PROMPT_JSON","deadlineMs":2500,"callId":"call-auth-1"}
                """;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
