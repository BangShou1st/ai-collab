package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.infrastructure.ai.model.KiloGatewayTransport;
import com.shitulelv.aicollab.infrastructure.ai.model.ProviderPresetRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KiloGatewayPresetServiceTest {
    private static final String FREE = "poolside/laguna-s-2.1:free";

    private KiloGatewayTransport transportListing(String... models) {
        KiloGatewayTransport transport = mock(KiloGatewayTransport.class);
        when(transport.admittedModels()).thenReturn(List.of(models));
        return transport;
    }

    private KiloGatewayPresetService service(UserAiProviderRepository repo, KiloGatewayTransport transport) {
        return new KiloGatewayPresetService(repo, new ProviderPresetRegistry(), transport);
    }

    @Test void saveRequiresAdmittedModel() {
        var repo = mock(UserAiProviderRepository.class);
        when(repo.findByUserAndPreset(any(), any())).thenReturn(Optional.empty());
        var service = service(repo, transportListing(FREE));
        assertThatThrownBy(() -> service.save(UUID.randomUUID(), "paid/model", true, false))
                .isInstanceOf(BusinessException.class);
        verify(repo, never()).save(any());
    }

    @Test void firstConnectionBecomesDefaultWithoutCredential() {
        var repo = mock(UserAiProviderRepository.class);
        var user = UUID.randomUUID();
        when(repo.findByUserAndPreset(user, "KILO_GATEWAY_FREE")).thenReturn(Optional.empty());
        when(repo.countByUserId(user)).thenReturn(0);

        service(repo, transportListing(FREE)).save(user, FREE, true, false);

        verify(repo).clearDefault(user);
        verify(repo).save(argThat(row ->
                "KILO_GATEWAY_FREE".equals(row.presetCode())
                        && FREE.equals(row.modelName())
                        && row.encryptedApiKey() == null
                        && "https://ai-gateway.kilo.ai/api/v1".equals(row.baseUrl())
                        && row.isDefault()));
    }

    @Test void disconnectDeletesOwnPresetWithoutPromoting() {
        var repo = mock(UserAiProviderRepository.class);
        var user = UUID.randomUUID();
        service(repo, mock(KiloGatewayTransport.class)).disconnect(user);
        verify(repo).deleteByUserAndPreset(user, "KILO_GATEWAY_FREE");
        verify(repo, never()).clearDefault(any());
        verify(repo, never()).markDefault(any(), any());
    }

    @Test void discoveryIsCachedUntilRefresh() {
        var transport = transportListing(FREE);
        var service = service(mock(UserAiProviderRepository.class), transport);
        assertThat(service.models(false)).containsExactly(FREE);
        assertThat(service.models(false)).containsExactly(FREE);
        verify(transport, times(1)).admittedModels();
        service.models(true);
        verify(transport, times(2)).admittedModels();
    }

    @Test void freeSuffixAloneIsNotAdmission() {
        // ":free" 后缀不是准入证据：只有当前网关注册表命中的免费模型才可保存。
        var repo = mock(UserAiProviderRepository.class);
        when(repo.findByUserAndPreset(any(), any())).thenReturn(Optional.empty());
        var service = service(repo, transportListing(FREE));
        assertThatThrownBy(() -> service.save(UUID.randomUUID(), "some-other-model:free", true, false))
                .isInstanceOf(BusinessException.class);
        verify(repo, never()).save(any());
    }

    @Test void testValidatesThroughTransport() {
        var repo = mock(UserAiProviderRepository.class);
        var user = UUID.randomUUID();
        var transport = transportListing(FREE);
        service(repo, transport).test(user, FREE);
        verify(transport).validateModel(FREE);
    }
}
