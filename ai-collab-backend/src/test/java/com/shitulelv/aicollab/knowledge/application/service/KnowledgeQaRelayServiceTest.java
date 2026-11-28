package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankCapabilityResolver;
import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeQuestionRequest;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeAnswerView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeCitationView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeStreamEvent;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeSessionEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.remote.KnowledgeQaModels;
import com.shitulelv.aicollab.knowledge.infrastructure.remote.KnowledgeQaStreamClient;
import com.shitulelv.aicollab.knowledge.infrastructure.repository.KnowledgeRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeHitVerifier;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeSearchModels;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 问答中继的核心语义（契约 §3.3/§5.1、§8.2/§8.3）：
 * finalize 全文一致性、引用复核→落库→citations/done 事件序、证据不足路径、
 * 入站门（开关/属主/校验）保留、流错误终止不落库。
 */
class KnowledgeQaRelayServiceTest {

    private static final String INSUFFICIENT = KnowledgeQaModels.INSUFFICIENT_ANSWER;

    private final UUID project = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final UUID document = UUID.randomUUID();
    private final UUID row = UUID.randomUUID();
    private final UUID snapshot = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();

    private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final KnowledgeRateLimiter rateLimiter = mock(KnowledgeRateLimiter.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final RerankCapabilityResolver rerank = mock(RerankCapabilityResolver.class);
    private final KnowledgeHitVerifier verifier = mock(KnowledgeHitVerifier.class);
    private final KnowledgePersistenceService persistence = mock(KnowledgePersistenceService.class);
    private final KnowledgeVisibilityGuard visibility = mock(KnowledgeVisibilityGuard.class);
    private final KnowledgeQaStreamClient client = mock(KnowledgeQaStreamClient.class);

    private KnowledgeQaRelayService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeQaRelayService(access, repository, rateLimiter, documents,
                rerank, verifier, persistence, visibility, client);
        lenient().when(repository.findOwn(project, session, user))
                .thenReturn(Optional.of(new KnowledgeSessionEntity()));
        DocumentEntity visible = document();
        lenient().when(documents.listVisibleForRetrieval(eq(project), any(), any(), any(), eq(5001)))
                .thenReturn(List.of(visible));
        lenient().when(documents.listVisibleForRetrieval(eq(project), anyList(), anyList(), any(), eq(5001)))
                .thenReturn(List.of(visible));
        lenient().when(repository.recentContext(project, session, user)).thenReturn(List.of());
        lenient().when(rerank.hasPublishedBinding(project)).thenReturn(false);
    }

    // ---------------------------------------------------------------- 同步路径

    @Test
    void syncAskVerifiesCitationsPersistsInsideVisibilityGuardAndReturnsView() {
        KnowledgeQaModels.Finalize finalize = finalize("回答正文。[S1]", false,
                List.of(citation(1)), modelTrace(), promptIdentity());
        when(client.stream(any(), any())).thenAnswer(invocation -> {
            KnowledgeQaStreamClient.EventListener listener = invocation.getArgument(1);
            listener.onEvent(KnowledgeQaModels.Event.token("回答正文。[S1]"));
            return finalize;
        });
        KnowledgeHitVerifier.VerifiedHit verified = verifiedHit(1);
        when(verifier.verify(eq(project), any(), any()))
                .thenReturn(new KnowledgeHitVerifier.Outcome(List.of(verified), Map.of()));
        KnowledgeAnswerView view = new KnowledgeAnswerView(messageId, "回答正文。[S1]", false, "qwen2.5:7b",
                List.of());
        AtomicReference<Runnable> guarded = new AtomicReference<>();
        doAnswer(invocation -> {
            guarded.set(invocation.getArgument(2));
            invocation.<Runnable>getArgument(2).run();
            return null;
        }).when(visibility).whileVisible(eq(project), anyList(), any(Runnable.class));
        when(persistence.saveExchange(eq(project), eq(session), eq(user), eq("问题"), eq("回答正文。[S1]"),
                eq(false), any(), anyList(), eq("knowledge.system"), eq("2026-11-11.2"))).thenReturn(view);

        KnowledgeAnswerView result = service.ask(project, session,
                new KnowledgeQuestionRequest("问题", List.of()), user);

        assertThat(result).isSameAs(view);
        // 落库发生在 whileVisible 内（撤权竞态防护保持）
        assertThat(guarded.get()).isNotNull();
        verify(persistence).saveExchange(eq(project), eq(session), eq(user), eq("问题"),
                eq("回答正文。[S1]"), eq(false), any(), anyList(), eq("knowledge.system"), eq("2026-11-11.2"));
        // 出站请求形状（§3.3 + P3 追认字段）
        ArgumentCaptor<KnowledgeQaModels.Request> outbound =
                ArgumentCaptor.forClass(KnowledgeQaModels.Request.class);
        verify(client).stream(outbound.capture(), any());
        assertThat(outbound.getValue().visibleDocumentIds()).containsExactly(document);
        assertThat(outbound.getValue().documentNames()).containsEntry(document.toString(), "验收规范.md");
        assertThat(outbound.getValue().rerank()).isEqualTo("HEURISTIC");
        assertThat(outbound.getValue().topK()).isEqualTo(8);
        assertThat(outbound.getValue().history()).isEmpty();
    }

