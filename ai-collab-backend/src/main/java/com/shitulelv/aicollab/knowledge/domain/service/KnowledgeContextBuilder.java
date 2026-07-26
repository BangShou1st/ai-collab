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
    public static final double MIN_SIMILARITY = 0.55d;
    private static final int MAX_SOURCES = 5;
    private static final int MAX_PROMPT_SOURCE_CODE_POINTS = 8000;
    private static final int MAX_PROMPT_FILENAME_CODE_POINTS = 180;
    private static final int MAX_PROMPT_HEADING_CODE_POINTS = 300;

    public KnowledgeContext build(List<DocumentSearchHit> hits) {
        List<KnowledgeSource> sources = new ArrayList<>();
        Set<String> hashes = new HashSet<>();
        StringBuilder prompt = new StringBuilder();
        int remaining = MAX_PROMPT_SOURCE_CODE_POINTS;

        for (DocumentSearchHit hit : hits) {
            if (hit.similarity() < MIN_SIMILARITY || sources.size() >= MAX_SOURCES) {
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
                    content.original(), hit.contentHash(), hit.similarity(), rank));
        }
        return new KnowledgeContext(List.copyOf(sources), prompt.toString());
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }
}
