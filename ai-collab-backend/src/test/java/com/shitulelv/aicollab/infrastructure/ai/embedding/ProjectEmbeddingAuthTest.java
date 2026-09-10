package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingController.ProjectEmbeddingConfigRequest;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectEmbeddingAuthTest {

    private final UUID projectId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    private static Jwt jwt(UUID userId) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"), Map.of("sub", userId.toString()));
    }

    private ProjectEmbeddingController controller(ProjectEmbeddingConfigRepository repo,
            ProjectAccessGuard guard, ModelSecretCipher secrets, ProjectEmbeddingGateway gateway) {
        return new ProjectEmbeddingController(repo, guard, secrets, gateway);
    }

    @Test
    void member_cannot_get_embedding_config() {
        ProjectEmbeddingConfigRepository repo = mock(ProjectEmbeddingConfigRepository.class);
        ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
        doThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN))
                .when(guard).requireAdmin(projectId, memberId);
        ProjectEmbeddingController controller =
                controller(repo, guard, mock(ModelSecretCipher.class), mock(ProjectEmbeddingGateway.class));

        assertThatThrownBy(() -> controller.get(projectId, jwt(memberId)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void non_member_cannot_save_embedding_config() {
        ProjectEmbeddingConfigRepository repo = mock(ProjectEmbeddingConfigRepository.class);
        ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
        doThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN))
                .when(guard).requireAdmin(projectId, memberId);
        ProjectEmbeddingController controller =
                controller(repo, guard, mock(ModelSecretCipher.class), mock(ProjectEmbeddingGateway.class));
        ProjectEmbeddingConfigRequest request = new ProjectEmbeddingConfigRequest(
                ModelProviderType.OPENAI_COMPATIBLE, "https://api.openai.com", "/v1/embeddings",
                "sk-test", "text-embedding-3-small", 1536, 16);

        assertThatThrownBy(() -> controller.save(projectId, request, jwt(memberId)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void owner_can_save_embedding_config() {
        ProjectEmbeddingConfigRepository repo = mock(ProjectEmbeddingConfigRepository.class);
        ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
        ModelSecretCipher secrets = mock(ModelSecretCipher.class);
        when(secrets.encrypt("sk-test")).thenReturn("enc");
        when(repo.findByProjectId(projectId)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(call -> call.getArgument(0));
        ProjectEmbeddingController controller =
                controller(repo, guard, secrets, mock(ProjectEmbeddingGateway.class));
        ProjectEmbeddingConfigRequest request = new ProjectEmbeddingConfigRequest(
                ModelProviderType.OPENAI_COMPATIBLE, "https://api.openai.com", "/v1/embeddings",
                "sk-test", "text-embedding-3-small", 1536, 16);

        var response = controller.save(projectId, request, jwt(ownerId));

        assertThat(response.data().hasApiKey()).isTrue();
    }

    @Test
    void forged_user_header_has_no_effect() {
        boolean legacyHeader = Arrays.stream(ProjectEmbeddingController.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .map(parameter -> parameter.getAnnotation(RequestHeader.class))
                .filter(annotation -> annotation != null)
                .anyMatch(annotation -> Arrays.asList(annotation.value()).contains("X-User-Id"));
        assertThat(legacyHeader).isFalse();
    }
}