    @Test
    void syncAskFailsWhenFinalizeAnswerDoesNotMatchForwardedTokens() {
        when(client.stream(any(), any())).thenAnswer(invocation -> {
            KnowledgeQaStreamClient.EventListener listener = invocation.getArgument(1);
            listener.onEvent(KnowledgeQaModels.Event.token("前半"));
            listener.onEvent(KnowledgeQaModels.Event.token("后半"));
            return finalize("回答正文", false, List.of(), modelTrace(), promptIdentity());
        });

        assertThatThrownBy(() -> service.ask(project, session,
                new KnowledgeQuestionRequest("问题", List.of()), user))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        verify(persistence, never()).saveExchange(any(), any(), any(), any(), any(), anyBoolean(), any(), anyList(), any(), any());
    }

    @Test
    void syncAskPersistsUnifiedInsufficientTextWhenAllCitationsRejected() {
        KnowledgeQaModels.Finalize finalize = finalize("正文", false,
                List.of(citation(1)), modelTrace(), promptIdentity());
        when(client.stream(any(), any())).thenAnswer(invocation -> {
            KnowledgeQaStreamClient.EventListener listener = invocation.getArgument(1);
            listener.onEvent(KnowledgeQaModels.Event.token("正文"));
            return finalize;
        });
        when(verifier.verify(eq(project), any(), any()))
                .thenReturn(new KnowledgeHitVerifier.Outcome(List.of(), Map.of()));
        KnowledgeAnswerView view = new KnowledgeAnswerView(messageId, INSUFFICIENT, true, "qwen2.5:7b", List.of());
        when(persistence.saveExchange(eq(project), eq(session), eq(user), eq("问题"), eq(INSUFFICIENT),
                eq(true), any(), anyList(), eq("knowledge.system"), eq("2026-11-11.2"))).thenReturn(view);

        KnowledgeAnswerView result = service.ask(project, session,
                new KnowledgeQuestionRequest("问题", List.of()), user);

        assertThat(result.answer()).isEqualTo(INSUFFICIENT);
        // 复核全拒：引用不落库，按证据不足处理（契约 §6 第 6 条）
        verify(persistence).saveExchange(eq(project), eq(session), eq(user), eq("问题"), eq(INSUFFICIENT),
                eq(true), any(), anyList(), eq("knowledge.system"), eq("2026-11-11.2"));
    }

    // ---------------------------------------------------------------- 入站门

    @Test
    void disabledChatGateFailsBeforeWorkerCall() {
        com.shitulelv.aicollab.system.application.service.SystemConfigService config =
                mock(com.shitulelv.aicollab.system.application.service.SystemConfigService.class);
        when(config.isChatEnabled()).thenReturn(false);
        KnowledgeQaRelayService gated = new KnowledgeQaRelayService(access, repository, rateLimiter,
                documents, rerank, verifier, persistence, visibility, client);
        setConfig(gated, config);

        assertThatThrownBy(() -> gated.ask(project, session,
                new KnowledgeQuestionRequest("问题", List.of()), user))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        verify(client, never()).stream(any(), any());
    }

