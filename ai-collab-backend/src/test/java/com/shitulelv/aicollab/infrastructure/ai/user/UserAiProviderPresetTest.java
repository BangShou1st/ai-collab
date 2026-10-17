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
    /** 共享库白名单内的模型：无需 API Key 即可作为免费 Chat 模型准入。 */
    private static final String WHITELISTED = "big-pickle";
    private static final String ALSO_WHITELISTED = "space-bunny-free";

    private UserAiProvider row(UUID user, String enc, String model, boolean def) {
        return new UserAiProvider(UUID.randomUUID(), user, "OpenCode Zen Free", ModelProviderType.OPENAI_COMPATIBLE,
                "https://opencode.ai/inference/v1", "/chat/completions", enc, model, true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT), def, OffsetDateTime.now(), OffsetDateTime.now(), "OPENCODE_ZEN_FREE");
    }

    /** 上游发现结果由传输层提供；准入判定本身来自共享库白名单。 */
    private static OpenCodeZenTransport transportListing(String... models) {
        OpenCodeZenTransport transport = mock(OpenCodeZenTransport.class);
        when(transport.admittedModels(any(), any())).thenReturn(List.of(models));
        return transport;
    }

    @Test void blankKeyPreservesStoredKey() {
        var repo = mock(UserAiProviderRepository.class);
        var secrets = mock(ModelSecretCipher.class);
        var user = UUID.randomUUID();
        var prev = row(user, "enc-old", WHITELISTED, false);
        when(repo.findByUserAndPreset(user, "OPENCODE_ZEN_FREE")).thenReturn(Optional.of(prev));
        when(repo.countByUserId(user)).thenReturn(1);
        when(secrets.decryptOptional("enc-old")).thenReturn("old-key");
        var svc = new UserAiProviderPresetService(repo, secrets, new ProviderPresetRegistry(),
                transportListing(WHITELISTED, ALSO_WHITELISTED));
        svc.save(user, "   ", ALSO_WHITELISTED, true, false);
        verify(secrets, never()).encrypt("   ");
        verify(repo).save(argThat(r -> r.modelName().equals(ALSO_WHITELISTED)));
    }

    @Test void invalidModelRejected() {
        var repo = mock(UserAiProviderRepository.class);
        var secrets = mock(ModelSecretCipher.class);
        var user = UUID.randomUUID();
        when(repo.findByUserAndPreset(user, "OPENCODE_ZEN_FREE")).thenReturn(Optional.empty());
        when(repo.countByUserId(user)).thenReturn(1);
        var svc = new UserAiProviderPresetService(repo, secrets, new ProviderPresetRegistry(),
                transportListing(WHITELISTED));
        assertThatThrownBy(() -> svc.save(user, "k", "paid-model", true, false)).isInstanceOf(BusinessException.class);
    }

    @Test void whitelistedModelIsAdmittedWithoutKey() {
        var repo = mock(UserAiProviderRepository.class);
        var user = UUID.randomUUID();
        when(repo.findByUserAndPreset(user, "OPENCODE_ZEN_FREE")).thenReturn(Optional.empty());
        var svc = new UserAiProviderPresetService(repo, mock(ModelSecretCipher.class), new ProviderPresetRegistry(),
                transportListing(WHITELISTED));
        svc.save(user, "   ", WHITELISTED, true, false);
        verify(repo).save(argThat(r -> r.modelName().equals(WHITELISTED)));
    }

    @Test void modelOutsideWhitelistRequiresKey() {
        var repo = mock(UserAiProviderRepository.class);
        var user = UUID.randomUUID();
        when(repo.findByUserAndPreset(user, "OPENCODE_ZEN_FREE")).thenReturn(Optional.empty());
        // 上游列表给了一个非白名单模型：目录交集无法兜底，凭证判定仍按白名单 fail-closed。
        var svc = new UserAiProviderPresetService(repo, mock(ModelSecretCipher.class), new ProviderPresetRegistry(),
                transportListing("legacy-paid"));
        assertThatThrownBy(() -> svc.save(user, "  ", "legacy-paid", true, false)).isInstanceOf(BusinessException.class);
    }

    @Test void modelDiscoveryCacheIsIsolatedPerCredential() {
        var repo = mock(UserAiProviderRepository.class);
        var transport = transportListing(WHITELISTED);
        var svc = new UserAiProviderPresetService(repo, mock(ModelSecretCipher.class), new ProviderPresetRegistry(),
                transport);

        svc.freeModels("key-of-user-a", false);
        svc.freeModels("key-of-user-b", false);

        // 不同凭证关联的可用性不会被混用：各自独立发现，不会命中对方的缓存。
        verify(transport, times(2)).admittedModels(any(), any());
    }

    @Test void modelDiscoveryCacheServesRepeatedCallsForTheSameCredential() {
        var repo = mock(UserAiProviderRepository.class);
        var transport = transportListing(WHITELISTED);
        var svc = new UserAiProviderPresetService(repo, mock(ModelSecretCipher.class), new ProviderPresetRegistry(),
                transport);

        assertThat(svc.freeModels("same-key", false)).containsExactly(WHITELISTED);
        assertThat(svc.freeModels("same-key", false)).containsExactly(WHITELISTED);
        verify(transport, times(1)).admittedModels(any(), any());

        svc.freeModels("same-key", true);
        verify(transport, times(2)).admittedModels(any(), any());
    }

    @Test void viewNeverExposesSecret() {
        var v = com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderView.from(row(UUID.randomUUID(), "enc-secret", WHITELISTED, false));
        assertThat(v.hasApiKey()).isTrue();
        assertThat(v.toString()).doesNotContain("enc-secret");
    }

    @Test void disconnectDeletesOwnPresetWithoutPromoting() {
        var repo = mock(UserAiProviderRepository.class);
        var user = UUID.randomUUID();
        var svc = new UserAiProviderPresetService(repo, mock(ModelSecretCipher.class),
                new ProviderPresetRegistry(), mock(OpenCodeZenTransport.class));
        svc.disconnect(user);
        verify(repo).deleteByUserAndPreset(user, "OPENCODE_ZEN_FREE");
        verify(repo, never()).clearDefault(any());
        verify(repo, never()).markDefault(any(), any());
    }

    @Test void disconnectMissingConnectionIsIdempotent() {
        var repo = mock(UserAiProviderRepository.class);
        var user = UUID.randomUUID();
        when(repo.deleteByUserAndPreset(user, "OPENCODE_ZEN_FREE")).thenReturn(0);
        var svc = new UserAiProviderPresetService(repo, mock(ModelSecretCipher.class),
                new ProviderPresetRegistry(), mock(OpenCodeZenTransport.class));
        svc.disconnect(user);
        verify(repo).deleteByUserAndPreset(user, "OPENCODE_ZEN_FREE");
    }
}
