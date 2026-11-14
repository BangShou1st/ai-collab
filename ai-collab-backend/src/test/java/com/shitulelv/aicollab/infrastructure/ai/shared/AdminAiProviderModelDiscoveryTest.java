package com.shitulelv.aicollab.infrastructure.ai.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.shared.api.AdminAiProviderController;
import com.shitulelv.aicollab.infrastructure.ai.user.ProviderModelDiscoveryService;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderProbeResultRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderRepository;
import com.shitulelv.aicollab.notification.application.service.AdminNotificationService;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminAiProviderModelDiscoveryTest {

    private final UserAiProviderRepository providers = mock(UserAiProviderRepository.class);
    private final ProviderModelDiscoveryService discovery = mock(ProviderModelDiscoveryService.class);

    @Test
    @DisplayName("管理员只能对当前账号拥有的已保存供应商执行模型发现")
    void savedProviderDiscoveryIsScopedToAdminOwner() {
        UUID adminId = UUID.randomUUID();
        UserAiProvider provider = provider(adminId);
        when(providers.findByIdAndUserId(provider.id(), adminId)).thenReturn(Optional.of(provider));
        when(discovery.models(adminId, provider.id(), true)).thenReturn(List.of("model-a", "model-b"));

        assertThat(service().discoverModels(adminId, provider.id(), true))
                .containsExactly("model-a", "model-b");
        verify(discovery).models(adminId, provider.id(), true);
    }

    @Test
    @DisplayName("其他管理员的供应商 ID 不暴露存在性且不会触发出站发现")
    void foreignProviderDiscoveryIsRejectedBeforeNetworkCall() {
        UUID currentAdmin = UUID.randomUUID();
        UserAiProvider foreignProvider = provider(UUID.randomUUID());
        when(providers.findByIdAndUserId(foreignProvider.id(), currentAdmin)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().discoverModels(currentAdmin, foreignProvider.id(), false))
                .isInstanceOf(BusinessException.class)
                .matches(failure -> ((BusinessException) failure).getErrorCode() == ErrorCode.VALIDATION_ERROR);
        verify(discovery, never()).models(currentAdmin, foreignProvider.id(), false);
    }

    @Test
    @DisplayName("未保存发现使用本次临时 Key，不经过供应商持久化仓库")
    void unsavedDiscoveryDelegatesWithoutPersistenceLookup() {
        UUID adminId = UUID.randomUUID();
        when(discovery.discoverUnsaved(
                adminId, ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com", "/v1/chat/completions", "temporary-key"))
                .thenReturn(List.of("model-a"));

        assertThat(service().discoverUnsavedModels(
                adminId, ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com", "/v1/chat/completions", "temporary-key"))
                .containsExactly("model-a");
        verify(discovery).discoverUnsaved(
                adminId, ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com", "/v1/chat/completions", "temporary-key");
        verifyNoInteractions(providers);
    }

    @Test
    @DisplayName("管理员控制器在权限校验后按当前管理员身份转发两种发现请求")
    void controllerDelegatesBothDiscoveryEndpointsWithAdminIdentity() {
        UUID adminId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(adminId.toString());
        AdminAiProviderService service = mock(AdminAiProviderService.class);
        UserService users = mock(UserService.class);
        when(service.discoverModels(adminId, sourceId, false)).thenReturn(List.of("saved-model"));
        when(service.discoverUnsavedModels(
                adminId, ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com", "/v1/chat/completions", "temporary-key"))
                .thenReturn(List.of("unsaved-model"));
        AdminAiProviderController controller = new AdminAiProviderController(service, users);

        assertThat(controller.models(sourceId, false, jwt).data()).containsExactly("saved-model");
        assertThat(controller.discoverUnsavedModels(
                new AdminAiProviderController.UnsavedModelDiscoveryRequest(
                        ModelProviderType.OPENAI_COMPATIBLE,
                        "https://api.example.com",
                        "/v1/chat/completions",
                        "temporary-key"),
                jwt).data()).containsExactly("unsaved-model");

        verify(users, org.mockito.Mockito.times(2)).requireSystemAdmin(adminId);
        verify(service).discoverModels(adminId, sourceId, false);
        verify(service).discoverUnsavedModels(
                adminId, ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com", "/v1/chat/completions", "temporary-key");
    }

    private AdminAiProviderService service() {
        return new AdminAiProviderService(
                providers,
                mock(SharedAiProviderRepository.class),
                mock(ModelSecretCipher.class),
                mock(OutboundEndpointPolicy.class),
                List.of(),
                mock(AdminNotificationService.class),
                mock(UserAiProviderProbeResultRepository.class),
                new ObjectMapper(),
                ProviderAdmissionProperties.defaults(),
                discovery);
    }

    private static UserAiProvider provider(UUID ownerId) {
        return new UserAiProvider(
                UUID.randomUUID(), ownerId, "admin-provider", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com", "/v1/chat/completions", "encrypted",
                "model-a", true, 0.2, 2048, EnumSet.of(ModelCapability.CHAT),
                false, OffsetDateTime.now(), OffsetDateTime.now(), false, 0);
    }
}
