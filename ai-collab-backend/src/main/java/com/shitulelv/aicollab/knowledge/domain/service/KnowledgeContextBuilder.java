package com.shitulelv.aicollab.knowledge.domain.service;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class KnowledgeContextBuilder {
    public static final double MIN_SIMILARITY = 0.15d;
    /** 知识问答的默认来源上限：保持既有行为，不随 Agent 的上限放宽而改变。 */
    public static final int DEFAULT_MAX_SOURCES = 5;
    /** 调用方可请求的来源上限；与 Agent 工具 limit 的公共边界一致（1–12）。 */
    public static final int MAX_SOURCES_LIMIT = 12;
    private static final int MAX_PROMPT_SOURCE_CODE_POINTS = 8000;
    private static final int MAX_PROMPT_FILENAME_CODE_POINTS = 180;
    private static final int MAX_PROMPT_HEADING_CODE_POINTS = 300;

    /** 知识问答入口：最多 {@value #DEFAULT_MAX_SOURCES} 个来源、8000 字符正文预算。 */
    public KnowledgeContext build(List<DocumentSearchHit> hits) {
        return build(hits, DEFAULT_MAX_SOURCES);
    }

    /**
     * 调用方可指定来源上限的有界入口。
     *
     * <p>{@code maxSources} 只放宽“来源条数上限”，不改变正文预算
     * （{@value #MAX_PROMPT_SOURCE_CODE_POINTS} 字符）、相似度下限、去重与引用排名规则：
     * 放宽到 12 个来源也不会绕过文本预算，预算耗尽仍然停止追加。</p>
     *
     * @param maxSources 允许保留的来源条数上限，必须在 1..{@value #MAX_SOURCES_LIMIT} 之间
     */
    public KnowledgeContext build(List<DocumentSearchHit> hits, int maxSources) {
        if (maxSources < 1 || maxSources > MAX_SOURCES_LIMIT) {
            throw new IllegalArgumentException(
                    "maxSources 必须在 1 到 " + MAX_SOURCES_LIMIT + " 之间");
        }
        List<KnowledgeSource> sources = new ArrayList<>();
        Set<String> hashes = new HashSet<>();
        StringBuilder prompt = new StringBuilder();
        int remaining = MAX_PROMPT_SOURCE_CODE_POINTS;

        for (DocumentSearchHit hit : hits) {
            if (hit.similarity() < MIN_SIMILARITY || sources.size() >= maxSources) {
                continue;
            }
            if (hashes.contains(hit.contentHash()) || hit.content() == null || hit.content().isBlank()) {
                continue;
            }

            int rank = sources.size() + 1;
            String separator = prompt.isEmpty() ? "" : "\n\n";
            String filename = KnowledgePromptText.escapeXmlText(
                    hit.originalFilename(), MAX_PROMPT_FILENAME_CODE_POINTS).escaped();
            String heading = KnowledgePromptText.escapeXmlText(
                    hit.heading(), MAX_PROMPT_HEADING_CODE_POINTS).escaped();
            String prefix = separator
                    + "[S" + rank + "]\n"
                    + "文件：" + filename + "\n"
                    + "标题：" + heading + "\n"
                    + "内容：";
            int prefixLength = codePointLength(prefix);
            if (prefixLength >= remaining) {
                break;
            }

            KnowledgePromptText.EscapedText content = KnowledgePromptText.escapeXmlText(
                    hit.content(), remaining - prefixLength);
            if (content.original().isBlank()) {
                continue;
            }

            prompt.append(prefix).append(content.escaped());
            remaining -= prefixLength + codePointLength(content.escaped());
            hashes.add(hit.contentHash());
            sources.add(new KnowledgeSource(
                    hit.id(), hit.documentId(), hit.originalFilename(), hit.heading(),
                    content.original(), hit.contentHash(), hit.similarity(), rank,
                    hit.metadata() != null ? hit.metadata() : java.util.Map.of()));
        }
        return new KnowledgeContext(List.copyOf(sources), prompt.toString());
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }
}
