package com.shitulelv.aicollab.retrieval.query;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;

import java.util.List;

/**
 * 证据充分性判定（RAG_V2_ROUTING_CLOSURE-01 任务二）。
 *
 * <p>回退判据从"检索候选是否为空"升级为"是否形成可使用、符合权限与引用约束的有效证据"：
 * Dense 返回了候选、但候选在证据准入（相似度阈值/去重/空正文）、Token Budget 或来源选择阶段
 * 被全部过滤时，同样触发有界回退——"候选非空但有效知识上下文为空"是真实存在的失败形态。</p>
 *
 * <p>契约约定：</p>
 * <ul>
 *   <li>判定函数由调用方注入（QA 与流式 QA 传入同一个基于 {@code KnowledgeContextBuilder} 的装配判定），
 *       回退算法本身单点持有于 {@link StrategyAwareRetrievalExecutor}，两条链路不得各自复制；</li>
 *   <li>判定必须<b>确定性且幂等</b>：执行器对同一候选列表可能调用一次（引导回退决策），
 *       并保证返回前最后一次调用与最终返回的候选列表一致，调用方可复用最后一次装配结果；</li>
 *   <li>判定不得通过降低阈值、放行不合格来源来"凑证据"；权限与文档范围约束由装配入口原有实现保证；</li>
 *   <li>"有有效来源但不足以回答复杂问题"（数量充分性）不属于本判定范围——保持保守行为，不凭数量自动认定证据充分。</li>
 * </ul>
 */
@FunctionalInterface
public interface EvidenceSufficiencyProbe {

    /**
     * 判定候选集是否已形成可使用的有效证据（装配后存在合法可引用的 KnowledgeSource）。
     *
     * @param hits 检索候选（按管线输出顺序）
     * @return true 表示存在至少一个有效来源，无需回退
     */
    boolean hasUsableEvidence(List<DocumentSearchHit> hits);
}
