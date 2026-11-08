package com.shitulelv.aicollab.retrieval.query;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalPipeline;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 策略感知检索执行器（阶段 3）：QA、流式 QA 共享同一执行组件（Agent 链路继续走自身 6 参管线注入）。
 *
 * <p>把"问题复杂度判断"与"实际检索执行策略"分离：决策由 {@link QueryRouter} 产出
 * （本地低成本规则，无每查询模型调用），本组件只负责按 {@link RetrievalStrategy} 执行并输出可解释诊断。</p>
 *
 * <p>RAG_V2_ROUTING_CLOSURE-01 任务二：DENSE_WITH_BOUNDED_FALLBACK 的回退判据由
 * "候选列表为空"升级为"装配后无有效证据"（见 {@link EvidenceSufficiencyProbe}）。
 * 有界性契约：回退最多执行一次完整 HYBRID 补充检索；补充结果仍需经过同一权限与证据校验，
 * 仍不足时由调用方按既有拒答路径处理，不得循环重试、不得降低阈值凑证据。</p>
 *
 * <p>安全契约不变：projectId/documentIds 范围、metadata 硬过滤下推、generationId、
 * 引用身份与 RRF/相似度类型化评分契约均沿用管线既有实现。</p>
 */
@Component
public class StrategyAwareRetrievalExecutor {
    private static final Logger log = LoggerFactory.getLogger(StrategyAwareRetrievalExecutor.class);
    private static final int MASKED_QUERY_CHARS = 6;

    private final RetrievalPipeline pipeline;

