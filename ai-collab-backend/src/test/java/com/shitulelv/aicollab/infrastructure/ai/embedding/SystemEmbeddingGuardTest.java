package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.embedding.api.SystemEmbeddingConfigRequest;
import com.shitulelv.aicollab.infrastructure.ai.embedding.api.SystemEmbeddingConfigView;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemEmbeddingGuardTest {

    private final UUID admin = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();

    private SystemEmbeddingService service(SystemEmbeddingConfigRepository repository,
            UserService users) {
        return new SystemEmbeddingService(repository, mock(ModelSecretCipher.class),
                mock(com.shitulelv.aicollab.common.security.OutboundEndpointPolicy.class),
                users, mock(com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository.class),
                mock(com.shitulelv.aicollab.document.application.service.BatchReindexService.class),
                mock(ProjectEmbeddingGateway.class));
    }

    private SystemEmbeddingConfig active() {
        OffsetDateTime now = OffsetDateTime.now();
        return new SystemEmbeddingConfig(UUID.randomUUID(), "OPENAI_COMPATIBLE",
                "https://api.openai.com", "/v1/embeddings", "enc", "text-embedding-3-small",
                1536, 16, "fp-old", true, now, now);
    }

    private SystemEmbeddingConfigRequest request(String model, int dimensions) {
        return new SystemEmbeddingConfigRequest("OPENAI_COMPATIBLE", "https://api.openai.com",
                "/v1/embeddings", null, model, dimensions, 16);
    }

    @Test
    void member_cannot_modify_system_embedding() {
        UserService users = mock(UserService.class);
        when(users.requireSystemAdmin(member))
                .thenThrow(new BusinessException(ErrorCode.ADMIN_REQUIRED));
        SystemEmbeddingService service =
                service(mock(SystemEmbeddingConfigRepository.class), users);

        assertThatThrownBy(() -> service.update(member, request("text-embedding-3-small", 1536)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_REQUIRED));
    }

    @Test
    void non_admin_cannot_modify_system_embedding() {
        UserService users = mock(UserService.class);
        when(users.requireSystemAdmin(member))
                .thenThrow(new BusinessException(ErrorCode.ADMIN_REQUIRED));
        SystemEmbeddingService service =
                service(mock(SystemEmbeddingConfigRepository.class), users);

        assertThatThrownBy(() -> service.reindex(member, request("text-embedding-3-small", 1536)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void system_admin_can_modify_system_embedding() {
        UserService users = mock(UserService.class);
        SystemEmbeddingConfigRepository repository = mock(SystemEmbeddingConfigRepository.class);
        when(repository.findActive()).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        SystemEmbeddingService service = service(repository, users);

        SystemEmbeddingConfigRequest create = new SystemEmbeddingConfigRequest("OPENAI_COMPATIBLE",
                "https://api.openai.com", "/v1/embeddings", "sk-admin", "text-embedding-3-small",
                1536, 16);
        SystemEmbeddingConfigView view = service.update(admin, create);

        assertThat(view.model()).isEqualTo("text-embedding-3-small");
        assertThat(view.hasApiKey()).isFalse();
    }

    @Test
    void semantic_change_requires_reindex() {
        UserService users = mock(UserService.class);
        SystemEmbeddingConfigRepository repository = mock(SystemEmbeddingConfigRepository.class);
        when(repository.findActive()).thenReturn(Optional.of(active()));
        com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository documents =
                mock(com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository.class);
        when(documents.hasChunksWithOtherFingerprint(any())).thenReturn(true);
        SystemEmbeddingService service = new SystemEmbeddingService(repository,
                mock(ModelSecretCipher.class),
                mock(com.shitulelv.aicollab.common.security.OutboundEndpointPolicy.class),
                users, documents,
                mock(com.shitulelv.aicollab.document.application.service.BatchReindexService.class),
                mock(ProjectEmbeddingGateway.class));

        assertThatThrownBy(() -> service.update(admin, request("other-model", 768)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.EMBEDDING_REINDEX_REQUIRED));
    }
}
