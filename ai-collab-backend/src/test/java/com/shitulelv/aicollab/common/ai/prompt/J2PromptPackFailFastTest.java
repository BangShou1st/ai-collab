package com.shitulelv.aicollab.common.ai.prompt;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.infrastructure.ai.AiCallLogService;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeQuestionRequest;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgePersistenceService;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeQuestionApplicationService;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeRateLimiter;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeStreamQuestionService;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeVisibilityGuard;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeCitationValidator;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgePromptAssembler;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeSessionEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.repository.KnowledgeRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeStreamEvent;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * J2 退出条件②③：<b>Prompt 不可用时快速失败</b>，且失败语义是
 * {@code AI_PROMPT_UNAVAILABLE}——不是"悄悄用旧 Prompt"，也不是空指针/静默空指令。
 *
 * <p>本类用**真实的** {@link BundledPromptCatalog}（挂在空 classloader 上，因此 manifest 不可读）
 * 驱动真实服务代码路径，因此它验证的是"资产不可用"这一条真实故障，而不是桩替身。</p>
 *
 * <p>"保留上次成功版本"由 {@code RemotePromptCatalogTest} 覆盖（那是远端客户端的语义）；
 * 本类覆盖的是调用方：宁可整轮失败，也不放过没有 Prompt 的模型请求。</p>
 */
class J2PromptPackFailFastTest {

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

        when(repository.findOwn(projectId, sessionId, userId))
                .thenReturn(Optional.of(mock(KnowledgeSessionEntity.class)));
        when(repository.recentContext(projectId, sessionId, userId)).thenReturn(List.of());
        when(retrieval.retrieve(any())).thenReturn(new ProjectKnowledgeRetrieval.Result(
                List.of(new DocumentSearchHit(chunkId, documentId, "验收规范.md", "里程碑验收",
                        "里程碑验收以客户签字为准。", "hash-milestone", 0.93)),
                "问题", List.of(), ProjectKnowledgeRetrieval.RETRIEVAL_CORE_LEGACY_DENSE));
        when(contextBuilder.buildBalanced(anyList())).thenReturn(new KnowledgeContext(
                List.of(new KnowledgeSource(chunkId, documentId, "验收规范.md", "里程碑验收",
                        "里程碑验收以客户签字为准。", "hash-milestone", 0.93, 1)),
                "[S1]\n文件：验收规范.md\n内容：里程碑验收以客户签字为准。"));

        // 真实的 bundled 目录，但 classloader 里没有 promptpack/ → 资产不可用
        KnowledgePromptAssembler assembler = new KnowledgePromptAssembler(
                new BundledPromptCatalog(new URLClassLoader(new URL[0], null)));

        questionService = new KnowledgeQuestionApplicationService(
                access, repository, rateLimiter, retrieval, contextBuilder, chat, aiLogs,
                citationValidator, persistence, visibility, assembler);
        streamQuestionService = new KnowledgeStreamQuestionService(
                access, repository, rateLimiter, retrieval, contextBuilder, chat, aiLogs,
                citationValidator, persistence, visibility, assembler);
    }

    @Test
    void bundleMissingFailsWithPromptUnavailable_notWithGenericError() {
        PromptCatalog broken = new BundledPromptCatalog(new URLClassLoader(new URL[0], null));
        assertThat(((BundledPromptCatalog) broken).readinessFailure()).isNotNull();

        assertThatThrownBy(() -> broken.require(PromptKeys.KNOWLEDGE_SYSTEM))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AI_PROMPT_UNAVAILABLE));
    }

    @Test
    void askFailsFastAndNeverCallsTheModel() {
        assertThatThrownBy(() -> questionService.ask(projectId, sessionId, request(), userId))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AI_PROMPT_UNAVAILABLE));

        // 关键语义：没有 Prompt 就绝不发模型请求，也绝不落一轮"没有 Prompt 的回答"
        verifyNoInteractions(chat);
        verifyNoInteractions(persistence);
    }

    @Test
    void askStreamFailsFastAndNeverCallsTheModel() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);

        // 流式路径不能在响应开始后抛异常，因此失败语义是「立即下发 error 事件并结束流」；
        // 关键点仍是：一次模型请求都不发、一轮回答都不落。
        assertThatCode(() -> streamQuestionService.askStream(
                projectId, sessionId, request(), userId, emitter)).doesNotThrowAnyException();

        ArgumentCaptor<SseEmitter.SseEventBuilder> events =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, timeout(5_000).atLeastOnce()).send(events.capture());
        verify(emitter, timeout(5_000)).complete();

        boolean errorReported = events.getAllValues().stream()
                .flatMap(builder -> builder.build().stream())
                .map(SseEmitter.DataWithMediaType::getData)
                .filter(KnowledgeStreamEvent.class::isInstance)
                .map(KnowledgeStreamEvent.class::cast)
                .anyMatch(event -> "error".equals(event.type())
                        && ErrorCode.AI_PROMPT_UNAVAILABLE.name().equals(event.code()));
        assertThat(errorReported)
                .as("流式路径必须把 AI_PROMPT_UNAVAILABLE 作为 error 事件下发")
                .isTrue();

        verifyNoInteractions(chat);
        verifyNoInteractions(persistence);
    }

    @Test
    void promptUnavailableIsServiceUnavailableAndRetryableInTheApiSurface() {
        // 对外语义可解释：503（依赖暂不可用），不是 500（代码缺陷），也不是 200 空回答
        assertThat(ErrorCode.AI_PROMPT_UNAVAILABLE.httpStatus().value()).isEqualTo(503);
        assertThat(ErrorCode.AI_PROMPT_UNAVAILABLE.message()).contains("Prompt");
    }

    private static KnowledgeQuestionRequest request() {
        return new KnowledgeQuestionRequest("项目里程碑的验收口径是什么？", null);
    }
}
