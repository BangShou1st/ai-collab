package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.service.CrossDocumentSourceSelector;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgePromptText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * D-RAG-01C 第二轮 阶段 D：<b>SOURCE_CAP 选择策略对照</b>（实验用，仅存在于测试源码）。
 *
 * <p>本类<b>不进入生产路径</b>：策略 A 直接委托生产 {@link CrossDocumentSourceSelector}；
 * 策略 B 是“相关度保护”实验版本。A 的选择结果经 {@link #assemble} 后由 parity test 与生产
 * {@code buildDiverse} 对齐；B 的选中来源是测试侧反事实，因此相关度收益必须报告为近似实验指标，
 * 不能称作生产上下文的实际提升。</p>
 *
 * <p>策略 B 的规则：保留现有“每文档一个代表”的多样性意图，但加两道相关度保护——
 * ① <b>相对全局第 N 名门槛</b>：某文档的最佳分块若比全局第 maxSources 名的相似度还低，
 * 就不再无条件占用代表名额；② <b>为全局竞争预留名额</b>：代表最多占 maxSources − reserve 个名额，
 * 其余名额一律按全局相似度补齐。这样弱相关文档代表不会无限制地挤掉更相关且互补的证据
 * （当文档数 ≥ 名额数时，旧策略会把全部名额给代表，任何“次强但更相关”的第二分块都进不来）。</p>
 */
final class SourceCapStrategyExperiment {
    private SourceCapStrategyExperiment() {
    }

    /** 实验侧的最小装配预算：与生产一致（12 来源、8000 code point、单条正文下限 120）。 */
    static final int MAX_PROMPT_SOURCE_CODE_POINTS = 8000;
    static final int MIN_SOURCE_BODY_CODE_POINTS = 120;
    private static final int MAX_PROMPT_FILENAME_CODE_POINTS = 180;
    private static final int MAX_PROMPT_HEADING_CODE_POINTS = 300;

    /** 策略 B 为全局竞争预留的名额数（maxSources 的六分之一，至少 1）。 */
    static int globalFillReserve(int maxSources) {
        return Math.max(1, maxSources / 6);
    }

    /** 策略 A：生产既定策略（两阶段：先每文档一个代表，再按全局相似度补齐）。 */
    static List<DocumentSearchHit> selectCurrent(List<DocumentSearchHit> hits, int maxSources) {
        return new CrossDocumentSourceSelector().select(hits, maxSources);
    }

    /** 策略 B：相关度保护的多样性选择（相对全局第 N 名门槛）。 */
    static List<DocumentSearchHit> selectRelevanceProtected(List<DocumentSearchHit> hits, int maxSources) {
        if (hits == null || hits.isEmpty() || maxSources < 1) return List.of();
        // 与生产选择器同源：先过滤不合格候选，再按相似度排序、按文档内容哈希去重。
        List<DocumentSearchHit> eligible = new ArrayList<>();
        for (DocumentSearchHit hit : hits) {
            if (hit == null) continue;
            if (hit.similarity() < KnowledgeContextBuilder.MIN_SIMILARITY) continue;
            if (hit.content() == null || hit.content().isBlank()) continue;
            eligible.add(hit);
        }
        eligible.sort(Comparator
                .comparingDouble(DocumentSearchHit::similarity).reversed()
                .thenComparing(DocumentSearchHit::id));

        Map<UUID, List<DocumentSearchHit>> byDocument = new LinkedHashMap<>();
        Map<UUID, Set<String>> hashesByDocument = new HashMap<>();
        List<DocumentSearchHit> unique = new ArrayList<>();
        for (DocumentSearchHit hit : eligible) {
            Set<String> hashes = hashesByDocument.computeIfAbsent(hit.documentId(), ignored -> new HashSet<>());
            String hash = hit.contentHash();
            if (hash != null && !hashes.add(hash)) continue;
            unique.add(hit);
            byDocument.computeIfAbsent(hit.documentId(), ignored -> new ArrayList<>()).add(hit);
        }

        // 相对全局第 N 名门槛：N = maxSources；候选不足 N 时门槛为 0（不设额外限制）。
        double threshold = unique.size() >= maxSources
                ? unique.get(maxSources - 1).similarity() : 0d;

        List<DocumentSearchHit> selected = new ArrayList<>();
        Set<UUID> selectedIds = new LinkedHashSet<>();
        int representativeCap = maxSources - globalFillReserve(maxSources);
        // 第一阶段：只有相似度不低于全局第 N 名的文档代表才占用代表名额，且代表总数有上限。
        for (List<DocumentSearchHit> documentHits : byDocument.values()) {
            if (selected.size() >= representativeCap) break;
            DocumentSearchHit representative = documentHits.getFirst();
            if (representative.similarity() < threshold) continue;
            selected.add(representative);
            selectedIds.add(representative.id());
        }
        // 第二阶段：剩余名额一律按全局相似度补齐（弱文档仍可凭全局分数进入）。
        for (DocumentSearchHit hit : unique) {
            if (selected.size() == maxSources) break;
            if (selectedIds.add(hit.id())) selected.add(hit);
        }
        return selected;
    }

    /** 装配结果：保留的分块/文档身份、上下文字符预算占用与来源数。 */
    record Assembled(List<UUID> chunkIds, Set<UUID> documentIds, int promptCodePoints, int sourceCount) {}

    /** Mirrors production buildDiverse assembly; SourceCapStrategyExperimentParityTest locks parity for A. */
    static Assembled assemble(List<DocumentSearchHit> ordered, int maxSources) {
        if (maxSources < 1 || maxSources > KnowledgeContextBuilder.MAX_SOURCES_LIMIT) {
            throw new IllegalArgumentException("maxSources 超出生产范围");
        }
        List<UUID> retainedChunks = new ArrayList<>();
        Set<UUID> retainedDocuments = new LinkedHashSet<>();
        int remaining = MAX_PROMPT_SOURCE_CODE_POINTS;
        for (int index = 0; index < ordered.size() && retainedChunks.size() < maxSources; index++) {
            DocumentSearchHit hit = ordered.get(index);
            String separator = retainedChunks.isEmpty() ? "" : "\n\n";
            String filename = KnowledgePromptText.escapeXmlText(
                    hit.originalFilename(), MAX_PROMPT_FILENAME_CODE_POINTS).escaped();
            String heading = KnowledgePromptText.escapeXmlText(
                    hit.heading(), MAX_PROMPT_HEADING_CODE_POINTS).escaped();
            String prefix = separator
                    + "[S" + (retainedChunks.size() + 1) + "]\n"
                    + "文件：" + filename + "\n"
                    + "标题：" + heading + "\n"
                    + "内容：";
            int prefixLength = prefix.codePointCount(0, prefix.length());
            if (prefixLength >= remaining) break;
            int available = remaining - prefixLength;
            int sourcesLeft = Math.max(1, ordered.size() - index);
            int allowance = available / sourcesLeft;
            if (allowance < MIN_SOURCE_BODY_CODE_POINTS) break;
            KnowledgePromptText.EscapedText content = KnowledgePromptText.escapeXmlText(hit.content(), allowance);
            if (content.original().isBlank()) continue;
            int bodyCodePoints = content.escaped().codePointCount(0, content.escaped().length());
            remaining -= prefixLength + bodyCodePoints;
            retainedChunks.add(hit.id());
            retainedDocuments.add(hit.documentId());
        }
        return new Assembled(List.copyOf(retainedChunks), retainedDocuments,
                MAX_PROMPT_SOURCE_CODE_POINTS - remaining, retainedChunks.size());
    }

}