    @Autowired
    public StrategyAwareRetrievalExecutor(RetrievalPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * 按路由策略执行检索；返回 null 表示调用方需走自身回退路径（如 V1 dense 直连）。
     * 回退判据为既有"候选为空"语义（无探针注入时的兼容行为）。
     */
    public List<DocumentSearchHit> execute(UUID projectId, QueryRouteDecision decision, String effectiveQuery,
            List<UUID> documentIds, List<MetadataFilter> filters, int topK) {
        return execute(projectId, decision, effectiveQuery, documentIds, filters, topK, null);
    }

    /**
     * 按路由策略执行检索，支持注入证据充分性判定。
     *
     * <p>对 DENSE_WITH_BOUNDED_FALLBACK：Dense 结果经探针判定无有效证据（含"零候选"与
     * "候选被证据准入/预算装配全部过滤"两类）时，最多执行一次完整 HYBRID 补充检索。
     * 探针保证返回前最后一次调用对应最终返回的候选列表，调用方可安全复用其装配结果。</p>
     *
     * @param evidenceProbe 证据充分性判定（null 时退回"候选为空"兼容判据）
     */
    public List<DocumentSearchHit> execute(UUID projectId, QueryRouteDecision decision, String effectiveQuery,
            List<UUID> documentIds, List<MetadataFilter> filters, int topK, EvidenceSufficiencyProbe evidenceProbe) {
        RetrievalStrategy strategy = decision != null && decision.strategy() != null
                ? decision.strategy()
                : RetrievalStrategy.HYBRID;
        long started = System.nanoTime();
        AtomicBoolean fallbackUsed = new AtomicBoolean(false);
        AtomicReference<String> fallbackCause = new AtomicReference<>("NONE");
        List<DocumentSearchHit> hits;
        try {
            hits = switch (strategy) {
                // 多查询分解：原始查询 + 子查询共同参与 RRF 融合（保持原始查询召回 + 全局排名）
                case MULTI_QUERY_HYBRID -> executeMultiQuery(projectId, decision, effectiveQuery, documentIds, filters, topK);
                // Dense 优先（公开 Benchmark 实测：陌生短文本语料 Dense-only 优于 Hybrid 且快 7 倍；
                // 项目内部 P9 冻结基线 52/54 本身即 dense 直连协议测得）——装配后证据不足时有界回退完整管线
                case DENSE_WITH_BOUNDED_FALLBACK -> executeDenseFirstWithBoundedFallback(
                        projectId, effectiveQuery, documentIds, filters, topK, evidenceProbe, fallbackUsed, fallbackCause);
                case DENSE -> executeDenseOnly(projectId, effectiveQuery, documentIds, filters, topK);
                case HYBRID -> executeFullHybrid(projectId, effectiveQuery, documentIds, filters, topK);
            };
        } catch (RuntimeException failure) {
            // 路由执行异常不抬高失败层级：降级完整管线重试一次（有界）
            log.warn("Strategy execution failed, falling back to full hybrid: strategy={} error={}",
                    strategy, failure.getClass().getSimpleName());
            fallbackUsed.set(true);
            fallbackCause.set("STRATEGY_EXECUTION_FAILURE");
            hits = executeFullHybrid(projectId, effectiveQuery, documentIds, filters, topK);
            // 探针对齐：Dense 优先路径的探针可能只对 dense 候选调用过（装配结果与返回候选不一致），
            // 对最终候选补一次判定（确定性装配，代价可忽略），保证调用方可复用最后一次装配结果。
            if (strategy == RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK && evidenceProbe != null) {
                evidenceProbe.hasUsableEvidence(hits);
            }
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
        // 可解释诊断：不含用户查询全文（掩码），回退原因使用稳定结构化代码
        log.info("RAG_STRATEGY_ROUTE strategy={} fallbackUsed={} fallbackCause={} reason={} queryMasked={} queryLen={} candidates={} latencyMs={}",
                strategy, fallbackUsed.get(), fallbackCause.get(),
                mask(decision != null ? decision.reason() : ""),
                mask(effectiveQuery), effectiveQuery == null ? 0 : effectiveQuery.length(),
                hits.size(), latencyMs);
        return hits;
    }

    /**
     * Dense 优先 + 有界证据回退：探针判定 Dense 候选未形成有效证据时，最多执行一次完整 HYBRID。
     * 回退结果不再触发第二次补充检索（有界），由调用方对最终候选做既有拒答/说明处理。
     */
    private List<DocumentSearchHit> executeDenseFirstWithBoundedFallback(
            UUID projectId, String effectiveQuery, List<UUID> documentIds, List<MetadataFilter> filters, int topK,
            EvidenceSufficiencyProbe evidenceProbe, AtomicBoolean fallbackUsed, AtomicReference<String> fallbackCause) {
        List<DocumentSearchHit> denseOnly = executeDenseOnly(projectId, effectiveQuery, documentIds, filters, topK);
        if (hasUsableEvidence(evidenceProbe, denseOnly)) {
            return denseOnly;
        }
        fallbackUsed.set(true);
        fallbackCause.set(denseOnly.isEmpty() ? "DENSE_EMPTY" : "EVIDENCE_INSUFFICIENT_AFTER_ASSEMBLY");
        List<DocumentSearchHit> hybrid = executeFullHybrid(projectId, effectiveQuery, documentIds, filters, topK);
        // 探针对齐：保证最后一次探针调用对应最终返回的候选列表（确定性装配，调用方可直接复用），
        // 结果不再触发第二次补充检索（回退有界），证据仍不足时由调用方走既有拒答路径。
        hasUsableEvidence(evidenceProbe, hybrid);
        return hybrid;
    }

    /**
     * 证据判定：有探针时按装配后证据判定（覆盖"候选非空但全部被过滤"）；
     * 无探针时保持既有"候选为空"兼容判据。
     */
    private static boolean hasUsableEvidence(EvidenceSufficiencyProbe evidenceProbe, List<DocumentSearchHit> hits) {
        return evidenceProbe != null ? evidenceProbe.hasUsableEvidence(hits) : !hits.isEmpty();
    }

    private List<DocumentSearchHit> executeMultiQuery(UUID projectId, QueryRouteDecision decision,
            String effectiveQuery, List<UUID> documentIds, List<MetadataFilter> filters, int topK) {
        List<String> subQueries = decision != null ? decision.subQueries() : List.of();
        if (subQueries == null || subQueries.size() <= 1) {
            return executeFullHybrid(projectId, effectiveQuery, documentIds, filters, topK);
        }
        List<String> fusionQueries = new ArrayList<>(subQueries);
        fusionQueries.add(0, effectiveQuery);
        return pipeline.executeMultiQuery(projectId, fusionQueries, documentIds, filters, topK)
                .toDocumentSearchHits();
    }

    private List<DocumentSearchHit> executeDenseOnly(UUID projectId, String effectiveQuery,
            List<UUID> documentIds, List<MetadataFilter> filters, int topK) {
        // Dense-only：跳过词法 SQL、关闭窗口/父子扩展、PASS_THROUGH 透传（Dense 相似度语义保持原值）
        RetrievalPipeline.PipelineConfig config = new RetrievalPipeline.PipelineConfig(
                20, 20, 20, topK, false, 1, false, false, RerankerPassThroughHolder.TYPE, filters, null);
        return pipeline.execute(projectId, effectiveQuery, documentIds, filters, config).toDocumentSearchHits();
    }

    private List<DocumentSearchHit> executeFullHybrid(UUID projectId, String effectiveQuery,
            List<UUID> documentIds, List<MetadataFilter> filters, int topK) {
        // 完整默认管线：与历史 V2 生产行为完全一致（窗口+父子+启发式重排）
        RetrievalResult result = pipeline.execute(projectId, effectiveQuery, documentIds, filters, topK);
        return result.toDocumentSearchHits();
    }

    private static String mask(String text) {
        if (text == null || text.isBlank()) return "";
        return text.substring(0, Math.min(MASKED_QUERY_CHARS, text.length())) + "***";
    }

    /** 静态持有 RerankerType 避免循环依赖表达；PASS_THROUGH 保持 Dense 相似度原值。 */
    private static final class RerankerPassThroughHolder {
        private static final com.shitulelv.aicollab.retrieval.pipeline.RerankerType TYPE =
                com.shitulelv.aicollab.retrieval.pipeline.RerankerType.PASS_THROUGH;
    }
}
