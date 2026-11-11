package com.shitulelv.aicollab.internal.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.internal.ai.token.Ed25519InternalTokenProvider;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenMode;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenProperties;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenProvider;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenVerifier;
import com.shitulelv.aicollab.internal.ai.token.StaticInternalTokenProvider;
import com.shitulelv.aicollab.retrieval.rerank.RerankMode;
import com.shitulelv.aicollab.retrieval.rerank.RerankProperties;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
 * 内部 AI 端点按模式鉴权（冻结 §2.2/§2.5 矩阵 #1–#13 的过滤器面）。
 *
 * <p>覆盖：static 回归（既有行为逐条保持）、ed25519 合法/过期/未知 kid/scope/aud、dual 同时
 * 接受两种令牌、401 与 503 的区分、以及"日志不含令牌"的卫生断言。</p>
 */
class InternalAiTokenFilterTest {

    private static final String ENDPOINT = "/internal/ai/v1/model-calls";
    private static final String MODEL_USER_ID = "99999999-9999-9999-9999-999999999999";
    private static final String STATIC_TOKEN = "test-only-static-token-w3s3";
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

    // ---------- #13 static 回归 ----------

    @Test
    void staticModeKeepsW3Behaviour() throws Exception {
        stubSuccess();
        RerankProperties rerank = rerank(STATIC_TOKEN);
        MockMvc mvc = mvc(rerank, InternalTokenProperties.defaults(),
                new StaticInternalTokenProvider(STATIC_TOKEN), InternalTokenVerifier.disabled(60));

        mvc.perform(post(ENDPOINT).header("Authorization", "Bearer " + STATIC_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1));

        mvc.perform(post(ENDPOINT).header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void staticModeMissingTokenIs401AndUnconfiguredIs503() throws Exception {
        RerankProperties rerank = rerank(STATIC_TOKEN);
        MockMvc mvc = mvc(rerank, InternalTokenProperties.defaults(),
                new StaticInternalTokenProvider(STATIC_TOKEN), InternalTokenVerifier.disabled(60));
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                .andExpect(status().isUnauthorized());

        RerankProperties unconfigured = rerank("");
        MockMvc failClosed = mvc(unconfigured, InternalTokenProperties.defaults(),
                new StaticInternalTokenProvider(""), InternalTokenVerifier.disabled(60));
        failClosed.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));
        verifyNoInteractions(chatModelGateway);
    }

    @Test
    void legacyConstructorKeepsStaticMode() throws Exception {
        stubSuccess();
        RerankProperties rerank = rerank(STATIC_TOKEN);
        InternalModelCallController controller = new InternalModelCallController(chatModelGateway, rerank);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InternalAiExceptionHandler())
                .addFilters(new InternalAiTokenFilter(rerank, objectMapper))
                .build();

        mvc.perform(post(ENDPOINT).header("Authorization", "Bearer " + STATIC_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                .andExpect(status().isOk());
    }

    // ---------- ed25519 ----------

    @Test
    void ed25519ModeAcceptsValidJwt() throws Exception {
        stubSuccess();
        KeyMaterial material = newMaterial();
        MockMvc mvc = ed25519Mvc(material, STATIC_TOKEN);
        String jwt = issuer(material, "k1", NOW).issueServiceToken();

        mvc.perform(post(ENDPOINT).header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1));
    }

