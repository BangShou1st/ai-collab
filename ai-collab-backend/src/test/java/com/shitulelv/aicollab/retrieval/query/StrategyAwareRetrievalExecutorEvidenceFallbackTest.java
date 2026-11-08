package com.shitulelv.aicollab.retrieval.query;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalPipeline;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG_V2_ROUTING_CLOSURE-01 任务二：Dense 优先策略的证据充分性回退契约。
 *
 * <p>回退判据从"候选为空"升级为"装配后无有效证据"；回退有界（最多一次完整 HYBRID）；
 * 探针对齐契约保证调用方可复用最后一次装配结果（QA 与流式 QA 共享同一执行器实现，
 * 不存在两套回退算法）。</p>
 */
class StrategyAwareRetrievalExecutorEvidenceFallbackTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final List<UUID> DOCUMENT_IDS = List.of();
    private static final List<MetadataFilter> FILTERS = List.of();
    private static final int TOP_K = 8;

    private RetrievalPipeline pipeline;
    private StrategyAwareRetrievalExecutor executor;

    @BeforeEach
    void setUp() {
        pipeline = mock(RetrievalPipeline.class);
        executor = new StrategyAwareRetrievalExecutor(pipeline);
    }

    private static DocumentSearchHit hit(String content, double similarity) {
        UUID id = UUID.randomUUID();
        return new DocumentSearchHit(id, UUID.randomUUID(), "doc.md", "heading",
                content, "hash-" + id, similarity);
    }

    private static RetrievalResult result(DocumentSearchHit... hits) {
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < hits.length; i++) {
            candidates.add(RetrievalCandidate.fromDenseHit(hits[i], i + 1));
        }
        return new RetrievalResult(candidates, null);
    }

    private void stubDense(DocumentSearchHit... hits) {
        when(pipeline.execute(eq(PROJECT_ID), anyString(), eq(DOCUMENT_IDS), eq(FILTERS),
                any(RetrievalPipeline.PipelineConfig.class))).thenReturn(result(hits));
    }

    private void stubHybrid(DocumentSearchHit... hits) {
        when(pipeline.execute(eq(PROJECT_ID), anyString(), eq(DOCUMENT_IDS), eq(FILTERS), eq(TOP_K)))
                .thenReturn(result(hits));
    }

    private static QueryRouteDecision denseFirstDecision() {
        return QueryRouteDecision.hybridWithStrategy("dense-first",
                new StructuredQuery("语义问题", List.of()), RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK);
    }

    @Test
    @DisplayName("Dense 返回零候选 → 回退完整 HYBRID（既有行为保持）")
    void denseEmpty_triggersSingleHybridFallback() {
        stubDense();
        DocumentSearchHit lexicalHit = hit("词法命中的候选", 0.6);
        stubHybrid(lexicalHit);

        List<DocumentSearchHit> out = executor.execute(
                PROJECT_ID, denseFirstDecision(), "语义问题", DOCUMENT_IDS, FILTERS, TOP_K);

        assertThat(out).hasSize(1);
        verify(pipeline, times(1)).execute(eq(PROJECT_ID), anyString(), eq(DOCUMENT_IDS), eq(FILTERS),
                any(RetrievalPipeline.PipelineConfig.class));
        verify(pipeline, times(1)).execute(eq(PROJECT_ID), anyString(), eq(DOCUMENT_IDS), eq(FILTERS), eq(TOP_K));
    }

    @Test
    @DisplayName("Dense 非空但全部被证据准入过滤（装配后无有效来源）→ 回退一次 HYBRID")
    void denseCandidatesAllFiltered_triggersSingleHybridFallback() {
        // 候选非空（相似度低于准入阈值 0.15，会被装配阶段全部丢弃），装配探针判定无有效证据
        DocumentSearchHit belowThreshold = hit("低相似度候选", 0.05);
        stubDense(belowThreshold);
        DocumentSearchHit lexicalHit = hit("词法补充的候选", 0.7);
        stubHybrid(lexicalHit);

        List<Integer> probeCalls = new ArrayList<>();
        List<DocumentSearchHit> out = executor.execute(
                PROJECT_ID, denseFirstDecision(), "语义问题", DOCUMENT_IDS, FILTERS, TOP_K,
                hits -> {
                    probeCalls.add(hits.size());
                    return !hits.isEmpty() && hits.getFirst().similarity() >= 0.15;
                });

        assertThat(out).hasSize(1);
        assertThat(out.getFirst().similarity()).isEqualTo(0.7);
        // 回退有界：dense 管线恰一次，完整 HYBRID 恰一次
        verify(pipeline, times(1)).execute(eq(PROJECT_ID), anyString(), eq(DOCUMENT_IDS), eq(FILTERS),
                any(RetrievalPipeline.PipelineConfig.class));
        verify(pipeline, times(1)).execute(eq(PROJECT_ID), anyString(), eq(DOCUMENT_IDS), eq(FILTERS), eq(TOP_K));
        // 探针对齐：最后一次调用对应最终返回候选
        assertThat(probeCalls.get(probeCalls.size() - 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("Dense 有合法证据 → 不执行回退")
    void denseEvidenceUsable_noFallback() {
        DocumentSearchHit usable = hit("高相似度候选", 0.8);
        stubDense(usable);

        List<DocumentSearchHit> out = executor.execute(
                PROJECT_ID, denseFirstDecision(), "语义问题", DOCUMENT_IDS, FILTERS, TOP_K,
                hits -> !hits.isEmpty() && hits.getFirst().similarity() >= 0.15);

        assertThat(out).hasSize(1);
        verify(pipeline, times(1)).execute(eq(PROJECT_ID), anyString(), eq(DOCUMENT_IDS), eq(FILTERS),
                any(RetrievalPipeline.PipelineConfig.class));
        verify(pipeline, never()).execute(eq(PROJECT_ID), anyString(), eq(DOCUMENT_IDS), eq(FILTERS), eq(TOP_K));
    }

    @Test
    @DisplayName("HYBRID 补充后证据仍不足 → 不发起第二次补充检索，返回结果交由调用方拒答")
    void hybridStillInsufficient_noSecondRetry() {
        DocumentSearchHit lowDense = hit("低相似度候选", 0.05);
        stubDense(lowDense);
        DocumentSearchHit lowLexical = hit("低相似度词法候选", 0.06);
        stubHybrid(lowLexical);

        List<List<DocumentSearchHit>> probeInvocations = new ArrayList<>();
        List<DocumentSearchHit> out = executor.execute(
                PROJECT_ID, denseFirstDecision(), "语义问题", DOCUMENT_IDS, FILTERS, TOP_K,
                hits -> {
                    probeInvocations.add(List.copyOf(hits));
                    return !hits.isEmpty() && hits.getFirst().similarity() >= 0.15;
                });

        assertThat(out).hasSize(1);
        assertThat(out.getFirst().similarity()).isEqualTo(0.06);
        // 回退有界：无第二次 HYBRID
        verify(pipeline, times(1)).execute(eq(PROJECT_ID), anyString(), eq(DOCUMENT_IDS), eq(FILTERS), eq(TOP_K));
        // 最终探针调用与返回候选一致（引用身份不变），调用方据此走既有拒答路径
        assertThat(probeInvocations.get(probeInvocations.size() - 1)).isEqualTo(out);
    }

    @Test
    @DisplayName("无探针时保持既有'候选为空'兼容判据：Dense 非空即不回退")
    void legacyCallSite_keepsEmptyCheckSemantics() {
        DocumentSearchHit belowThreshold = hit("低相似度候选", 0.05);
        stubDense(belowThreshold);

        List<DocumentSearchHit> out = executor.execute(
                PROJECT_ID, denseFirstDecision(), "语义问题", DOCUMENT_IDS, FILTERS, TOP_K);

        assertThat(out).hasSize(1);
        verify(pipeline, never()).execute(eq(PROJECT_ID), anyString(), eq(DOCUMENT_IDS), eq(FILTERS), eq(TOP_K));
    }

    @Test
    @DisplayName("范围与过滤约束在回退路径中逐字传递：projectId/documentIds/filters 不放宽")
    void fallback_passesSameScopeAndFilters() {
        List<UUID> documentIds = List.of(UUID.randomUUID());
        List<MetadataFilter> filters = List.of(MetadataFilter.contains("originalFilename", "规范"));
        when(pipeline.execute(eq(PROJECT_ID), anyString(), eq(documentIds), eq(filters),
                any(RetrievalPipeline.PipelineConfig.class))).thenReturn(result());
        when(pipeline.execute(eq(PROJECT_ID), anyString(), eq(documentIds), eq(filters), eq(TOP_K)))
                .thenReturn(result());

        executor.execute(PROJECT_ID, denseFirstDecision(), "语义问题", documentIds, filters, TOP_K,
                hits -> false);

        verify(pipeline, times(1)).execute(eq(PROJECT_ID), anyString(), eq(documentIds), eq(filters),
                any(RetrievalPipeline.PipelineConfig.class));
        verify(pipeline, times(1)).execute(eq(PROJECT_ID), anyString(), eq(documentIds), eq(filters), eq(TOP_K));
    }

    @Test
    @DisplayName("执行异常降级路径：探针最终对齐到实际返回候选")
    void strategyFailure_fallbackAlignsProbeWithReturnedHits() {
        when(pipeline.execute(eq(PROJECT_ID), anyString(), eq(DOCUMENT_IDS), eq(FILTERS),
                any(RetrievalPipeline.PipelineConfig.class)))
                .thenThrow(new IllegalStateException("dense failure"));
        DocumentSearchHit hybridHit = hit("降级候选", 0.6);
        stubHybrid(hybridHit);

        List<List<DocumentSearchHit>> probeInvocations = new ArrayList<>();
        List<DocumentSearchHit> out = executor.execute(
                PROJECT_ID, denseFirstDecision(), "语义问题", DOCUMENT_IDS, FILTERS, TOP_K,
                hits -> {
                    probeInvocations.add(List.copyOf(hits));
                    return true;
                });

        assertThat(out).hasSize(1);
        assertThat(probeInvocations).isNotEmpty();
        assertThat(probeInvocations.get(probeInvocations.size() - 1)).isEqualTo(out);
    }

    @Test
    @DisplayName("HYBRID/COMPLEX 策略不注入探针判定，装配由调用方完成")
    void nonDenseFirstStrategies_probeNotInvoked() {
        DocumentSearchHit hit = hit("候选", 0.6);
        when(pipeline.execute(eq(PROJECT_ID), anyString(), eq(DOCUMENT_IDS), eq(FILTERS), eq(TOP_K)))
                .thenReturn(result(hit));
        QueryRouteDecision structured = QueryRouteDecision.structured("filters",
                new StructuredQuery("type:pdf 报告", List.of()));

        List<DocumentSearchHit> out = executor.execute(
                PROJECT_ID, structured, "type:pdf 报告", DOCUMENT_IDS, FILTERS, TOP_K,
                hits -> {
                    throw new AssertionError("非 Dense 优先策略不应触发探针");
                });

        assertThat(out).hasSize(1);
    }
}
