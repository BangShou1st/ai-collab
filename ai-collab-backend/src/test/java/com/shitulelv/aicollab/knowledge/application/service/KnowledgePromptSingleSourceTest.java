package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.infrastructure.ai.AiCallLogService;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeQuestionRequest;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeCitationValidator;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgePromptProvider;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeSessionEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.repository.KnowledgeRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * J1-4 回归：知识问答的唯一 Prompt 来源是 {@link KnowledgePromptProvider}。
 *
 * <p>钉住的行为：
 * <ul>
 *   <li>{@link KnowledgeQuestionApplicationService#ask} 与
 *       {@link KnowledgeStreamQuestionService#askStream} 真实经过服务代码路径构建出的
 *       {@link ChatCompletionCommand}，system/user prompt 恰为 provider 返回的哨兵文本；</li>
 *   <li>两份已删除的本地短版 Prompt（{@code SYSTEM_PROMPT_BASE}）文案不会出现在任何出站命令中。</li>
 * </ul>
 *
 * <p>「缺 Bean 即启动失败」不在此断言：它由两个服务的<b>构造器必需依赖</b>保证
 * （Spring 构造器注入缺失即启动失败），显式传 {@code null} 属于 Java null 语义而非产品契约。</p>
 *
 * <p>说明：provider 用 mock 返回可识别哨兵（不涉及 Prompt 正文），
 * {@code KnowledgeVisibilityGuard}、引用校验与持久化都不参与"出站命令"断言，
 * 因此按 no-op mock 处理，不构造持久化夹具。</p>
 */
class KnowledgePromptSingleSourceTest {

    private static final String SYSTEM_SENTINEL = "PROVIDER_SYSTEM_PROMPT_SENTINEL";
    private static final String USER_SENTINEL = "PROVIDER_USER_PAYLOAD_SENTINEL";
    /** 已删除的本地副本开头（两份 SYSTEM_PROMPT_BASE 的首句）。 */
    private static final String LEGACY_LOCAL_PROMPT_HEADER = "你是 AI Collab 的项目知识库助手。";
    /** 已删除的本地副本独有文案（provider 长版 Prompt 中不存在此句）。 */
    private static final String LEGACY_LOCAL_PROMPT_PROBE = "优先基于 SOURCES 中的内容回答";
    private static final String QUESTION = "项目里程碑的验收口径是什么？";
    private static final String EVIDENCE_PAYLOAD = "（契约测试用证据块）";

    private final UUID projectId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();

    private ProjectAccessGuard access;
    private KnowledgeRepository repository;
    private KnowledgeRateLimiter rateLimiter;
    private ProjectKnowledgeRetrieval retrieval;
    private KnowledgeContextBuilder contextBuilder;
    private ChatModelGateway chat;
    private AiCallLogService aiLogs;
    private KnowledgeCitationValidator citationValidator;
    private KnowledgePersistenceService persistence;
    private KnowledgeVisibilityGuard visibility;
    private KnowledgePromptProvider promptProvider;

    private KnowledgeQuestionApplicationService questionService;
    private KnowledgeStreamQuestionService streamQuestionService;

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccessGuard.class);
        repository = mock(KnowledgeRepository.class);
        rateLimiter = mock(KnowledgeRateLimiter.class);
        retrieval = mock(ProjectKnowledgeRetrieval.class);
        contextBuilder = mock(KnowledgeContextBuilder.class);
        chat = mock(ChatModelGateway.class);
        aiLogs = mock(AiCallLogService.class);
        citationValidator = mock(KnowledgeCitationValidator.class);
        persistence = mock(KnowledgePersistenceService.class);
        visibility = mock(KnowledgeVisibilityGuard.class);
        promptProvider = mock(KnowledgePromptProvider.class);

        when(repository.findOwn(projectId, sessionId, userId))
                .thenReturn(Optional.of(mock(KnowledgeSessionEntity.class)));
        when(repository.recentContext(projectId, sessionId, userId)).thenReturn(List.of());
        when(retrieval.retrieve(any())).thenReturn(new ProjectKnowledgeRetrieval.Result(
                List.of(new DocumentSearchHit(chunkId, documentId, "验收规范.md", "里程碑验收",
                        "里程碑验收以客户签字为准。", "hash-milestone", 0.93)),
                QUESTION, List.of(), ProjectKnowledgeRetrieval.RETRIEVAL_CORE_LEGACY_DENSE));
        when(contextBuilder.buildBalanced(anyList())).thenReturn(new KnowledgeContext(
                List.of(new KnowledgeSource(chunkId, documentId, "验收规范.md", "里程碑验收",
                        "里程碑验收以客户签字为准。", "hash-milestone", 0.93, 1)),
                EVIDENCE_PAYLOAD));
        when(promptProvider.systemPrompt()).thenReturn(SYSTEM_SENTINEL);
        when(promptProvider.buildUserPayload(any(), any(), any())).thenReturn(USER_SENTINEL);

        questionService = questionService(promptProvider);
        streamQuestionService = streamQuestionService(promptProvider);
    }

    @Test
    void askSendsOnlyProviderPromptToChatGateway() {
        questionService.ask(projectId, sessionId, request(), userId);

        ArgumentCaptor<ChatCompletionCommand> captor = ArgumentCaptor.forClass(ChatCompletionCommand.class);
        verify(chat).complete(captor.capture(), any());
        ChatCompletionCommand command = captor.getValue();

        assertThat(command.systemPrompt()).isEqualTo(SYSTEM_SENTINEL);
        assertThat(command.userPrompt()).isEqualTo(USER_SENTINEL);
        assertNoLocalPromptCopy(command);

        // 用户负载由 provider 组装，入参来自服务自己装配的问题与证据
        verify(promptProvider).buildUserPayload(eq(QUESTION), eq(EVIDENCE_PAYLOAD), any());
    }

    @Test
    void askStreamSendsOnlyProviderPromptToChatGateway() {
        SseEmitter emitter = mock(SseEmitter.class);

        streamQuestionService.askStream(projectId, sessionId, request(), userId, emitter);

        ArgumentCaptor<ChatCompletionCommand> captor = ArgumentCaptor.forClass(ChatCompletionCommand.class);
        verify(chat, timeout(5_000)).completeStream(captor.capture(), any(), any(), any(), any());
        ChatCompletionCommand command = captor.getValue();

        assertThat(command.systemPrompt()).isEqualTo(SYSTEM_SENTINEL);
        assertThat(command.userPrompt()).isEqualTo(USER_SENTINEL);
        assertNoLocalPromptCopy(command);

        verify(promptProvider).buildUserPayload(eq(QUESTION), eq(EVIDENCE_PAYLOAD), any());
    }

    private KnowledgeQuestionApplicationService questionService(KnowledgePromptProvider provider) {
        return new KnowledgeQuestionApplicationService(
                access, repository, rateLimiter, retrieval, contextBuilder, chat, aiLogs,
                citationValidator, persistence, visibility, provider);
    }

    private KnowledgeStreamQuestionService streamQuestionService(KnowledgePromptProvider provider) {
        return new KnowledgeStreamQuestionService(
                access, repository, rateLimiter, retrieval, contextBuilder, chat, aiLogs,
                citationValidator, persistence, visibility, provider);
    }

    private static KnowledgeQuestionRequest request() {
        return new KnowledgeQuestionRequest(QUESTION, null);
    }

    private static void assertNoLocalPromptCopy(ChatCompletionCommand command) {
        assertThat(command.systemPrompt())
                .doesNotContain(LEGACY_LOCAL_PROMPT_HEADER)
                .doesNotContain(LEGACY_LOCAL_PROMPT_PROBE);
        assertThat(command.userPrompt())
                .doesNotContain(LEGACY_LOCAL_PROMPT_HEADER)
                .doesNotContain(LEGACY_LOCAL_PROMPT_PROBE);
    }
}