    @Test
    void ed25519ModeRejectsExpiredJwtScopeAndAudience() throws Exception {
        KeyMaterial material = newMaterial();
        MockMvc mvc = ed25519Mvc(material, STATIC_TOKEN);
        InternalTokenIssuer issuer = issuer(material, "k1", NOW);

        String expired = issuer.issueAt(List.of("ai-worker", "backend"), List.of("rerank", "model-call"),
                NOW - 400, NOW - 120, "jti-expired", Map.of());
        String wrongScope = issuer.issueAt(List.of("ai-worker", "backend"), List.of("rerank"),
                NOW, NOW + 300, "jti-scope", Map.of());
        String wrongAudience = issuer.issueAt(List.of("ai-worker"), List.of("rerank", "model-call"),
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
    void ed25519ModeRejectsUnknownKidAndStaticToken() throws Exception {
        KeyMaterial material = newMaterial();
        KeyMaterial other = newMaterial();
        MockMvc mvc = ed25519Mvc(material, STATIC_TOKEN);
        String unknownKid = issuer(other, "k2", NOW).issueServiceToken();

        for (String token : List.of(unknownKid, STATIC_TOKEN)) {
            mvc.perform(post(ENDPOINT).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ---------- dual ----------

    @Test
    void dualModeAcceptsBothTokenKinds() throws Exception {
        stubSuccess();
        KeyMaterial material = newMaterial();
        MockMvc mvc = dualMvc(material, STATIC_TOKEN);
        String jwt = issuer(material, "k1", NOW).issueServiceToken();

        mvc.perform(post(ENDPOINT).header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                .andExpect(status().isOk());
        mvc.perform(post(ENDPOINT).header("Authorization", "Bearer " + STATIC_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                .andExpect(status().isOk());
        mvc.perform(post(ENDPOINT).header("Authorization", "Bearer neither-token")
                        .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dualModePrefersJwtButFallsBackToStatic() throws Exception {
        KeyMaterial material = newMaterial();
        MockMvc mvc = dualMvc(material, STATIC_TOKEN);
        // 过期 JWT 不等同于静态令牌：两条路径都失败 → 401。
        String expired = issuer(material, "k1", NOW).issueAt(List.of("ai-worker", "backend"),
                List.of("rerank", "model-call"), NOW - 400, NOW - 120, "jti-dual", Map.of());
        mvc.perform(post(ENDPOINT).header("Authorization", "Bearer " + expired)
                        .contentType(MediaType.APPLICATION_JSON).content(validRequestBody()))
                .andExpect(status().isUnauthorized());
    }

    // ---------- #12 日志卫生 ----------

    @Test
    void authFailureLogsReasonAndPathButNeverTheToken() throws Exception {
        KeyMaterial material = newMaterial();
        MockMvc mvc = ed25519Mvc(material, STATIC_TOKEN);
        String expired = issuer(material, "k1", NOW).issueAt(List.of("ai-worker", "backend"),
                List.of("rerank", "model-call"), NOW - 400, NOW - 120, "jti-log", Map.of());

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

    // ---------- helpers ----------

    private MockMvc ed25519Mvc(KeyMaterial material, String staticToken) {
        RerankProperties rerank = rerank(staticToken);
        InternalTokenProperties properties = tokenProperties(InternalTokenMode.ED25519, material);
        InternalTokenProvider provider = new Ed25519InternalTokenProvider(issuer(material, "k1", NOW));
        InternalTokenVerifier verifier = new InternalTokenVerifier(
                Map.of("k1", material.publicKey()), 60, CLOCK);
        return mvc(rerank, properties, provider, verifier);
    }

    private MockMvc dualMvc(KeyMaterial material, String staticToken) {
        RerankProperties rerank = rerank(staticToken);
        InternalTokenProperties properties = tokenProperties(InternalTokenMode.DUAL, material);
        InternalTokenProvider provider = new Ed25519InternalTokenProvider(issuer(material, "k1", NOW));
        InternalTokenVerifier verifier = new InternalTokenVerifier(
                Map.of("k1", material.publicKey()), 60, CLOCK);
        return mvc(rerank, properties, provider, verifier);
    }

    private MockMvc mvc(RerankProperties rerank, InternalTokenProperties tokenProperties,
            InternalTokenProvider provider, InternalTokenVerifier verifier) {
        InternalModelCallController controller = new InternalModelCallController(chatModelGateway, rerank);
        InternalAiTokenFilter filter = new InternalAiTokenFilter(rerank, tokenProperties, verifier,
                objectMapper);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InternalAiExceptionHandler())
                .addFilters(filter)
                .build();
    }

    private static InternalTokenIssuer issuer(KeyMaterial material, String kid, long epochSecond) {
        return new InternalTokenIssuer(material.privateKey(), kid, 300,
                Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    private static InternalTokenProperties tokenProperties(InternalTokenMode mode, KeyMaterial material) {
        return new InternalTokenProperties(mode, material.privateKeyBase64(), "k1", 300, 60, "");
    }

    private static RerankProperties rerank(String serviceToken) {
        return new RerankProperties(RerankMode.JAVA, "http://127.0.0.1:1", 3000,
                Duration.ofSeconds(2), serviceToken, 20, 8, 1.0, MODEL_USER_ID);
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

        String privateKeyBase64() {
            return Base64.getEncoder().encodeToString(privateKey.getEncoded());
        }
    }
}