    @Test
    void missingSessionFailsBeforeWorkerCall() {
        when(repository.findOwn(project, session, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ask(project, session,
                new KnowledgeQuestionRequest("问题", List.of()), user))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.KNOWLEDGE_SESSION_NOT_FOUND));
        verify(client, never()).stream(any(), any());
    }

    @Test
    void overlongQuestionRejectedWithoutWorkerCall() {
        assertThatThrownBy(() -> service.ask(project, session,
                new KnowledgeQuestionRequest("问".repeat(2001), List.of()), user))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(client, never()).stream(any(), any());
    }

    // ---------------------------------------------------------------- 流式路径

    @Test
    void streamRelayForwardsTokensThenCitationsAndDoneAfterPersist() throws Exception {
        KnowledgeQaModels.Finalize finalize = finalize("回答正文", false,
                List.of(citation(1)), modelTrace(), promptIdentity());
        when(client.stream(any(), any())).thenAnswer(invocation -> {
            KnowledgeQaStreamClient.EventListener listener = invocation.getArgument(1);
            listener.onEvent(KnowledgeQaModels.Event.token("回答"));
            listener.onEvent(KnowledgeQaModels.Event.token("正文"));
            return finalize;
        });
        KnowledgeHitVerifier.VerifiedHit verified = verifiedHit(1);
        when(verifier.verify(eq(project), any(), any()))
                .thenReturn(new KnowledgeHitVerifier.Outcome(List.of(verified), Map.of()));
        KnowledgeAnswerView view = new KnowledgeAnswerView(messageId, "回答正文", false, "qwen2.5:7b",
                List.of(new KnowledgeCitationView(document, "验收规范.md", row, "标题", "引用片段", 0.83, 1, 3)));
        when(persistence.saveExchange(any(), any(), any(), any(), any(), anyBoolean(), any(), anyList(), any(), any()))
                .thenReturn(view);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return null;
        }).when(visibility).whileVisible(eq(project), anyList(), any(Runnable.class));

        List<String[]> frames = new ArrayList<>();
        SseEmitter emitter = sseCollector(frames);

        service.askStream(project, session, new KnowledgeQuestionRequest("问题", List.of()), user, emitter);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(frames.stream().map(frame -> frame[0]).toList())
                        .containsExactly("token", "token", "citations", "done"));
        assertThat(frames.get(1)[1]).contains("正文");
        assertThat(frames.get(2)[1]).contains("citations");
        assertThat(frames.get(3)[1]).contains(messageId.toString());
        // done 前必落库（§1.3 时序）
        verify(persistence).saveExchange(any(), any(), any(), any(), any(), anyBoolean(), any(), anyList(), any(), any());
    }

    @Test
    void streamRelayMapsWorkerErrorEventToErrorSseWithoutPersist() throws Exception {
        when(client.stream(any(), any())).thenThrow(
                new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "回答包含不应披露的内部信息，已被安全策略拦截"));

        List<String[]> frames = new ArrayList<>();
        SseEmitter emitter = sseCollector(frames);

        service.askStream(project, session, new KnowledgeQuestionRequest("问题", List.of()), user, emitter);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(frames).isNotEmpty());
        String[] last = frames.get(frames.size() - 1);
        assertThat(last[0]).isEqualTo("error");
        assertThat(last[1]).contains("AI_PROVIDER_ERROR");
        assertThat(last[1]).contains("回答包含不应披露的内部信息");
        verify(persistence, never()).saveExchange(any(), any(), any(), any(), any(), anyBoolean(), any(), anyList(), any(), any());
    }

    @Test
    void streamRelaySendsErrorWhenStreamEndsWithoutFinalize() throws Exception {
        // worker 半途断开（无 finalize）：客户端报 AI_MODEL_TIMEOUT，中继终止且不落库
        when(client.stream(any(), any())).thenAnswer(invocation -> {
            KnowledgeQaStreamClient.EventListener listener = invocation.getArgument(1);
            listener.onEvent(KnowledgeQaModels.Event.token("部分"));
            throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT, "问答流未完成");
        });

        List<String[]> frames = new ArrayList<>();
        SseEmitter emitter = sseCollector(frames);

        service.askStream(project, session, new KnowledgeQuestionRequest("问题", List.of()), user, emitter);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(frames).isNotEmpty());
        String[] last = frames.get(frames.size() - 1);
        assertThat(last[0]).isEqualTo("error");
        assertThat(last[1]).contains("AI_MODEL_TIMEOUT");
        verify(persistence, never()).saveExchange(any(), any(), any(), any(), any(), anyBoolean(), any(), anyList(), any(), any());
    }

    // ---------------------------------------------------------------- 工具

    private void setConfig(KnowledgeQaRelayService target, Object config) {
        try {
            var field = KnowledgeQaRelayService.class.getDeclaredField("systemConfigService");
            field.setAccessible(true);
            field.set(target, config);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** 捕获 SseEmitter 实际写出的 SSE 帧（event:/data: 文本），与前端看到的帧一致。 */
    private static SseEmitter sseCollector(List<String[]> frames) {
        StringBuilder raw = new StringBuilder();
        return new SseEmitter(Long.MAX_VALUE) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                for (var item : builder.build()) {
                    Object data = item.getData();
                    if (data instanceof String text) {
                        raw.append(text);
                    } else if (data instanceof KnowledgeStreamEvent event) {
                        // data(Object) 的 JSON 序列化发生在发送时；这里等价复刻
                        raw.append(new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsString(event));
                    }
                }
                frames.clear();
                for (String block : raw.toString().split("\n\n")) {
                    String name = null;
                    String data = null;
                    for (String line : block.split("\n")) {
                        if (line.startsWith("event:")) {
                            name = line.substring("event:".length()).trim();
                        } else if (line.startsWith("data:")) {
                            data = line.substring("data:".length()).trim();
                        }
                    }
                    if (name != null) {
                        frames.add(new String[] {name, data == null ? "" : data});
                    }
                }
            }
        };
    }

    private DocumentEntity document() {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(document);
        entity.setOriginalFilename("验收规范.md");
        return entity;
    }

    private KnowledgeQaModels.Finalize.Citation citation(int rank) {
        return new KnowledgeQaModels.Finalize.Citation(document, 3, "a".repeat(64), snapshot,
                rank, 0.83, "引用片段");
    }

    private KnowledgeHitVerifier.VerifiedHit verifiedHit(int rank) {
        KnowledgeSearchModels.Hit hit = new KnowledgeSearchModels.Hit(rank, document, 3, "a".repeat(64),
                null, null, null, null, snapshot, Map.of(), null, null);
        return new KnowledgeHitVerifier.VerifiedHit(hit, document, 3, "a".repeat(64), snapshot,
                "标题", "数据库正文", row, 3, List.of());
    }

    private static KnowledgeQaModels.Finalize.Model modelTrace() {
        return new KnowledgeQaModels.Finalize.Model("OPENAI_COMPATIBLE", "qwen2.5:7b", 120L, 42, 7);
    }

    private static KnowledgeQaModels.Finalize.Prompt promptIdentity() {
        return new KnowledgeQaModels.Finalize.Prompt("knowledge.system", "2026-11-11.2");
    }

    private static KnowledgeQaModels.Finalize finalize(String answer, boolean insufficient,
            List<KnowledgeQaModels.Finalize.Citation> citations,
            KnowledgeQaModels.Finalize.Model model, KnowledgeQaModels.Finalize.Prompt prompt) {
        return new KnowledgeQaModels.Finalize(UUID.randomUUID(), answer, insufficient, citations,
                new KnowledgeQaModels.Finalize.Retrieval("HYBRID", "改写查询", "原因", List.of()),
                prompt, model);
    }
}
