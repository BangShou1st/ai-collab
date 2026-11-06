package com.shitulelv.aicollab.document.domain.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.infrastructure.parser.ParsedDocument;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DocumentChunker {
    private static final int TARGET = 1000;
    private static final int OVERLAP = 120;
    private static final int MAX_CHUNKS = 1000;
    private static final int MAX_HEADING_CODE_POINTS = 300;

    public List<DocumentChunk> split(String text) {
        return split(text, List.of(), false);
    }

    public List<DocumentChunk> split(String text, boolean enableSentenceIndexing) {
        return split(text, List.of(), enableSentenceIndexing);
    }

    public List<DocumentChunk> split(String text, List<ParsedDocument.PageBoundary> pageBoundaries) {
        return split(text, pageBoundaries, false);
    }

    public List<DocumentChunk> split(String text, List<ParsedDocument.PageBoundary> pageBoundaries, boolean enableSentenceIndexing) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<Section> sections = parseSections(text);
        List<DocumentChunk> result = new ArrayList<>();

        for (Section section : sections) {
            String sectionContent = section.content();
            int sectionOffset = section.offset();
            int len = sectionContent.length();
            int sentenceSeq = 0;

            // 若整节偏小，直接作为单块 (STANDARD)
            if (len <= TARGET + 200) {
                UUID stdId = UUID.randomUUID();
                boolean table = isTable(sectionContent);
                boolean code = isCode(sectionContent);
                String cType = DocumentChunk.TYPE_STANDARD;

                Map<String, Object> extra = new LinkedHashMap<>();
                if (table) extra.put("isTable", true);
                if (code) extra.put("isCode", true);

                addChunk(result, stdId, section.heading(), sectionContent, sectionOffset, sectionOffset + len,
                        pageBoundaries, extra, null, cType);

                if (enableSentenceIndexing && !table && !code) {
                    sentenceSeq = extractAndAddSentences(result, sectionContent, sectionOffset, section.heading(),
                            pageBoundaries, stdId, sentenceSeq);
                }
                continue;
            }

            // 较长章节：生成真实的 DocumentChunk.TYPE_PARENT 块（生成 UUID parentId），子分块为 DocumentChunk.TYPE_CHILD，并且 parentChunkId = parentId
            UUID parentId = UUID.randomUUID();
            String parentSnippet = truncateParentSnippet(sectionContent, 500);

            Map<String, Object> parentMeta = new LinkedHashMap<>();
            parentMeta.put("isParent", true);

            addChunk(result, parentId, section.heading(), sectionContent, sectionOffset, sectionOffset + len,
                    pageBoundaries, parentMeta, null, DocumentChunk.TYPE_PARENT);

            // 生成子分块 (Child Chunks)
            int start = 0;
            while (start < len) {
                int end = chooseStructuralEnd(sectionContent, start);
                String raw = sectionContent.substring(start, end);
                String content = raw.trim();
                int leading = raw.indexOf(content);
                int sourceStart = sectionOffset + start + Math.max(0, leading);
                int sourceEnd = sourceStart + content.length();

                if (!content.isBlank()) {
                    UUID childId = UUID.randomUUID();
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("parentHeading", section.heading());
                    extra.put("parentSnippet", parentSnippet);
                    extra.put("parentCharFrom", sectionOffset);
                    extra.put("parentCharThrough", sectionOffset + len);
                    extra.put("parentChunkId", parentId.toString());

                    boolean childTable = isTable(content);
                    boolean childCode = isCode(content);
                    String childType = DocumentChunk.TYPE_CHILD;
                    if (childTable) extra.put("isTable", true);
                    if (childCode) extra.put("isCode", true);

                    addChunk(result, childId, section.heading(), content, sourceStart, sourceEnd,
                            pageBoundaries, extra, parentId, childType);

                    if (enableSentenceIndexing && !childTable && !childCode) {
                        sentenceSeq = extractAndAddSentences(result, content, sourceStart, section.heading(),
                                pageBoundaries, childId, sentenceSeq);
                    }
                }

                if (end >= len) break;
                int next = safeBoundary(sectionContent, Math.max(start + 1, end - OVERLAP));
                start = next > start ? next : sectionContent.offsetByCodePoints(start, 1);
            }
        }

        return result;
    }

    private static boolean isTable(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        return (trimmed.contains("|") && trimmed.contains("\n|")) || (trimmed.startsWith("|") && trimmed.endsWith("|"));
    }

    private static boolean isCode(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        return trimmed.startsWith("```") || trimmed.contains("\n```");
    }

    private static boolean isTableOrCodeBlock(String text) {
        return isTable(text) || isCode(text);
    }

    private int extractAndAddSentences(List<DocumentChunk> result, String text, int baseOffset, String heading,
                                      List<ParsedDocument.PageBoundary> pageBoundaries, UUID parentChunkId, int startSeq) {
        int seq = startSeq;
        int len = text.length();
        int sentStart = 0;

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            boolean isBoundary = (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '；' || c == ';');
            if (isBoundary || i == len - 1) {
                int sentEnd = i + 1;
                String sentence = text.substring(sentStart, sentEnd).trim();
                if (sentence.length() >= 8) {
                    int leading = text.substring(sentStart, sentEnd).indexOf(sentence);
                    int globalStart = baseOffset + sentStart + Math.max(0, leading);
                    int globalEnd = globalStart + sentence.length();

                    Map<String, Object> sentMeta = new LinkedHashMap<>();
                    sentMeta.put("sentenceIndex", seq++);
                    sentMeta.put("parentChunkId", parentChunkId.toString());
                    sentMeta.put("parentHeading", heading);
                    sentMeta.put("chunkType", DocumentChunk.TYPE_SENTENCE);

                    addChunk(result, UUID.randomUUID(), heading, sentence, globalStart, globalEnd,
                            pageBoundaries, sentMeta, parentChunkId, DocumentChunk.TYPE_SENTENCE);
                }
                sentStart = sentEnd;
            }
        }
        return seq;
    }

    private void addChunk(List<DocumentChunk> result, UUID id, String heading, String content,
                          int sourceStart, int sourceEnd,
                          List<ParsedDocument.PageBoundary> pageBoundaries,
                          Map<String, Object> extraMetadata, UUID parentChunkId, String chunkType) {
        if (result.size() >= MAX_CHUNKS) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                    "文档内容过长，生成的分块数量超过上限");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("charFrom", sourceStart);
        metadata.put("charThrough", sourceEnd);
        metadata.put("offsetCoordinate", "CLEANED_TEXT_UTF16_V2");
        Integer pageNumber = resolvePageNumber(sourceStart, pageBoundaries);
        if (pageNumber != null) {
            metadata.put("pageNumber", pageNumber);
            metadata.put("pageThrough", resolvePageNumber(Math.max(sourceStart, sourceEnd - 1), pageBoundaries));
        }
        if (parentChunkId != null) {
            metadata.put("parentChunkId", parentChunkId.toString());
        }
        if (chunkType != null) {
            metadata.put("chunkType", chunkType);
        }
        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }

        int tokenEstimate = Math.max(1, (content.codePointCount(0, content.length()) + 3) / 4);
        result.add(new DocumentChunk(
                id,
                result.size(),
                truncateHeading(heading),
                content,
                sha256(content),
                tokenEstimate,
                metadata,
                parentChunkId,
                chunkType != null ? chunkType : DocumentChunk.TYPE_STANDARD
        ));
    }

    private static Integer resolvePageNumber(int charOffset, List<ParsedDocument.PageBoundary> boundaries) {
        if (boundaries == null || boundaries.isEmpty()) return null;
        Integer pageNumber = null;
        for (ParsedDocument.PageBoundary boundary : boundaries) {
            if (boundary.charOffset() <= charOffset) {
                pageNumber = boundary.pageNumber();
            } else {
                break;
            }
        }
        return pageNumber;
    }

    private static List<Section> parseSections(String text) {
        List<Section> result = new ArrayList<>();
        String currentHeading = null;
        int bodyStart = 0;
        int offset = 0;

        String[] lines = text.split("\\n", -1);
        boolean inCodeBlock = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
            }

            // 在代码块外部才识别标题
            if (!inCodeBlock) {
                boolean markdownHeading = trimmed.matches("^#{1,6}\\s+.+");
                boolean legalClauseHeading = trimmed.matches("^(第[0-9一二三四五六七八九十百]+[条章节款]|条款\\s*[0-9]+).*");
                boolean inferredHeading = !trimmed.isBlank()
                        && trimmed.codePointCount(0, trimmed.length()) <= 80
                        && !trimmed.matches(".*[。！？.!?；;]$")
                        && offset > bodyStart;

                if (markdownHeading || legalClauseHeading || (inferredHeading && text.substring(bodyStart, Math.min(offset, text.length())).endsWith("\n\n"))) {
                    flush(result, currentHeading, text, bodyStart, Math.min(offset, text.length()));
                    currentHeading = markdownHeading ? trimmed.replaceFirst("^#{1,6}\\s+", "") : trimmed;
                    bodyStart = Math.min(text.length(), offset + line.length() + 1);
                }
            }
            offset += line.length() + 1;
        }

        flush(result, currentHeading, text, bodyStart, text.length());
        return result;
    }

    private static void flush(List<Section> result, String heading, String text, int start, int end) {
        if (start >= end || start >= text.length()) return;
        String raw = text.substring(start, end);
        String content = raw.trim();
        if (!content.isBlank()) {
            result.add(new Section(heading, content, start + raw.indexOf(content)));
        }
    }

    private static int chooseStructuralEnd(String content, int start) {
        int proposed = safeBoundary(content, Math.min(content.length(), start + TARGET));
        if (proposed == content.length()) return proposed;

        // 避免在代码块内切断
        int codeBlockTicks = countOccurrences(content.substring(start, proposed), "```");
        if (codeBlockTicks % 2 != 0) {
            // 代码块未闭合，尝试找到下一个 ``` 闭合点
            int closeTick = content.indexOf("```", proposed);
            if (closeTick != -1 && closeTick - start <= TARGET * 1.5) {
                int nextLine = content.indexOf('\n', closeTick + 3);
                return nextLine != -1 ? nextLine : closeTick + 3;
            }
        }

        // 段落优先
        int paragraph = content.lastIndexOf("\n\n", proposed);
        if (paragraph > start + TARGET / 2) {
            return paragraph;
        }

        // 句子边界
        int sentence = Math.max(
                Math.max(content.lastIndexOf('。', proposed), content.lastIndexOf('.', proposed)),
                Math.max(content.lastIndexOf('！', proposed), content.lastIndexOf('；', proposed))
        );
        int boundary = sentence < 0 ? -1 : sentence + 1;
        return boundary > start + TARGET / 2 ? boundary : proposed;
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static String truncateParentSnippet(String content, int maxChars) {
        if (content == null || content.isBlank()) return "";
        String trimmed = content.strip();
        if (trimmed.length() <= maxChars) return trimmed;
        return trimmed.substring(0, safeBoundary(trimmed, maxChars)) + "...";
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

    private record Section(String heading, String content, int offset) {
    }
}
