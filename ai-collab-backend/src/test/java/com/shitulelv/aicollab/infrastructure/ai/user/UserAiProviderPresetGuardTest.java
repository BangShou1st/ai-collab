package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.model.*;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderRequest;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserAiProviderPresetGuardTest {
    private UserAiProvider zen(UUID user) {
        return new UserAiProvider(UUID.randomUUID(), user, "OpenCode Zen Free", ModelProviderType.OPENAI_COMPATIBLE,
                "https://opencode.ai/zen/v1", "/chat/completions", "enc", "a-free", true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT), false, OffsetDateTime.now(), OffsetDateTime.now(), "OPENCODE_ZEN_FREE");
    }
    private UserAiProvider custom(UUID user) {
        return new UserAiProvider(UUID.randomUUID(), user, "c", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com", "/v1/chat/completions", "enc", "m", true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT), false, OffsetDateTime.now(), OffsetDateTime.now(), null);
    }
    private UserAiProviderRequest req() {
        return new UserAiProviderRequest("c", ModelProviderType.OPENAI_COMPATIBLE, "https://api.example.com",
                "/v1/chat/completions", "k", "m", true, 0.2, 1200, Set.of(ModelCapability.CHAT));
    }
    private UserAiProviderService svc(UserAiProviderRepository repo) {
        return new UserAiProviderService(repo, mock(ModelSecretCipher.class), mock(OutboundEndpointPolicy.class), List.of());
    }
    @Test void genericUpdateRejectsPreset() {
        var repo = mock(UserAiProviderRepository.class);
        var user = UUID.randomUUID(); var z = zen(user);
        when(repo.findByIdAndUserId(z.id(), user)).thenReturn(Optional.of(z));
        assertThatThrownBy(() -> svc(repo).update(user, z.id(), req())).isInstanceOf(BusinessException.class);
        verify(repo, never()).save(any());
    }
    @Test void genericDeleteRejectsPreset() {
        var repo = mock(UserAiProviderRepository.class);
        var user = UUID.randomUUID(); var z = zen(user);
        when(repo.findByIdAndUserId(z.id(), user)).thenReturn(Optional.of(z));
        assertThatThrownBy(() -> svc(repo).delete(user, z.id())).isInstanceOf(BusinessException.class);
        verify(repo, never()).deleteByIdAndUserId(any(), any());
    }
    @Test void genericTestRejectsPreset() {
        var repo = mock(UserAiProviderRepository.class);
        var user = UUID.randomUUID(); var z = zen(user);
        when(repo.findByIdAndUserId(z.id(), user)).thenReturn(Optional.of(z));
        assertThatThrownBy(() -> svc(repo).test(user, z.id())).isInstanceOf(BusinessException.class);
    }
    @Test void customUpdateStillWorks() {
        var repo = mock(UserAiProviderRepository.class);
        var user = UUID.randomUUID(); var c = custom(user);
        when(repo.findByIdAndUserId(c.id(), user)).thenReturn(Optional.of(c));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        svc(repo).update(user, c.id(), req());
        verify(repo).save(any());
    }
    @Test void setDefaultAndPurposeAcceptPreset() {
        var repo = mock(UserAiProviderRepository.class);
        var user = UUID.randomUUID(); var z = zen(user);
        when(repo.findByIdAndUserId(z.id(), user)).thenReturn(Optional.of(z));
        when(repo.markDefault(user, z.id())).thenReturn(1);
        svc(repo).setDefault(user, z.id());
        svc(repo).assignPurpose(user, ModelPurpose.AGENT, z.id());
        verify(repo).markDefault(user, z.id());
        verify(repo).assignPurpose(user, ModelPurpose.AGENT, z.id());
    }
}
