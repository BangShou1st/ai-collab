package com.shitulelv.aicollab.infrastructure.ai.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderAdapter;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.shared.api.AdminAiProviderTestView;
import com.shitulelv.aicollab.infrastructure.ai.shared.api.AdminSharedAiProviderView;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderProbeResultRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderRequest;
import com.shitulelv.aicollab.notification.application.service.AdminNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D4 最小发布准入：多次采样、P50/P95、超时/失败记录、配置版本绑定与发布拒绝。
 */
@ExtendWith(MockitoExtension.class)
class ProviderPublishAdmissionTest {

    @Mock private UserAiProviderRepository userProviderRepository;
    @Mock private SharedAiProviderRepository sharedRepository;
    @Mock private ModelSecretCipher secrets;
    @Mock private OutboundEndpointPolicy endpoints;
    @Mock private AdminNotificationService notificationService;
    @Mock private UserAiProviderProbeResultRepository probeResults;

    private final ObjectMapper json = new ObjectMapper();
    private final UUID adminId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private StubAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StubAdapter();
    }

    @Test
    @DisplayName("正常通过：记录三次样本的 P50/P95、零超时零失败并绑定当前配置版本")
    void passingSamplesPersistLatencyAndCurrentVersion() throws Exception {
        UserAiProvider source = provider(7);
        when(userProviderRepository.findByIdAndUserId(sourceId, adminId))
                .thenReturn(Optional.of(source));
        adapter.success(5);
        adapter.success(10);
        adapter.success(15);

        AdminAiProviderTestView view = service(1_000).test(adminId, sourceId);

        assertThat(view.content()).isEqualTo("连接成功");
        assertThat(view.admission().accepted()).isTrue();
        assertThat(view.admission().status()).isEqualTo(ProviderAdmissionSnapshot.STATUS_PASS);
        assertThat(view.admission().samples()).isEqualTo(3);
        assertThat(view.admission().p50Ms()).isGreaterThanOrEqualTo(5);
        assertThat(view.admission().p95Ms()).isGreaterThanOrEqualTo(view.admission().p50Ms());
        assertThat(view.admission().timeoutCount()).isZero();
        assertThat(view.admission().failureCount()).isZero();
        assertThat(view.admission().configVersion()).isEqualTo(7);

        UserAiProviderProbeResultRepository.ProbeResultRow row = capturedAdmission();
        assertThat(row.mode()).isEqualTo(ProviderAdmissionSnapshot.MODE);
        assertThat(row.status()).isEqualTo(ProviderAdmissionSnapshot.STATUS_PASS);
        assertThat(row.testedConfigVersion()).isEqualTo(7L);
        assertThat(row.latencyMs()).isEqualTo(view.admission().p95Ms());
        JsonNode detail = json.readTree(row.detail());
        assertThat(detail.path("samples").asInt()).isEqualTo(3);
        assertThat(detail.path("p50Ms").asLong()).isPositive();
        assertThat(detail.path("p95Ms").asLong())
                .isGreaterThanOrEqualTo(detail.path("p50Ms").asLong());
        assertThat(detail.path("configVersion").asLong()).isEqualTo(7);
    }

    @Test
    @DisplayName("任一样本超时：记录超时数并拒绝发布")
    void timeoutSampleIsRecordedAndPublishRejected() {
        UserAiProvider source = provider(7);
        when(userProviderRepository.findByIdAndUserId(sourceId, adminId))
                .thenReturn(Optional.of(source));
        adapter.success(1);
        adapter.timeout(1);
        adapter.success(1);

        assertThatThrownBy(() -> service(1_000).test(adminId, sourceId))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.AI_MODEL_TIMEOUT));

        UserAiProviderProbeResultRepository.ProbeResultRow row = capturedAdmission();
        assertThat(row.status()).isEqualTo(ProviderAdmissionSnapshot.STATUS_INCONCLUSIVE);
        JsonNode detail = parse(row);
        assertThat(detail.path("timeoutCount").asInt()).isEqualTo(1);
        assertThat(detail.path("failureCount").asInt()).isEqualTo(1);

        when(probeResults.find(sourceId, ProviderAdmissionSnapshot.MODE))
                .thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service(1_000).publishShared(adminId, sourceId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("准入未通过");
    }

    @Test
    @DisplayName("非超时失败同样计数；部分成功不得洗成发布准入通过")
    void mixedFailureIsCountedAndPublishRejected() {
        UserAiProvider source = provider(7);
        when(userProviderRepository.findByIdAndUserId(sourceId, adminId))
                .thenReturn(Optional.of(source));
        adapter.success(1);
        adapter.failure(new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "合成失败"));
        adapter.success(1);

        assertThatThrownBy(() -> service(1_000).test(adminId, sourceId))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_ERROR));

        UserAiProviderProbeResultRepository.ProbeResultRow row = capturedAdmission();
        assertThat(row.status()).isEqualTo(ProviderAdmissionSnapshot.STATUS_FAIL);
        JsonNode detail = parse(row);
        assertThat(detail.path("failureCount").asInt()).isEqualTo(1);
        assertThat(detail.path("timeoutCount").asInt()).isZero();

        when(probeResults.find(sourceId, ProviderAdmissionSnapshot.MODE))
                .thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service(1_000).publishShared(adminId, sourceId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("准入未通过");
    }

    @Test
    @DisplayName("单次成功不能冒充稳定：三次采样中两次超时仍拒绝发布")
    void singleSuccessCannotSatisfyMultiSampleAdmission() {
        UserAiProvider source = provider(7);
        when(userProviderRepository.findByIdAndUserId(sourceId, adminId))
                .thenReturn(Optional.of(source));
        adapter.success(1);
        adapter.timeout(1);
        adapter.timeout(1);

        assertThatThrownBy(() -> service(1_000).test(adminId, sourceId))
                .isInstanceOf(BusinessException.class);

        JsonNode detail = parse(capturedAdmission());
        assertThat(detail.path("samples").asInt()).isEqualTo(3);
        assertThat(detail.path("timeoutCount").asInt()).isEqualTo(2);
        assertThat(detail.path("failureCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("P95 超过阈值：测试失败且持久化 FAIL，发布被拒绝")
    void p95OverLimitRejectsTestAndPublish() {
        UserAiProvider source = provider(7);
        when(userProviderRepository.findByIdAndUserId(sourceId, adminId))
                .thenReturn(Optional.of(source));
        adapter.success(10);
        adapter.success(10);
        adapter.success(10);

        assertThatThrownBy(() -> service(1).test(adminId, sourceId))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getMessage()).contains("延迟准入未通过"));

        UserAiProviderProbeResultRepository.ProbeResultRow row = capturedAdmission();
        assertThat(row.status()).isEqualTo(ProviderAdmissionSnapshot.STATUS_FAIL);
        assertThat(parse(row).path("p95Ms").asLong()).isGreaterThan(1);

        when(probeResults.find(sourceId, ProviderAdmissionSnapshot.MODE))
                .thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service(1).publishShared(adminId, sourceId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("准入未通过");
    }

    @Test
    @DisplayName("配置版本推进后旧准入结果过期，发布被拒绝")
    void staleConfigVersionRejectsPublish() {
        UserAiProvider source = provider(8);
        when(userProviderRepository.findByIdAndUserId(sourceId, adminId))
                .thenReturn(Optional.of(source));
        when(probeResults.find(sourceId, ProviderAdmissionSnapshot.MODE))
                .thenReturn(Optional.of(admissionRow(7, ProviderAdmissionSnapshot.STATUS_PASS, 20, 0, 0)));

        assertThatThrownBy(() -> service(1_000).publishShared(adminId, sourceId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("过期");

        verify(sharedRepository, never()).saveOrUpdate(any());
    }

    @Test
    @DisplayName("没有准入记录时发布被拒绝")
    void missingAdmissionRejectsPublish() {
        UserAiProvider source = provider(7);
        when(userProviderRepository.findByIdAndUserId(sourceId, adminId))
                .thenReturn(Optional.of(source));
        when(probeResults.find(sourceId, ProviderAdmissionSnapshot.MODE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(1_000).publishShared(adminId, sourceId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须完成多次连接测试");

        verify(sharedRepository, never()).saveOrUpdate(any());
    }

    @Test
    @DisplayName("准入结果为 PASS 且指标达标时允许发布")
    void passingAdmissionAllowsPublish() {
        UserAiProvider source = provider(7);
        when(userProviderRepository.findByIdAndUserId(sourceId, adminId))
                .thenReturn(Optional.of(source));
        when(probeResults.find(sourceId, ProviderAdmissionSnapshot.MODE))
                .thenReturn(Optional.of(admissionRow(
                        7, ProviderAdmissionSnapshot.STATUS_PASS, 30, 0, 0)));
        when(sharedRepository.findBySourceProviderId(sourceId)).thenReturn(Optional.empty());
        SharedAiProvider published = new SharedAiProvider(
                UUID.randomUUID(), sourceId, adminId, source.name(), source.providerType(),
                source.baseUrl(), source.apiPath(), source.encryptedApiKey(), source.modelName(),
                true, SharedAiProvider.STATUS_PUBLISHED, source.temperature(), source.maxOutputTokens(),
                source.capabilities(), 0, OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
        when(sharedRepository.saveOrUpdate(any())).thenReturn(published);

        AdminSharedAiProviderView view = service(1_000).publishShared(adminId, sourceId);

        assertThat(view.isShared()).isTrue();
        assertThat(view.sharedStatus()).isEqualTo(SharedAiProvider.STATUS_PUBLISHED);
        verify(sharedRepository).saveOrUpdate(any());
    }

    @Test
    @DisplayName("准入摘要持久化失败时 fail closed，不返回测试成功")
    void admissionPersistenceFailurePreventsSuccess() {
        UserAiProvider source = provider(7);
        when(userProviderRepository.findByIdAndUserId(sourceId, adminId))
                .thenReturn(Optional.of(source));
        adapter.success(1);
        adapter.success(1);
        adapter.success(1);
        doThrow(new IllegalStateException("db down")).when(probeResults).upsert(any());

        assertThatThrownBy(() -> service(1_000).test(adminId, sourceId))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("仅显示名变化不推进配置版本，已共享项保持 PUBLISHED")
    void displayNameOnlyUpdateKeepsPublishedShared() {
        UserAiProvider source = provider(7);
        when(userProviderRepository.findByIdAndUserId(sourceId, adminId))
                .thenReturn(Optional.of(source));
        UserAiProvider renamed = providerWith("Renamed-Fixture", "fixture-chat", 7);
        when(userProviderRepository.save(any())).thenReturn(renamed);
        SharedAiProvider previous = shared(SharedAiProvider.STATUS_PUBLISHED, true);
        when(sharedRepository.findBySourceProviderId(sourceId)).thenReturn(Optional.of(previous));
        when(sharedRepository.saveOrUpdate(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AdminSharedAiProviderView view = service(1_000).update(adminId, sourceId,
                request("Renamed-Fixture", "fixture-chat"));

        assertThat(view.isShared()).isTrue();
        assertThat(view.sharedStatus()).isEqualTo(SharedAiProvider.STATUS_PUBLISHED);
    }

    @Test
    @DisplayName("请求参数变化推进版本时自动撤销共享，编辑不能绕过发布准入")
    void requestConfigChangeRevokesPublishedSharedUntilReAdmission() {
        UserAiProvider source = provider(7);
        when(userProviderRepository.findByIdAndUserId(sourceId, adminId))
                .thenReturn(Optional.of(source));
        UserAiProvider changed = providerWith("Admission-Fixture", "fixture-chat-v2", 8);
        when(userProviderRepository.save(any())).thenReturn(changed);
        SharedAiProvider previous = shared(SharedAiProvider.STATUS_PUBLISHED, true);
        when(sharedRepository.findBySourceProviderId(sourceId)).thenReturn(Optional.of(previous));
        when(sharedRepository.saveOrUpdate(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AdminSharedAiProviderView view = service(1_000).update(adminId, sourceId,
                request("Admission-Fixture", "fixture-chat-v2"));

        assertThat(view.isShared()).isFalse();
        assertThat(view.sharedStatus()).isEqualTo(SharedAiProvider.STATUS_REVOKED);
        ArgumentCaptor<SharedAiProvider> captor = ArgumentCaptor.forClass(SharedAiProvider.class);
        verify(sharedRepository).saveOrUpdate(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(SharedAiProvider.STATUS_REVOKED);
    }

    @Test
    @DisplayName("已停用但状态仍为 PUBLISHED 的共享项，配置变化后也不能保留可发布状态")
    void requestConfigChangeRevokesPreviouslyDisabledPublishedShared() {
        UserAiProvider source = provider(7);
        when(userProviderRepository.findByIdAndUserId(sourceId, adminId))
                .thenReturn(Optional.of(source));
        UserAiProvider changed = providerWith("Admission-Fixture", "fixture-chat-v2", 8);
        when(userProviderRepository.save(any())).thenReturn(changed);
        SharedAiProvider previous = shared(SharedAiProvider.STATUS_PUBLISHED, false);
        when(sharedRepository.findBySourceProviderId(sourceId)).thenReturn(Optional.of(previous));
        when(sharedRepository.saveOrUpdate(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AdminSharedAiProviderView view = service(1_000).update(adminId, sourceId,
                request("Admission-Fixture", "fixture-chat-v2"));

        assertThat(view.sharedStatus()).isEqualTo(SharedAiProvider.STATUS_REVOKED);
    }

    private AdminAiProviderService service(long p95MaxMs) {
        return new AdminAiProviderService(
                userProviderRepository,
                sharedRepository,
                secrets,
                endpoints,
                List.of(adapter),
                notificationService,
                probeResults,
                json,
                new ProviderAdmissionProperties(3, p95MaxMs, 0, 0));
    }

    private UserAiProvider provider(long configVersion) {
        return providerWith("Admission-Fixture", "fixture-chat", configVersion);
    }

    private UserAiProvider providerWith(String name, String modelName, long configVersion) {
        return new UserAiProvider(
                sourceId, adminId, name, ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com", "/v1/chat/completions", "enc_key", modelName,
                true, 0.7, 4096, EnumSet.of(ModelCapability.CHAT), false,
                OffsetDateTime.now(), OffsetDateTime.now(), false, configVersion);
    }

    private SharedAiProvider shared(String status, boolean enabled) {
        return new SharedAiProvider(
                UUID.randomUUID(), sourceId, adminId, "Admission-Fixture",
                ModelProviderType.OPENAI_COMPATIBLE, "https://api.example.com",
                "/v1/chat/completions", "enc_key", "fixture-chat", enabled, status,
                0.7, 4096, EnumSet.of(ModelCapability.CHAT), 7,
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static UserAiProviderRequest request(String name, String modelName) {
        return new UserAiProviderRequest(
                name, ModelProviderType.OPENAI_COMPATIBLE, "https://api.example.com",
                "/v1/chat/completions", "", modelName, true, 0.7, 4096,
                EnumSet.of(ModelCapability.CHAT), null, null);
    }

    private UserAiProviderProbeResultRepository.ProbeResultRow admissionRow(
            long configVersion, String status, long p95Ms, int timeoutCount, int failureCount) {
        ProviderAdmissionSnapshot snapshot = new ProviderAdmissionSnapshot(
                ProviderAdmissionSnapshot.SCHEMA_VERSION, status, 3, Math.max(1, p95Ms / 2), p95Ms,
                timeoutCount, failureCount, configVersion, 10_000);
        String detail = """
                {"schemaVersion":1,"status":"%s","samples":3,"p50Ms":%d,"p95Ms":%d,
                "timeoutCount":%d,"failureCount":%d,"configVersion":%d,"p95LimitMs":10000}
                """.formatted(status, snapshot.p50Ms(), p95Ms, timeoutCount, failureCount, configVersion);
        return new UserAiProviderProbeResultRepository.ProbeResultRow(
                sourceId, ProviderAdmissionSnapshot.MODE, status, "fixture-chat", detail,
                null, p95Ms, OffsetDateTime.now(), configVersion, OffsetDateTime.now());
    }

    private UserAiProviderProbeResultRepository.ProbeResultRow capturedAdmission() {
        ArgumentCaptor<UserAiProviderProbeResultRepository.ProbeResultRow> captor =
                ArgumentCaptor.forClass(UserAiProviderProbeResultRepository.ProbeResultRow.class);
        verify(probeResults).upsert(captor.capture());
        return captor.getValue();
    }

    private JsonNode parse(UserAiProviderProbeResultRepository.ProbeResultRow row) {
        try {
            return json.readTree(row.detail());
        } catch (Exception exception) {
            throw new AssertionError("invalid admission JSON", exception);
        }
    }

    private static final class StubAdapter implements ModelProviderAdapter {
        private record Outcome(ChatCompletionResult result, RuntimeException failure, long sleepMs) {}

        private final Deque<Outcome> outcomes = new ArrayDeque<>();

        void success(long sleepMs) {
            outcomes.add(new Outcome(
                    new ChatCompletionResult("连接成功", "OPENAI_COMPATIBLE", "fixture-chat", 1, 1, sleepMs),
                    null, sleepMs));
        }

        void timeout(long sleepMs) {
            outcomes.add(new Outcome(null, new BusinessException(ErrorCode.AI_MODEL_TIMEOUT), sleepMs));
        }

        void failure(RuntimeException failure) {
            outcomes.add(new Outcome(null, failure, 1));
        }

        @Override
        public ModelProviderType providerType() {
            return ModelProviderType.OPENAI_COMPATIBLE;
        }

        @Override
        public ChatCompletionResult complete(
                ModelConfiguration configuration, String apiKey, ChatCompletionCommand command) {
            Outcome outcome = outcomes.removeFirst();
            try {
                Thread.sleep(outcome.sleepMs());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
            }
            if (outcome.failure() != null) throw outcome.failure();
            return outcome.result();
        }

        @Override
        public void completeStream(
                ModelConfiguration configuration,
                String apiKey,
                ChatCompletionCommand command,
                Consumer<String> onToken,
                Consumer<ChatCompletionResult> onDone,
                Consumer<Exception> onError) {
            throw new UnsupportedOperationException();
        }
    }
}
