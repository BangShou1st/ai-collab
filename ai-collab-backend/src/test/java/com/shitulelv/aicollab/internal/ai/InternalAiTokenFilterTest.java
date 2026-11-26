package com.shitulelv.aicollab.internal.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部 AI 端点 Ed25519 鉴权（过滤器面）。
 *
 * <p>覆盖：合法短令牌、过期/scope/aud 错误、未知 kid、非 JWS 的任意字符串、缺失令牌 401、
 * 验签材料为空 503，以及"日志不含令牌"的卫生断言。</p>
 */
class InternalAiTokenFilterTest {

    private static final String ENDPOINT = "/internal/ai/v1/model-calls";
    private static final UUID CALLER_USER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String OPAQUE_TOKEN = "test-only-opaque-token";
    private static final long NOW = 1_762_900_100L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatModelGateway chatModelGateway;

    @BeforeEach
    void setUp() {
        chatModelGateway = mock(ChatModelGateway.class);
    }

    private void stubSuccess() {
        when(chatModelGateway.complete(any()))
                .thenReturn(new ChatCompletionResult("{\"ok\":true}", "prov", "model-x", 1, 1, 12L));
    }

    @Test
    void acceptsValidJwtWithCallerClaim() throws Exception {
        stubSuccess();
        KeyMaterial material = newMaterial();
        MockMvc mvc = mvc(verifier(material));
        String jwt = issuer(material, "k1", NOW).issueServiceTokenForCaller(CALLER_USER_ID, null);

        mvc.perform(post(ENDPOINT).header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1));
    }

    @Test
    void rejectsExpiredJwtScopeAndAudience() throws Exception {
        KeyMaterial material = newMaterial();
        MockMvc mvc = mvc(verifier(material));
        InternalTokenIssuer issuer = issuer(material, "k1", NOW);

        String expired = issuer.issueAt(List.of("ai-worker", "backend"), List.of("plan", "model-call"),
                NOW - 400, NOW - 120, "jti-expired", Map.of());
        String wrongScope = issuer.issueAt(List.of("ai-worker", "backend"), List.of("plan"),
                NOW, NOW + 300, "jti-scope", Map.of());
        String wrongAudience = issuer.issueAt(List.of("ai-worker"), List.of("plan", "model-call"),
                NOW, NOW + 300, "jti-aud", Map.of());

        for (String token : List.of(expired, wrongScope, wrongAudience)) {
            mvc.perform(post(ENDPOINT).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(chatModelGateway);
    }

    @Test
    void rejectsUnknownKidAndOpaqueToken() throws Exception {
        KeyMaterial material = newMaterial();
        KeyMaterial other = newMaterial();
        MockMvc mvc = mvc(verifier(material));
        String unknownKid = issuer(other, "k2", NOW).issueServiceToken();

        for (String token : List.of(unknownKid, OPAQUE_TOKEN)) {
            mvc.perform(post(ENDPOINT).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(chatModelGateway);
    }

    @Test
    void missingTokenIs401AndEmptyKeyRingIs503() throws Exception {
        MockMvc mvc = mvc(verifier(newMaterial()));
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                .andExpect(status().isUnauthorized());

        MockMvc failClosed = mvc(new InternalTokenVerifier(Map.of(), 60, CLOCK));
        failClosed.perform(post(ENDPOINT).header("Authorization", "Bearer " + OPAQUE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));
        verifyNoInteractions(chatModelGateway);
    }

    @Test
    void authFailureLogsReasonAndPathButNeverTheToken() throws Exception {
        KeyMaterial material = newMaterial();
        MockMvc mvc = mvc(verifier(material));
        String expired = issuer(material, "k1", NOW).issueAt(List.of("ai-worker", "backend"),
                List.of("plan", "model-call"), NOW - 400, NOW - 120, "jti-log", Map.of());

        Logger filterLogger = (Logger) LoggerFactory.getLogger(InternalAiTokenFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
        try {
            mvc.perform(post(ENDPOINT).header("Authorization", "Bearer " + expired)
                            .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                    .andExpect(status().isUnauthorized());
        } finally {
            filterLogger.detachAppender(appender);
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(logs).contains("reason=expired").contains(ENDPOINT);
        assertThat(logs).doesNotContain("eyJ").doesNotContain(expired);
    }

    private MockMvc mvc(InternalTokenVerifier verifier) {
        InternalModelCallController controller = new InternalModelCallController(chatModelGateway);
        InternalAiTokenFilter filter = new InternalAiTokenFilter(verifier, objectMapper);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InternalAiExceptionHandler())
                .addFilters(filter)
                .build();
    }

    private static InternalTokenVerifier verifier(KeyMaterial material) {
        return new InternalTokenVerifier(Map.of("k1", material.publicKey()), 60, CLOCK);
    }

    private static InternalTokenIssuer issuer(KeyMaterial material, String kid, long epochSecond) {
        return new InternalTokenIssuer(material.privateKey(), kid, 300,
                Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    private static String validRequestBody() {
        return """
                {"schemaVersion":1,"purpose":"KNOWLEDGE_CHAT","systemPrompt":"打分",
                 "userPrompt":"候选","outputFormat":"PROMPT_JSON","deadlineMs":2500,"callId":"call-filter-1"}
                """;
    }

    private static KeyMaterial newMaterial() {
        try {
            KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new KeyMaterial(keyPair.getPrivate(), keyPair.getPublic());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record KeyMaterial(PrivateKey privateKey, PublicKey publicKey) {
    }
}
