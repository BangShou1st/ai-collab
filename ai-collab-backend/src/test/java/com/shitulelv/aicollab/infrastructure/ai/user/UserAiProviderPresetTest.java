package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.infrastructure.ai.model.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserAiProviderPresetTest {
    private UserAiProvider row(UUID user, String enc, String model, boolean def) {
        return new UserAiProvider(UUID.randomUUID(), user, "OpenCode Zen Free", ModelProviderType.OPENAI_COMPATIBLE,
                "https://opencode.ai/zen/v1", "/chat/completions", enc, model, true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT), def, OffsetDateTime.now(), OffsetDateTime.now(), "OPENCODE_ZEN_FREE");
    }
    @Test void blankKeyPreservesStoredKey() {
        var repo = mock(UserAiProviderRepository.class);
        var secrets = mock(ModelSecretCipher.class);
        var user = UUID.randomUUID();
        var prev = row(user, "enc-old", "a-free", false);
        when(repo.findByUserAndPreset(user, "OPENCODE_ZEN_FREE")).thenReturn(Optional.of(prev));
        when(repo.countByUserId(user)).thenReturn(1);
        when(secrets.decrypt("enc-old")).thenReturn("old-key");
        var catalog = mock(OpenCodeZenModelCatalog.class);
        when(catalog.freeModels(any(), any())).thenReturn(List.of("a-free", "b-free"));
        var svc = new UserAiProviderPresetService(repo, secrets, new ProviderPresetRegistry(), catalog);
        svc.save(user, "   ", "b-free", true, false);
        verify(secrets, never()).encrypt("   ");
        verify(repo).save(argThat(r -> r.modelName().equals("b-free")));
    }
    @Test void invalidModelRejected() {
        var repo = mock(UserAiProviderRepository.class);
        var secrets = mock(ModelSecretCipher.class);
        var user = UUID.randomUUID();
        when(repo.findByUserAndPreset(user, "OPENCODE_ZEN_FREE")).thenReturn(Optional.empty());
        when(repo.countByUserId(user)).thenReturn(1);
        var catalog = mock(OpenCodeZenModelCatalog.class);
        when(catalog.freeModels(any(), any())).thenReturn(List.of("a-free"));
        doThrow(new BusinessException(com.shitulelv.aicollab.common.exception.ErrorCode.VALIDATION_ERROR))
                .when(catalog).requireFreeModel(eq("paid-model"), any());
        var svc = new UserAiProviderPresetService(repo, secrets, new ProviderPresetRegistry(), catalog);
        assertThatThrownBy(() -> svc.save(user, "k", "paid-model", true, false)).isInstanceOf(BusinessException.class);
    }
    @Test void createRequiresKey() {
        var repo = mock(UserAiProviderRepository.class);
        var secrets = mock(ModelSecretCipher.class);
        var user = UUID.randomUUID();
        when(repo.findByUserAndPreset(user, "OPENCODE_ZEN_FREE")).thenReturn(Optional.empty());
        var svc = new UserAiProviderPresetService(repo, secrets, new ProviderPresetRegistry(), mock(OpenCodeZenModelCatalog.class));
        assertThatThrownBy(() -> svc.save(user, "  ", "a-free", true, false)).isInstanceOf(BusinessException.class);
    }
    @Test void viewNeverExposesSecret() {
        var v = com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderView.from(row(UUID.randomUUID(), "enc-secret", "a-free", false));
        assertThat(v.hasApiKey()).isTrue();
        assertThat(v.toString()).doesNotContain("enc-secret");
    }
}
