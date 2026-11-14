package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeMessageEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeSessionEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.repository.KnowledgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * J2 退出条件②：{@code promptVersion} 可在知识问答调用链中追溯（V90 落点）。
 *
 * <p>钉住三条语义：</p>
 * <ol>
 *   <li>真正调用模型的轮次：助手的 {@code prompt_key/prompt_version} 必须写入；</li>
 *   <li>没有调用模型的轮次（证据不足直接拒答）：两列为 {@code null}——这是"没有用过 Prompt"的
 *       正确表达，不是缺省填充；</li>
 *   <li>只给一半身份是调用方错误，直接拒绝（避免库里出现"有 key 没 version"的半截证据）。</li>
 * </ol>
 */
class J2PromptIdentityPersistenceTest {

    private static final String PROMPT_KEY = "knowledge.system";
    private static final String PROMPT_VERSION = "2026-11-11.2";

    private final UUID projectId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private KnowledgeRepository repository;
    private KnowledgePersistenceService service;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeRepository.class);
        service = new KnowledgePersistenceService(repository);

        KnowledgeSessionEntity session = mock(KnowledgeSessionEntity.class);
        when(session.getUpdatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC));
        when(repository.lockOwn(projectId, sessionId, userId)).thenReturn(Optional.of(session));
        when(repository.touch(eq(projectId), eq(sessionId), eq(userId), any())).thenReturn(true);
    }

    @Test
    void assistantMessageCarriesPromptIdentity() {
        service.saveExchange(projectId, sessionId, userId, "问题", "回答", false,
                completion(), List.of(), PROMPT_KEY, PROMPT_VERSION);

        List<KnowledgeMessageEntity> inserted = capturedMessages();
        assertThat(inserted).hasSize(2);

        KnowledgeMessageEntity user = roleOf(inserted, "USER");
        assertThat(user.getPromptKey()).isNull();
        assertThat(user.getPromptVersion()).isNull();

        KnowledgeMessageEntity assistant = roleOf(inserted, "ASSISTANT");
        assertThat(assistant.getPromptKey()).isEqualTo(PROMPT_KEY);
        assertThat(assistant.getPromptVersion()).isEqualTo(PROMPT_VERSION);
    }

    @Test
    void turnWithoutModelCallKeepsPromptIdentityNull() {
        // 证据不足直接返回统一拒答（completion = null）：这一轮没有模型请求，也没有 Prompt 身份
        service.saveExchange(projectId, sessionId, userId, "问题", "当前项目资料不足以回答该问题",
                true, null, List.of());

        List<KnowledgeMessageEntity> inserted = capturedMessages();
        assertThat(roleOf(inserted, "ASSISTANT").getPromptKey()).isNull();
        assertThat(roleOf(inserted, "ASSISTANT").getPromptVersion()).isNull();
    }

    @Test
    void halfIdentityIsRejected() {
        assertThatThrownBy(() -> service.saveExchange(projectId, sessionId, userId, "问题", "回答", false,
                completion(), List.of(), PROMPT_KEY, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.saveExchange(projectId, sessionId, userId, "问题", "回答", false,
                completion(), List.of(), null, PROMPT_VERSION))
                .isInstanceOf(IllegalArgumentException.class);

        // 半截身份在校验阶段就被拒绝：连会话行都不锁、一条消息都不写
        assertThat(capturedMessagesDirect()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    // ---- 辅助 ----

    private List<KnowledgeMessageEntity> capturedMessages() {
        ArgumentCaptor<KnowledgeMessageEntity> captor = ArgumentCaptor.forClass(KnowledgeMessageEntity.class);
        verify(repository, atLeastOnce()).insertMessage(captor.capture());
        return captor.getAllValues();
    }

    private List<KnowledgeMessageEntity> capturedMessagesDirect() {
        ArgumentCaptor<KnowledgeMessageEntity> captor = ArgumentCaptor.forClass(KnowledgeMessageEntity.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).insertMessage(captor.capture());
        return captor.getAllValues();
    }

    private static KnowledgeMessageEntity roleOf(List<KnowledgeMessageEntity> messages, String role) {
        return messages.stream().filter(message -> role.equals(message.getRole())).findFirst().orElseThrow();
    }

    private static ChatCompletionResult completion() {
        return new ChatCompletionResult("回答", "OPENAI_COMPATIBLE", "model-a", 100, 20, 12L);
    }
}
