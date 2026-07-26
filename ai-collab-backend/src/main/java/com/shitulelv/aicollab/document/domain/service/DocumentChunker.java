package com.shitulelv.aicollab.document.domain.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Component
public class DocumentChunker {
    private static final int TARGET = 1200;
    private static final int OVERLAP = 150;
    private static final int MAX_CHUNKS = 1000;
    private static final int MAX_HEADING_CODE_POINTS = 300;

    public List<DocumentChunk> split(String text) {
        List<Section> sections = sections(text);
        List<DocumentChunk> result = new ArrayList<>();
        for (Section section : sections) {
            int start = 0;
            while (start < section.content().length()) {
                int end = chooseEnd(section.content(), start);
                String content = section.content().substring(start, end).trim();
                if (!content.isBlank()) {
                    if (result.size() >= MAX_CHUNKS) {
                        throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                                "文档内容过长，生成的分块数量超过上限");
                    }
                    result.add(new DocumentChunk(result.size(), truncateHeading(section.heading()), content,
                            sha256(content), Math.max(1,
                            (content.codePointCount(0, content.length()) + 3) / 4)));
                }
                if (end >= section.content().length()) break;
                int next = safeBoundary(section.content(), Math.max(start + 1, end - OVERLAP));
                start = next > start ? next : section.content().offsetByCodePoints(start, 1);
            }
        }
        return result;
    }

    private static List<Section> sections(String text) {
        List<Section> result = new ArrayList<>();
        String heading = null;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\n")) {
            String trimmed = line.trim();
            boolean markdownHeading = trimmed.matches("^#{1,6}\\s+.+");
            boolean inferredHeading = !trimmed.isBlank()
                    && trimmed.codePointCount(0, trimmed.length()) <= 80
                    && !trimmed.matches(".*[。！？.!?；;]$") && body.length() > 0;
            if (markdownHeading || inferredHeading && body.toString().endsWith("\n\n")) {
                flush(result, heading, body);
                heading = markdownHeading ? trimmed.replaceFirst("^#{1,6}\\s+", "") : trimmed;
            } else {
                body.append(line).append('\n');
            }
        }
        flush(result, heading, body);
        return result;
    }

    private static void flush(List<Section> result, String heading, StringBuilder body) {
        String content = body.toString().trim();
        if (!content.isBlank()) result.add(new Section(heading, content));
        body.setLength(0);
    }

    private static int chooseEnd(String content, int start) {
        int proposed = safeBoundary(content, Math.min(content.length(), start + TARGET));
        if (proposed == content.length()) return proposed;
        int paragraph = content.lastIndexOf("\n\n", proposed);
        int sentence = Math.max(content.lastIndexOf('。', proposed), content.lastIndexOf('.', proposed));
        int boundary = Math.max(paragraph, sentence < 0 ? -1 : sentence + 1);
        return boundary > start + TARGET / 2 ? boundary : proposed;
    }

    private static int safeBoundary(String value, int index) {
        int bounded = Math.max(0, Math.min(index, value.length()));
        if (bounded > 0 && bounded < value.length()
                && Character.isHighSurrogate(value.charAt(bounded - 1))
                && Character.isLowSurrogate(value.charAt(bounded))) {
            return bounded - 1;
        }
        return bounded;
    }

    private static String truncateHeading(String heading) {
        if (heading == null) return null;
        int count = heading.codePointCount(0, heading.length());
        return count <= MAX_HEADING_CODE_POINTS ? heading
                : heading.substring(0, heading.offsetByCodePoints(0, MAX_HEADING_CODE_POINTS));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private record Section(String heading, String content) {
    }
}
