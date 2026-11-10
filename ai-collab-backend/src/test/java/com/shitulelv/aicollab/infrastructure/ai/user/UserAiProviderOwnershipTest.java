package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderRequest;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderView;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
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

class UserAiProviderOwnershipTest {

    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();
    private final UUID providerB = UUID.randomUUID();

    private UserAiProvider providerOf(UUID user, UUID id, boolean isDefault) {
        OffsetDateTime now = OffsetDateTime.now();
        return new UserAiProvider(id, user, "main", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.openai.com", "/v1/chat/completions", "enc", "gpt-4o-mini",
                true, 0.2, 1200, EnumSet.of(ModelCapability.CHAT), isDefault, now, now, null);
    }

    private UserAiProviderService service(UserAiProviderRepository repository) {
        return new UserAiProviderService(repository, mock(ModelSecretCipher.class),
                mock(com.shitulelv.aicollab.common.security.OutboundEndpointPolicy.class), List.of());
    }

    @Test
    void owner_cannot_read_other_users_provider() {
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        when(repository.findById(providerB)).thenReturn(Optional.of(providerOf(userB, providerB, true)));

        assertThatThrownBy(() -> service(repository).get(userA, providerB))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void owner_cannot_update_other_users_provider() {
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        when(repository.findById(providerB)).thenReturn(Optional.of(providerOf(userB, providerB, true)));

        assertThatThrownBy(() -> service(repository).update(userA, providerB,
                new UserAiProviderRequest("x", ModelProviderType.OPENAI_COMPATIBLE,
                        "https://api.openai.com", "/v1/chat/completions", null, "gpt-4o-mini",
                        true, 0.2, 1200, java.util.Set.of(ModelCapability.CHAT))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void owner_cannot_delete_other_users_provider() {
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        when(repository.findById(providerB)).thenReturn(Optional.of(providerOf(userB, providerB, true)));
        UserAiProviderService service = service(repository);

        assertThatThrownBy(() -> service.delete(userA, providerB))
                .isInstanceOf(BusinessException.class);
        verify(repository, org.mockito.Mockito.never()).deleteByIdAndUserId(any(), any());
    }

    @Test
    void setting_default_replaces_previous_default() {
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.findByIdAndUserId(second, userA))
                .thenReturn(Optional.of(providerOf(userA, second, false)));
        when(repository.markDefault(userA, second)).thenReturn(1);

        service(repository).setDefault(userA, second);

        verify(repository).clearDefault(userA);
        verify(repository).markDefault(userA, second);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void purpose_override_wins_over_default() {
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        UserAiProvider override = providerOf(userA, UUID.randomUUID(), false);
        UserAiProvider def = providerOf(userA, UUID.randomUUID(), true);
        when(repository.findAssigned(userA, ModelPurpose.AGENT)).thenReturn(Optional.of(override));
        when(repository.findDefaultByUserId(userA)).thenReturn(Optional.of(def));

        assertThat(service(repository).resolve(userA, ModelPurpose.AGENT)).isEqualTo(override);
    }

    @Test
    void missing_default_and_override_returns_unconfigured() {
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        when(repository.findAssigned(userA, ModelPurpose.PLANNING)).thenReturn(Optional.empty());
        when(repository.findDefaultByUserId(userA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(repository).resolve(userA, ModelPurpose.PLANNING))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleting_default_does_not_randomly_select_another() {
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        UUID def = UUID.randomUUID();
        when(repository.findByIdAndUserId(def, userA))
                .thenReturn(Optional.of(providerOf(userA, def, true)));
        when(repository.findDefaultByUserId(userA)).thenReturn(Optional.empty());

        UserAiProviderService service = service(repository);
        service.delete(userA, def);

        assertThatThrownBy(() -> service.resolve(userA, ModelPurpose.KNOWLEDGE_CHAT))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void api_key_write_only() {
        UserAiProvider provider = providerOf(userA, UUID.randomUUID(), true);
        UserAiProviderView view = UserAiProviderView.from(provider);
        assertThat(view.hasApiKey()).isTrue();
        assertThat(view.toString()).doesNotContain("enc");
    }

    // ========== 成本台账价格权限（设计文档 §4.4）：价格走模型配置更新通道，用户级配置只能本人改 ==========

    private UserAiProviderRequest pricedRequest(java.math.BigDecimal priceInput, java.math.BigDecimal priceOutput) {
        return new UserAiProviderRequest("x", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.openai.com", "/v1/chat/completions", null, "gpt-4o-mini",
                true, 0.2, 1200, java.util.Set.of(ModelCapability.CHAT), priceInput, priceOutput);
    }

    @Test
    void price_update_rejected_for_other_users_provider() {
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        when(repository.findByIdAndUserId(providerB, userA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(repository).update(userA, providerB, pricedRequest(
                new java.math.BigDecimal("0.002"), new java.math.BigDecimal("0.006"))))
                .isInstanceOf(BusinessException.class);
        // 归属校验失败不得触达价格写入
        verify(repository, org.mockito.Mockito.never())
                .updatePrices(any(), any(), any(), any());
    }

    @Test
    void owner_updates_prices_through_existing_update_channel() {
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        when(repository.findByIdAndUserId(providerB, userA))
                .thenReturn(Optional.of(providerOf(userA, providerB, true)));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findPrices(providerB, userA)).thenReturn(Optional.of(
                new UserAiProviderRepository.ProviderPrices(
                        new java.math.BigDecimal("0.002000"), new java.math.BigDecimal("0.006000"))));

        UserAiProviderView view = service(repository).update(userA, providerB,
                pricedRequest(new java.math.BigDecimal("0.002"), new java.math.BigDecimal("0.006")));

        verify(repository).updatePrices(providerB, userA,
                new java.math.BigDecimal("0.002"), new java.math.BigDecimal("0.006"));
        assertThat(view.pricePer1kInput()).isEqualByComparingTo("0.002000");
        assertThat(view.pricePer1kOutput()).isEqualByComparingTo("0.006000");
    }

    @Test
    void preset_provider_cannot_carry_prices() {
        // 预置/共享连接不填价格（成本恒为 NULL）：更新通道在归属校验后即被 rejectPreset 拦截
        UserAiProviderRepository repository = mock(UserAiProviderRepository.class);
        UserAiProvider preset = new UserAiProvider(providerB, userA, "OpenCode Zen Free",
                ModelProviderType.OPENAI_COMPATIBLE, "https://api.example.com", "/v1/chat/completions",
                "enc", "model", true, 0.2, 1200, EnumSet.of(ModelCapability.CHAT), false,
                OffsetDateTime.now(), OffsetDateTime.now(), "OPENCODE_ZEN_FREE");
        when(repository.findByIdAndUserId(providerB, userA)).thenReturn(Optional.of(preset));

        assertThatThrownBy(() -> service(repository).update(userA, providerB, pricedRequest(
                new java.math.BigDecimal("0.002"), new java.math.BigDecimal("0.006"))))
                .isInstanceOf(BusinessException.class);
        verify(repository, org.mockito.Mockito.never())
                .updatePrices(any(), any(), any(), any());
    }
}
