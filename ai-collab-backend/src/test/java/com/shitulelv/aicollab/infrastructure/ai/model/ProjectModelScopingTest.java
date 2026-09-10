package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.infrastructure.ai.model.api.ModelConfigurationRequest;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectModelScopingTest {

    private final UUID projectA = UUID.randomUUID();
    private final UUID projectB = UUID.randomUUID();
    private final UUID configB = UUID.randomUUID();
    private final UUID adminA = UUID.randomUUID();

    private ModelConfiguration configOfProjectB() {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(configB, projectB, "b-model",
                ModelProviderType.OPENAI_COMPATIBLE, "https://api.openai.com", "/v1/chat/completions",
                "enc", "gpt-4o-mini", true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT), now, now);
    }

    private ModelConfigurationRequest request() {
        return new ModelConfigurationRequest("b-model", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.openai.com", "/v1/chat/completions", null, "gpt-4o-mini",
                true, 0.2, 1200, Set.of(ModelCapability.CHAT));
    }

    private ProjectModelConfigurationService service(ModelConfigurationRepository repository) {
        return new ProjectModelConfigurationService(
                mock(ProjectAccessGuard.class), repository, mock(ModelSecretCipher.class), List.of());
    }

    @Test
    void project_a_admin_cannot_update_project_b_config() {
        ModelConfigurationRepository repository = mock(ModelConfigurationRepository.class);
        when(repository.findById(configB)).thenReturn(Optional.of(configOfProjectB()));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        assertThatThrownBy(() -> service(repository).update(projectA, configB, request(), adminA))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void project_a_admin_cannot_delete_project_b_config() {
        ModelConfigurationRepository repository = mock(ModelConfigurationRepository.class);
        when(repository.findById(configB)).thenReturn(Optional.of(configOfProjectB()));

        assertThatThrownBy(() -> service(repository).delete(projectA, configB, adminA))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void project_a_admin_cannot_assign_project_b_config() {
        ModelConfigurationRepository repository = mock(ModelConfigurationRepository.class);
        when(repository.findById(configB)).thenReturn(Optional.of(configOfProjectB()));

        assertThatThrownBy(() -> service(repository)
                .assign(projectA, ModelPurpose.KNOWLEDGE_CHAT, configB, adminA))
                .isInstanceOf(BusinessException.class);
    }
}
