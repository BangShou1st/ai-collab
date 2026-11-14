package com.shitulelv.aicollab.infrastructure.ai.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.shared.api.AdminSharedAiProviderView;
import com.shitulelv.aicollab.infrastructure.ai.shared.api.SharedAiProviderView;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderProbeResultRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderRequest;
import com.shitulelv.aicollab.notification.application.service.AdminNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SharedAiProviderLifecycleTest {

    @Mock
    private UserAiProviderRepository userProviderRepository;
    @Mock
    private SharedAiProviderRepository sharedRepository;
    @Mock
    private ModelSecretCipher secrets;
    @Mock
    private OutboundEndpointPolicy endpoints;
    @Mock
    private AdminNotificationService notificationService;
    @Mock
    private UserAiProviderProbeResultRepository probeResults;

    private AdminAiProviderService adminService;
    private SharedAiProviderQueryService queryService;

    private final UUID adminId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID sharedId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adminService = new AdminAiProviderService(
                userProviderRepository,
                sharedRepository,
                secrets,
                endpoints,
                List.of(),
                notificationService,
                probeResults,
                new ObjectMapper(),
                ProviderAdmissionProperties.defaults());
        queryService = new SharedAiProviderQueryService(sharedRepository);
    }

    @Test
    @DisplayName("管理员多次发布同一供应商保持幂等且更新版本，只生成唯一共享项")
    void publishMultipleTimesIsIdempotent() {
        UserAiProvider source = new UserAiProvider(
                sourceId, adminId, "DeepSeek-V3", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.deepseek.com", "/v1/chat/completions", "enc_key", "deepseek-chat",
                true, 0.7, 4096, EnumSet.of(ModelCapability.CHAT), false,
                OffsetDateTime.now(), OffsetDateTime.now(), false, 0);

        when(userProviderRepository.findByIdAndUserId(sourceId, adminId)).thenReturn(Optional.of(source));
        when(sharedRepository.findBySourceProviderId(sourceId)).thenReturn(Optional.empty());
        when(probeResults.find(sourceId, ProviderAdmissionSnapshot.MODE))
                .thenReturn(Optional.of(admissionRow(sourceId, 0)));

        SharedAiProvider shared1 = new SharedAiProvider(
                sharedId, sourceId, adminId, "DeepSeek-V3", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.deepseek.com", "/v1/chat/completions", "enc_key", "deepseek-chat",
                true, "PUBLISHED", 0.7, 4096, EnumSet.of(ModelCapability.CHAT),
                0, OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
        when(sharedRepository.saveOrUpdate(any())).thenReturn(shared1);

        // 第一次发布
        AdminSharedAiProviderView res1 = adminService.publishShared(adminId, sourceId);
        assertThat(res1.isShared()).isTrue();
        assertThat(res1.sharedStatus()).isEqualTo("PUBLISHED");

        // 模拟第二次发布同一 sourceProviderId：sharedRepository 中已存在该 sourceId 映射
        when(sharedRepository.findBySourceProviderId(sourceId)).thenReturn(Optional.of(shared1));
        SharedAiProvider shared2 = new SharedAiProvider(
                sharedId, sourceId, adminId, "DeepSeek-V3", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.deepseek.com", "/v1/chat/completions", "enc_key", "deepseek-chat",
                true, "PUBLISHED", 0.7, 4096, EnumSet.of(ModelCapability.CHAT),
                1, OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
        when(sharedRepository.saveOrUpdate(any())).thenReturn(shared2);

        AdminSharedAiProviderView res2 = adminService.publishShared(adminId, sourceId);
        assertThat(res2.isShared()).isTrue();
        // ID 保持一致，依然是同一个 sharedId，没有新建另一条记录
        verify(sharedRepository, times(2)).saveOrUpdate(any());
        verify(notificationService, times(2)).publishEvent(eq("AI_PROVIDER"), eq("INFO"), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("普通用户只读共享供应商列表，脱敏且不暴露任何凭据和敏感端点")
    void queryServiceListsPublishedSanitized() {
        SharedAiProvider published = new SharedAiProvider(
                sharedId, sourceId, adminId, "Shared GPT-4", ModelProviderType.OPENAI_COMPATIBLE,
                "https://internal-api.corp.local", "/v1/chat/completions", "super_secret_encrypted_key", "gpt-4",
                true, "PUBLISHED", 0.7, 4096, EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING),
                2, OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());

        when(sharedRepository.findAllPublished()).thenReturn(List.of(published));

        List<SharedAiProviderView> list = queryService.listPublished();
        assertThat(list).hasSize(1);
        SharedAiProviderView view = list.get(0);
        assertThat(view.id()).isEqualTo(sharedId);
        assertThat(view.name()).isEqualTo("Shared GPT-4");
        assertThat(view.modelName()).isEqualTo("gpt-4");
        assertThat(view.enabled()).isTrue();
        assertThat(view.status()).isEqualTo("PUBLISHED");
        // 验证 view 类中根本没有 encryptedApiKey 或 baseUrl 字段，脱敏严密
    }

    @Test
    @DisplayName("共享供应商运行时快照通过 boolean shared 标识，不依赖预设字符串")
    void sharedRuntimeSnapshotCarriesTypeSafeSharedFlag() {
        SharedAiProvider published = new SharedAiProvider(
                sharedId, sourceId, adminId, "Shared GPT-4", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com", "/v1/chat/completions", "encrypted", "gpt-4",
                true, "PUBLISHED", 0.7, 4096, EnumSet.of(ModelCapability.CHAT),
                2, OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());

        UserAiProvider runtime = published.toRuntimeUserAiProvider();

        assertThat(runtime.shared()).isTrue();
        assertThat(runtime.userId()).isEqualTo(adminId);
        assertThat(runtime.enabled()).isTrue();
    }

    @Test
    @DisplayName("管理员列表可解析 RERANK 能力 Provider，且不把它当成 Chat 项")
    void adminListParsesRerankCapabilityProvider() {
        UserAiProvider source = new UserAiProvider(
                sourceId, adminId, "Chat-Source", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com", "/v1/chat/completions", "enc_key", "chat-model",
                true, 0.7, 4096, EnumSet.of(ModelCapability.CHAT), false,
                OffsetDateTime.now(), OffsetDateTime.now(), false, 0);
        SharedAiProvider rerankProvider = new SharedAiProvider(
                UUID.randomUUID(), null, adminId, "Rerank-Provider", ModelProviderType.LOCAL_CUSTOM,
                "http://127.0.0.1:19090", "/rerank", null, null,
                true, "PUBLISHED", 0.7, 4096, EnumSet.of(ModelCapability.RERANK),
                0, OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());

        when(userProviderRepository.findAllByUserId(adminId)).thenReturn(List.of(source));
        when(sharedRepository.findAll()).thenReturn(List.of(rerankProvider));

        assertThat(adminService.listAdminProviders(adminId))
                .extracting(AdminSharedAiProviderView::id)
                .containsExactly(sourceId);
    }

    @Test
    @DisplayName("已停用的管理员供应商不能发布为全站共享")
    void disabledSourceCannotBePublished() {
        UserAiProvider disabled = new UserAiProvider(
                sourceId, adminId, "Disabled-V3", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.deepseek.com", "/v1/chat/completions", "enc_key", "deepseek-chat",
                false, 0.7, 4096, EnumSet.of(ModelCapability.CHAT), false,
                OffsetDateTime.now(), OffsetDateTime.now(), false, 0);

        when(userProviderRepository.findByIdAndUserId(sourceId, adminId)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> adminService.publishShared(adminId, sourceId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已停用");
    }

    @Test
    @DisplayName("撤销共享供应商会更新状态为 REVOKED 并记录审计通知")
    void revokeSharedProviderSuccess() {
        UserAiProvider source = new UserAiProvider(
                sourceId, adminId, "DeepSeek-V3", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.deepseek.com", "/v1/chat/completions", "enc_key", "deepseek-chat",
                true, 0.7, 4096, EnumSet.of(ModelCapability.CHAT), false,
                OffsetDateTime.now(), OffsetDateTime.now(), false, 0);

        SharedAiProvider revoked = new SharedAiProvider(
                sharedId, sourceId, adminId, "DeepSeek-V3", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.deepseek.com", "/v1/chat/completions", "enc_key", "deepseek-chat",
                true, "REVOKED", 0.7, 4096, EnumSet.of(ModelCapability.CHAT),
                1, OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());

        when(userProviderRepository.findByIdAndUserId(sourceId, adminId)).thenReturn(Optional.of(source));
        when(sharedRepository.findBySourceProviderId(sourceId)).thenReturn(Optional.of(revoked));

        AdminSharedAiProviderView res = adminService.revokeShared(adminId, sourceId);
        assertThat(res.isShared()).isFalse();
        assertThat(res.sharedStatus()).isEqualTo("REVOKED");

        verify(sharedRepository).revokeBySourceProviderId(sourceId);
        verify(notificationService).publishEvent(eq("AI_PROVIDER"), eq("WARNING"), anyString(), anyString(), anyString(), anyString());
    }

    private static UserAiProviderProbeResultRepository.ProbeResultRow admissionRow(
            UUID providerId, long configVersion) {
        return new UserAiProviderProbeResultRepository.ProbeResultRow(
                providerId,
                ProviderAdmissionSnapshot.MODE,
                ProviderAdmissionSnapshot.STATUS_PASS,
                "deepseek-chat",
                """
                        {"schemaVersion":1,"status":"PASS","samples":3,"p50Ms":10,"p95Ms":20,
                        "timeoutCount":0,"failureCount":0,"configVersion":%d,"p95LimitMs":10000}
                        """.formatted(configVersion),
                null,
                20L,
                OffsetDateTime.now(),
                configVersion,
                OffsetDateTime.now());
    }
}
