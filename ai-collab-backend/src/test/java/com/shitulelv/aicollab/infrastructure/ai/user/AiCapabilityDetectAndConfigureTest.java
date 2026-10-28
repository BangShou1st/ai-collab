package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnGateway;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「检测并配置」编排：探针真实执行、PASS 写回、失败/临时故障分类、
 * CAS 写回竞争与并发互斥。对应 P7-PROVIDER-ONBOARDING-01
 * 验收场景 T01/T02/T03/T05/T06/T07（服务层部分）。
 */
class AiCapabilityDetectAndConfigureTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private UserAiProvider provider(EnumSet<ModelCapability> capabilities, OffsetDateTime updatedAt) {
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "c",
                ModelProviderType.OPENAI_COMPATIBLE, "https://api.example.com", "/v1/chat/completions",
                "enc", "m", true, 0.2, 1200, capabilities, false,
                OffsetDateTime.now(), updatedAt, null);
    }

    private ChatCompletionResult completion() {
        return new ChatCompletionResult("ok", "OPENAI_COMPATIBLE", "m", null, null, 42L);
    }

    private ModelTurnResult toolCallResult() {
        return new ModelTurnResult("", List.of(new ModelToolCall(
                "t1", "capability_probe", JSON.createObjectNode().put("value", "probe"), null)),
                null, null, "OPENAI_COMPATIBLE", "m", 7L, null);
    }

    /** 流式探针的 mock 网关回调：正常吐 token 并完成。 */
    private void stubStreamingSuccess(ChatModelGateway chat) {
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<String> onToken = invocation.getArgument(1);
            java.util.function.Consumer<ChatCompletionResult> onDone = invocation.getArgument(2);
            onToken.accept("probe");
            onDone.accept(completion());
            return null;
        }).when(chat).completeStream(any(), any(), any(), any());
    }

    /** 流式探针的 mock 网关回调：抛出给定异常。 */
    private void stubStreamingFailure(ChatModelGateway chat, Exception failure) {
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<Exception> onError = invocation.getArgument(3);
            onError.accept(failure);
            return null;
        }).when(chat).completeStream(any(), any(), any(), any());
    }

    private AiCapabilityProbeService service(UserAiProviderService providers, ChatModelGateway chat,
            ModelTurnGateway turns, UserAiProviderRepository repository) {
        return new AiCapabilityProbeService(providers, chat, turns, JSON, null,
                mock(UserAiProviderProbeResultRepository.class), repository);
    }

    @Test void fullSuitePassesAndWritesBackUndeclaredNativeTools() {
        // T01：Provider 仅声明 CHAT，原生工具探针真实执行并通过 → 自动启用 NATIVE_TOOLS
        OffsetDateTime version = OffsetDateTime.now();
        UserAiProvider snapshot = provider(EnumSet.of(ModelCapability.CHAT), version);
        UserAiProviderService providers = mock(UserAiProviderService.class);
        when(providers.requireRuntimeConfiguration(any(), any())).thenReturn(snapshot);
        ChatModelGateway chat = mock(ChatModelGateway.class);
        when(chat.complete(any())).thenReturn(completion());
        stubStreamingSuccess(chat);
        ModelTurnGateway turns = mock(ModelTurnGateway.class);
        when(turns.turn(any())).thenReturn(toolCallResult());
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        OffsetDateTime newVersion = version.plusSeconds(1);
        when(repository.addCapabilitiesIfVersionMatches(eq(snapshot.id()), eq(snapshot.userId()),
                anySet(), eq(version))).thenReturn(Optional.of(newVersion));

        var result = service(providers, chat, turns, repository)
                .detectAndConfigure(snapshot.userId(), snapshot.id(), false, true);

        assertThat(result.steps()).hasSize(3);
        assertThat(result.steps()).allSatisfy(step ->
                assertThat(step.status()).isEqualTo(UserAiProviderProbeResultRepository.PASS));
        assertThat(result.writeBack().applied()).isTrue();
        // CAS 写回包含 NATIVE_TOOLS；规划探针未参与时不误加 STRUCTURED_OUTPUT
        ArgumentCaptor<java.util.Set<ModelCapability>> added =
                ArgumentCaptor.forClass(java.util.Set.class);
        verify(repository).addCapabilitiesIfVersionMatches(eq(snapshot.id()), eq(snapshot.userId()),
                added.capture(), eq(version));
        assertThat(added.getValue()).containsExactlyInAnyOrder(
                ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.NATIVE_TOOLS);
        // 写回后探针结论版本锚点迁移到新版本（T06：刚通过不立即过期）
        verify(repository).rebaseProbeTestedVersion(eq(snapshot.id()), eq(version), eq(newVersion), any());
        assertThat(result.generationRequests()).isEqualTo(3);
    }

    @Test void nativeToolsRealFailureDoesNotWriteBack() {
        // T02：模型真实不支持工具调用 → 结论 FAIL、不写回 NATIVE_TOOLS、不中止已通过的流式
        OffsetDateTime version = OffsetDateTime.now();
        UserAiProvider snapshot = provider(EnumSet.of(ModelCapability.CHAT), version);
        UserAiProviderService providers = mock(UserAiProviderService.class);
        when(providers.requireRuntimeConfiguration(any(), any())).thenReturn(snapshot);
        ChatModelGateway chat = mock(ChatModelGateway.class);
        when(chat.complete(any())).thenReturn(completion());
        stubStreamingSuccess(chat);
        ModelTurnGateway turns = mock(ModelTurnGateway.class);
        when(turns.turn(any())).thenThrow(new BusinessException(
                ErrorCode.AI_PROVIDER_REQUEST_REJECTED, "模型服务拒绝请求：tool use is not supported"));
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);

        var result = service(providers, chat, turns, repository)
                .detectAndConfigure(snapshot.userId(), snapshot.id(), false, true);

        assertThat(result.steps().stream()
                .filter(step -> step.mode() == AiCapabilityProbeService.Mode.NATIVE_TOOLS)
                .findFirst().orElseThrow().status())
                .isEqualTo(UserAiProviderProbeResultRepository.FAIL);
        // CONNECTIVITY/STREAMING 已 PASS：写回仍执行，但集合不含 NATIVE_TOOLS
        ArgumentCaptor<java.util.Set<ModelCapability>> added =
                ArgumentCaptor.forClass(java.util.Set.class);
        verify(repository).addCapabilitiesIfVersionMatches(eq(snapshot.id()), eq(snapshot.userId()),
                added.capture(), eq(version));
        assertThat(added.getValue()).containsExactlyInAnyOrder(ModelCapability.CHAT, ModelCapability.STREAMING);
    }

    @Test void transientFailuresAreInconclusiveAndDoNotAbortSuite() {
        // T03：连接 429、流式超时 → INCONCLUSIVE，不误报“不支持”，不无界重试（各恰好一次）
        OffsetDateTime version = OffsetDateTime.now();
        UserAiProvider snapshot = provider(EnumSet.of(ModelCapability.CHAT), version);
        UserAiProviderService providers = mock(UserAiProviderService.class);
        when(providers.requireRuntimeConfiguration(any(), any())).thenReturn(snapshot);
        ChatModelGateway chat = mock(ChatModelGateway.class);
        when(chat.complete(any())).thenThrow(new BusinessException(
                ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED, "模型服务返回 HTTP 429"));
        stubStreamingFailure(chat, new BusinessException(
                ErrorCode.AI_MODEL_TIMEOUT, "模型请求超时"));
        ModelTurnGateway turns = mock(ModelTurnGateway.class);
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);

        var result = service(providers, chat, turns, repository)
                .detectAndConfigure(snapshot.userId(), snapshot.id(), false, false);

        assertThat(result.steps()).allSatisfy(step ->
                assertThat(step.status()).isEqualTo(UserAiProviderProbeResultRepository.INCONCLUSIVE));
        verify(chat, times(1)).complete(any()); // 无重试：连接探针恰好一次
        verify(chat, times(1)).completeStream(any(), any(), any(), any()); // 流式探针恰好一次
        verify(repository, org.mockito.Mockito.never())
                .addCapabilitiesIfVersionMatches(any(), any(), anySet(), any());
    }

    @Test void credentialChangeDuringProbeRejectsWriteBackWithReason() {
        // T05：检测期间配置变更（版本不匹配）→ CAS 失败，明确报错且不覆盖新配置
        OffsetDateTime version = OffsetDateTime.now();
        UserAiProvider snapshot = provider(EnumSet.of(ModelCapability.CHAT), version);
        UserAiProviderService providers = mock(UserAiProviderService.class);
        when(providers.requireRuntimeConfiguration(any(), any())).thenReturn(snapshot);
        ChatModelGateway chat = mock(ChatModelGateway.class);
        when(chat.complete(any())).thenReturn(completion());
        stubStreamingSuccess(chat);
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        when(repository.addCapabilitiesIfVersionMatches(any(), any(), anySet(), any()))
                .thenReturn(Optional.empty());

        var result = service(providers, chat, turns(), repository)
                .detectAndConfigure(snapshot.userId(), snapshot.id(), false, false);

        assertThat(result.writeBack().applied()).isFalse();
        assertThat(result.writeBack().reason()).contains("配置已变更");
    }

    @Test void concurrentDetectRunsAreRejectedByBackendLock() throws Exception {
        // T07（服务层）：同一 Provider 并发第二个检测任务被后端互斥拒绝（跨线程，模拟双窗口）
        OffsetDateTime version = OffsetDateTime.now();
        UserAiProvider snapshot = provider(EnumSet.of(ModelCapability.CHAT), version);
        UserAiProviderService providers = mock(UserAiProviderService.class);
        when(providers.requireRuntimeConfiguration(any(), any())).thenReturn(snapshot);
        java.util.concurrent.CountDownLatch probeStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseProbe = new java.util.concurrent.CountDownLatch(1);
        ChatModelGateway chat = mock(ChatModelGateway.class);
        when(chat.complete(any())).thenAnswer(invocation -> {
            probeStarted.countDown();
            releaseProbe.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return completion();
        });
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        var probeService = service(providers, chat, turns(), repository);

        var first = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> probeService.detectAndConfigure(snapshot.userId(), snapshot.id(), false, false));
        assertThat(probeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> probeService.detectAndConfigure(snapshot.userId(), snapshot.id(), false, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有能力检测任务在进行中");
        releaseProbe.countDown();
        assertThat(first.join().steps().getFirst().status())
                .isEqualTo(UserAiProviderProbeResultRepository.PASS);
    }

    @Test void writeBackSkipsCasWhenAllProbedCapabilitiesAlreadyDeclared() {
        // 探针通过的能力已全部声明时不推进配置版本：同轮 INCONCLUSIVE 结论不被误标 STALE
        OffsetDateTime version = OffsetDateTime.now();
        UserAiProvider snapshot = provider(EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING), version);
        UserAiProviderService providers = mock(UserAiProviderService.class);
        when(providers.requireRuntimeConfiguration(any(), any())).thenReturn(snapshot);
        ChatModelGateway chat = mock(ChatModelGateway.class);
        when(chat.complete(any())).thenReturn(completion());
        stubStreamingSuccess(chat);
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);

        var result = service(providers, chat, turns(), repository)
                .detectAndConfigure(snapshot.userId(), snapshot.id(), false, false);

        assertThat(result.steps()).allSatisfy(step ->
                assertThat(step.status()).isEqualTo(UserAiProviderProbeResultRepository.PASS));
        assertThat(result.writeBack().applied()).isTrue();
        verify(repository, org.mockito.Mockito.never())
                .addCapabilitiesIfVersionMatches(any(), any(), anySet(), any());
        verify(repository, org.mockito.Mockito.never()).rebaseProbeTestedVersion(any(), any(), any(), any());
    }

    @Test void staleLockIsReleasedAfterFailure() {
        // 失败后锁必须释放：下一次检测可再次发起（无无限重试、无死锁）
        OffsetDateTime version = OffsetDateTime.now();
        UserAiProvider snapshot = provider(EnumSet.of(ModelCapability.CHAT), version);
        UserAiProviderService providers = mock(UserAiProviderService.class);
        when(providers.requireRuntimeConfiguration(any(), any())).thenReturn(snapshot);
        ChatModelGateway chat = mock(ChatModelGateway.class);
        when(chat.complete(any())).thenThrow(new BusinessException(ErrorCode.AI_MODEL_TIMEOUT));
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        var probeService = service(providers, chat, turns(), repository);

        var first = probeService.detectAndConfigure(snapshot.userId(), snapshot.id(), false, false);
        assertThat(first.steps().getFirst().status()).isEqualTo(UserAiProviderProbeResultRepository.INCONCLUSIVE);
        var second = probeService.detectAndConfigure(snapshot.userId(), snapshot.id(), false, false);
        assertThat(second.steps().getFirst().status()).isEqualTo(UserAiProviderProbeResultRepository.INCONCLUSIVE);
    }

    private ModelTurnGateway turns() {
        return mock(ModelTurnGateway.class);
    }
}
