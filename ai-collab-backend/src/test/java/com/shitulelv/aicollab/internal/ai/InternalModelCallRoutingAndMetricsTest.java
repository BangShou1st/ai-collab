package com.shitulelv.aicollab.internal.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.RoutingChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProviderRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenVerifier;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 4.1 & 5.2：验证 /internal/ai/v1/model-calls 的调用归因路由（Ed25519 claim → 共享模型）、
 * 无可用身份时 503 fail-closed 与拆分指标标签。
 */
class InternalModelCallRoutingAndMetricsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void ed25519CallerClaimTakesPriorityOverSharedProvider() throws Exception {
        KeyPair kp = ed25519KeyPair();
        InternalTokenIssuer issuer = new InternalTokenIssuer(kp.getPrivate(), "k1", 300);
        SharedAiProviderRepository sharedRepo = mock(SharedAiProviderRepository.class);
        RoutingChatModelGateway gateway = mock(RoutingChatModelGateway.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        UUID claimCaller = UUID.randomUUID();
        UUID claimProject = UUID.randomUUID();
        when(sharedRepo.findAllPublished()).thenReturn(List.of(publishedProvider()));
        when(gateway.complete(any())).thenReturn(
                new ChatCompletionResult("{\"ok\":true}", "OPENAI_COMPATIBLE", "gpt-4o", 12, 8, 25));

        MockMvc mvc = buildMvc(kp, gateway, sharedRepo, metrics);
        String token = issuer.issueServiceTokenForCaller(claimCaller, claimProject);

        mvc.perform(post("/internal/ai/v1/model-calls")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("PLANNING", "call-ed25519")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("{\"ok\":true}"))
                .andExpect(jsonPath("$.modelAlias").value("gpt-4o"));

        ArgumentCaptor<ChatCompletionCommand> cmdCaptor = ArgumentCaptor.forClass(ChatCompletionCommand.class);
        verify(gateway).complete(cmdCaptor.capture());
        assertThat(cmdCaptor.getValue().callerUserId()).isEqualTo(claimCaller);
        assertThat(cmdCaptor.getValue().projectId()).isEqualTo(claimProject);
        assertThat(cmdCaptor.getValue().purpose()).isEqualTo(ModelPurpose.PLANNING);
        verify(sharedRepo, never()).findAllPublished();
        verify(gateway, never()).completeWithSnapshot(any(), any(), any(), any());

        Timer unified = metrics.find("internal.ai.model.duration")
                .tag("purpose", "PLANNING")
                .tag("provider", "OPENAI_COMPATIBLE")
                .tag("model", "gpt-4o")
                .tag("outcome", "success")
                .tag("caller_type", "ed25519_claim")
                .timer();
        assertThat(unified).isNotNull();
        assertThat(unified.count()).isEqualTo(1L);
        assertThat(metrics.find("rerank.model.duration").timers()).isEmpty();
    }

    @Test
    void sharedProviderFallbackExecutesCompleteWithSnapshotWhenClaimCallerAbsent() throws Exception {
        KeyPair kp = ed25519KeyPair();
        InternalTokenIssuer issuer = new InternalTokenIssuer(kp.getPrivate(), "k1", 300);
        SharedAiProviderRepository sharedRepo = mock(SharedAiProviderRepository.class);
        RoutingChatModelGateway gateway = mock(RoutingChatModelGateway.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        SharedAiProvider shared = publishedProvider();
        UUID projectId = UUID.randomUUID();
        when(sharedRepo.findAllPublished()).thenReturn(List.of(shared));
        when(gateway.completeWithSnapshot(any(), any(AiRequestMetadata.class), any(UserAiProvider.class), isNull()))
                .thenReturn(new ChatCompletionResult("{\"choice\":1}", "ANTHROPIC", "claude-sonnet", 10, 5, 19));

        MockMvc mvc = buildMvc(kp, gateway, sharedRepo, metrics);
        String token = issuer.issueServiceTokenForCaller(null, projectId);

        mvc.perform(post("/internal/ai/v1/model-calls")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("AGENT", "call-shared")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelAlias").value("claude-sonnet"));

        ArgumentCaptor<ChatCompletionCommand> cmdCaptor = ArgumentCaptor.forClass(ChatCompletionCommand.class);
        ArgumentCaptor<UserAiProvider> snapshotCaptor = ArgumentCaptor.forClass(UserAiProvider.class);
        verify(gateway).completeWithSnapshot(
                cmdCaptor.capture(), any(AiRequestMetadata.class), snapshotCaptor.capture(), isNull());
        assertThat(cmdCaptor.getValue().callerUserId()).isEqualTo(shared.publisherUserId());
        assertThat(cmdCaptor.getValue().projectId()).isEqualTo(projectId);
        assertThat(snapshotCaptor.getValue().id()).isEqualTo(shared.id());
        verify(gateway, never()).complete(any());

        Timer unified = metrics.find("internal.ai.model.duration")
                .tag("purpose", "AGENT")
                .tag("caller_type", "shared_provider")
                .timer();
        assertThat(unified).isNotNull();
        assertThat(unified.count()).isEqualTo(1L);
        assertThat(metrics.find("rerank.model.duration").timers()).isEmpty();
    }

    @Test
    void failsClosedWith503WhenNoClaimOrSharedProviderAvailable() throws Exception {
        KeyPair kp = ed25519KeyPair();
        InternalTokenIssuer issuer = new InternalTokenIssuer(kp.getPrivate(), "k1", 300);
        SharedAiProviderRepository sharedRepo = mock(SharedAiProviderRepository.class);
        ChatModelGateway gateway = mock(ChatModelGateway.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        when(sharedRepo.findAllPublished()).thenReturn(List.of());

        MockMvc mvc = buildMvc(kp, gateway, sharedRepo, metrics);

        mvc.perform(post("/internal/ai/v1/model-calls")
                        .header("Authorization", "Bearer " + issuer.issueServiceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("PLANNING", "call-missing")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));

        verify(gateway, never()).complete(any());
        assertThat(metrics.find("internal.ai.model.duration").timers()).isEmpty();
        assertThat(metrics.find("rerank.model.duration").timers()).isEmpty();
    }

    private static MockMvc buildMvc(
            KeyPair kp,
            ChatModelGateway gateway,
            SharedAiProviderRepository sharedRepo,
            SimpleMeterRegistry metrics) {
        InternalTokenVerifier verifier = new InternalTokenVerifier(Map.of("k1", kp.getPublic()), 60);
        InternalAiTokenFilter filter = new InternalAiTokenFilter(verifier, JSON);
        InternalModelCallController controller = new InternalModelCallController(
                gateway, metrics, sharedRepo);
        return MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .build();
    }

    private static KeyPair ed25519KeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static SharedAiProvider publishedProvider() {
        return new SharedAiProvider(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Shared Claude",
                ModelProviderType.ANTHROPIC,
                "https://api.anthropic.com",
                "/v1/messages",
                "encrypted-key-material",
                "claude-sonnet",
                true,
                SharedAiProvider.STATUS_PUBLISHED,
                0.2,
                4096,
                EnumSet.of(ModelCapability.CHAT),
                1,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    private static String requestJson(String purpose, String callId) {
        return """
                {
                  "schemaVersion": 1,
                  "purpose": "%s",
                  "systemPrompt": "system",
                  "userPrompt": "user",
                  "outputFormat": "PROMPT_JSON",
                  "deadlineMs": 3000,
                  "callId": "%s"
                }
                """.formatted(purpose, callId);
    }
}
