package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 探针结果落库、失败分类与 STALE 判定。
 */
class AiCapabilityProbeResultTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private UserAiProvider provider(OffsetDateTime updatedAt) {
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "c",
                ModelProviderType.OPENAI_COMPATIBLE, "https://api.example.com", "/v1/chat/completions",
                "enc", "m", true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING), false,
                OffsetDateTime.now(), updatedAt, null);
    }

    private AiCapabilityProbeService probe(UserAiProviderRepository providers,
            ChatModelGateway chat, UserAiProviderProbeResultRepository results) {
        var service = mock(UserAiProviderService.class);
        when(service.requireRuntimeConfiguration(any(), any())).thenAnswer(i -> provider(OffsetDateTime.now()));
        return new AiCapabilityProbeService(service, chat, mock(ModelTurnGateway.class), JSON, null, results);
    }

    private ChatCompletionResult completion() {
        return new ChatCompletionResult("ok", "OPENAI_COMPATIBLE", "m", null, null, 42L);
    }

    @Test void passingProbePersistsPassResult() {
        var repo = mock(UserAiProviderRepository.class);
        var chat = mock(ChatModelGateway.class);
        when(chat.complete(any())).thenReturn(completion());
        var results = mock(UserAiProviderProbeResultRepository.class);
        var provider = provider(OffsetDateTime.now());
        var service = probeWithProvider(provider, chat, results);
        service.test(provider.userId(), provider.id(), AiCapabilityProbeService.Mode.CONNECTIVITY);
        verify(results).upsert(org.mockito.ArgumentMatchers.argThat(row ->
                UserAiProviderProbeResultRepository.PASS.equals(row.status())
                        && AiCapabilityProbeService.Mode.CONNECTIVITY.name().equals(row.mode())));
    }

    private AiCapabilityProbeService probeWithProvider(UserAiProvider provider,
            ChatModelGateway chat, UserAiProviderProbeResultRepository results) {
        var service = mock(UserAiProviderService.class);
        when(service.requireRuntimeConfiguration(any(), any())).thenReturn(provider);
        return new AiCapabilityProbeService(service, chat, mock(ModelTurnGateway.class), JSON, null, results);
    }

    @Test void transientFailureIsInconclusiveWhileCredentialFailureIsFail() {
        assertThat(AiCapabilityProbeService.classify(
                new BusinessException(ErrorCode.AI_MODEL_TIMEOUT)))
                .isEqualTo(UserAiProviderProbeResultRepository.INCONCLUSIVE);
        assertThat(AiCapabilityProbeService.classify(
                new BusinessException(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED)))
                .isEqualTo(UserAiProviderProbeResultRepository.INCONCLUSIVE);
        assertThat(AiCapabilityProbeService.classify(
                new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "模型服务返回 HTTP 503")))
                .isEqualTo(UserAiProviderProbeResultRepository.INCONCLUSIVE);
        assertThat(AiCapabilityProbeService.classify(
                new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID, "HTTP 401")))
                .isEqualTo(UserAiProviderProbeResultRepository.FAIL);
        assertThat(AiCapabilityProbeService.classify(
                new BusinessException(ErrorCode.AI_PROVIDER_NOT_FOUND)))
                .isEqualTo(UserAiProviderProbeResultRepository.FAIL);
        assertThat(AiCapabilityProbeService.classify(
                new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE, "所选能力测试未满足输出契约")))
                .isEqualTo(UserAiProviderProbeResultRepository.FAIL);
    }

    @Test void undeclaredCapabilityIsNotSupportedNotFail() {
        // ModelCapabilityPolicy / turn 网关对未声明能力的拒绝是确定性的“不支持”，
        // 不是临时故障，也不是模型能力失败。
        assertThat(AiCapabilityProbeService.classify(
                new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "当前模型配置不支持 STREAMING")))
                .isEqualTo(UserAiProviderProbeResultRepository.NOT_SUPPORTED);
        assertThat(AiCapabilityProbeService.classify(
                new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "Agent 模型必须支持 NATIVE_TOOLS")))
                .isEqualTo(UserAiProviderProbeResultRepository.NOT_SUPPORTED);
    }

    @Test void failingProbePersistsClassifiedResultAndRethrows() {
        var chat = mock(ChatModelGateway.class);
        when(chat.complete(any())).thenThrow(
                new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID, "模型 API Key 无效或无权访问 (HTTP 401)"));
        var results = mock(UserAiProviderProbeResultRepository.class);
        var provider = provider(OffsetDateTime.now());
        var service = probeWithProvider(provider, chat, results);
        assertThatThrownBy(() -> service.test(provider.userId(), provider.id(),
                AiCapabilityProbeService.Mode.CONNECTIVITY))
                .isInstanceOf(BusinessException.class);
        verify(results).upsert(org.mockito.ArgumentMatchers.argThat(row ->
                UserAiProviderProbeResultRepository.FAIL.equals(row.status())
                        && ErrorCode.AI_MODEL_CREDENTIAL_INVALID.name().equals(row.errorCode())));
    }

    @Test void resultsAreStaleWhenConfigChangedAfterTest() {
        var testedAt = OffsetDateTime.now().minusMinutes(5);
        var provider = provider(testedAt.plusMinutes(1)); // 配置在测试之后变更
        var service = mock(UserAiProviderService.class);
        when(service.requireRuntimeConfiguration(any(), any())).thenReturn(provider);
        var results = mock(UserAiProviderProbeResultRepository.class);
        when(results.findAllByProviderId(provider.id())).thenReturn(List.of(
                new UserAiProviderProbeResultRepository.ProbeResultRow(provider.id(), "STREAMING",
                        UserAiProviderProbeResultRepository.PASS, "m", null, null, 120L,
                        testedAt, testedAt)));
        var views = new AiCapabilityProbeService(service, mock(ChatModelGateway.class),
                mock(ModelTurnGateway.class), JSON, null, results)
                .results(provider.userId(), provider.id());
        assertThat(views).hasSize(1);
        assertThat(views.getFirst().status()).isEqualTo("PASS");
        assertThat(views.getFirst().stale()).isTrue();
    }

    @Test void resultsAreCurrentWhenConfigUnchanged() {
        var testedAt = OffsetDateTime.now().minusMinutes(5);
        var provider = provider(testedAt);
        var service = mock(UserAiProviderService.class);
        when(service.requireRuntimeConfiguration(any(), any())).thenReturn(provider);
        var results = mock(UserAiProviderProbeResultRepository.class);
        when(results.findAllByProviderId(provider.id())).thenReturn(List.of(
                new UserAiProviderProbeResultRepository.ProbeResultRow(provider.id(), "STREAMING",
                        UserAiProviderProbeResultRepository.PASS, "m", null, null, 120L,
                        provider.updatedAt(), testedAt)));
        var views = new AiCapabilityProbeService(service, mock(ChatModelGateway.class),
                mock(ModelTurnGateway.class), JSON, null, results)
                .results(provider.userId(), provider.id());
        assertThat(views.getFirst().stale()).isFalse();
    }

    @Test void persistenceFailureDoesNotMaskProbeOutcome() {
        // 落库失败不影响探针结论：调用方仍拿到真实结果。
        var chat = mock(ChatModelGateway.class);
        when(chat.complete(any())).thenReturn(completion());
        var results = mock(UserAiProviderProbeResultRepository.class);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(results).upsert(any());
        var provider = provider(OffsetDateTime.now());
        var service = probeWithProvider(provider, chat, results);
        var evidence = service.test(provider.userId(), provider.id(),
                AiCapabilityProbeService.Mode.CONNECTIVITY);
        assertThat(evidence.verified()).isTrue();
    }
}
