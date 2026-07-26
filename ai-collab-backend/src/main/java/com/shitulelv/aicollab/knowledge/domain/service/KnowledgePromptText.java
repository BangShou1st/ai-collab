package com.shitulelv.aicollab.knowledge.domain.service;

/**
 * Encodes untrusted question and document text before it is inserted into
 * XML-like prompt delimiters. This class treats all dynamic values as text,
 * never as prompt structure.
 */
public final class KnowledgePromptText {
    private KnowledgePromptText() {
    }

    public static String escapeXmlText(String value) {
        return escapeXmlText(value, Integer.MAX_VALUE).escaped();
    }

    public static EscapedText escapeXmlText(String value, int maximumEscapedCodePoints) {
        if (value == null || maximumEscapedCodePoints <= 0) {
            return new EscapedText("", "");
        }
        StringBuilder original = new StringBuilder();
        StringBuilder escaped = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String token = switch (codePoint) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                default -> new String(Character.toChars(codePoint));
            };
            int tokenLength = token.codePointCount(0, token.length());
            if (used > maximumEscapedCodePoints - tokenLength) {
                break;
            }
            original.appendCodePoint(codePoint);
            escaped.append(token);
            used += tokenLength;
            offset += Character.charCount(codePoint);
        }
        return new EscapedText(original.toString(), escaped.toString());
    }

    public record EscapedText(String original, String escaped) {
    }
}
