package com.shitulelv.aicollab.retrieval.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for extracting restricted structured metadata filters from natural language queries
 * or structured parameter payloads.
 *
 * Enforces a strict field whitelist and rejects illegal fields or unsupported operators
 * to prevent SQL injection and avoid silently widening search scope.
 */
public class MetadataFilterParser {
    private static final Logger log = LoggerFactory.getLogger(MetadataFilterParser.class);

    private static final Map<String, String> FIELD_ALIASES = Map.ofEntries(
            Map.entry("file", "originalFilename"),
            Map.entry("filename", "originalFilename"),
            Map.entry("originalfilename", "originalFilename"),
            Map.entry("name", "displayName"),
            Map.entry("doc", "displayName"),
            Map.entry("displayname", "displayName"),
            Map.entry("type", "mimeType"),
            Map.entry("mimetype", "mimeType"),
            Map.entry("format", "mimeType"),
            Map.entry("status", "status"),
            Map.entry("tag", "tags"),
            Map.entry("tags", "tags"),
            Map.entry("heading", "sectionHeading"),
            Map.entry("section", "sectionHeading"),
            Map.entry("sectionheading", "sectionHeading"),
            Map.entry("created", "createdAt"),
            Map.entry("createdat", "createdAt"),
            Map.entry("since", "createdAt"),
            Map.entry("after", "createdAt"),
            Map.entry("before", "createdAt"),
            Map.entry("updated", "updatedAt"),
            Map.entry("updatedat", "updatedAt")
    );

    private static final Set<String> PROTOCOL_PREFIXES = Set.of("http", "https", "ftp", "urn", "mailto");

    // Matches explicit key:value or key="value" or key='value'
    private static final Pattern EXPLICIT_KV_PATTERN = Pattern.compile(
            "(?:^|\\s+)([a-zA-Z_][a-zA-Z0-9_]*)\\s*[:=]\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s]+))"
    );

    // Matches document name in book brackets: 《...》
    private static final Pattern BOOK_TITLE_PATTERN = Pattern.compile(
            "(?:在|关于|查阅|参考|限定在|来自)?\\s*《([^《》]{1,200})》\\s*(?:中|里|文档|文件)?(?:的)?"
    );

    // Matches filename pattern: 文件名为 "..." or 文件 "..." 中
    private static final Pattern FILENAME_MENTION_PATTERN = Pattern.compile(
            "(?:文件名为|在文件|查看文件)\\s*[“\"']([^“”\"']{1,200})[”\"']\\s*(?:中|里)?(?:的)?"
    );

    // Matches section / heading mention: 在 "..." 章节中
    private static final Pattern HEADING_MENTION_PATTERN = Pattern.compile(
            "(?:在|关于)?\\s*[“\"']([^“”\"']{1,200})[”\"']\\s*(?:章节|小节|部分|标题)\\s*(?:中|里)?(?:的)?"
    );

    // Matches document format mentions
    private static final Pattern MIME_TYPE_PATTERN = Pattern.compile(
            "(?i)\\b(PDF|Markdown|Word|DOCX|TXT)\\s*(?:格式|文件|文档)?(?:的)?"
    );

    // Matches time mentions like 2026-10-01 之后 / 之前
    private static final Pattern TIME_AFTER_PATTERN = Pattern.compile(
            "(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(?:T\\d{2}:\\d{2}:\\d{2})?)\\s*(?:之后|以后|起)(?:创建)?"
    );
    private static final Pattern TIME_BEFORE_PATTERN = Pattern.compile(
            "(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(?:T\\d{2}:\\d{2}:\\d{2})?)\\s*(?:之前|以前|前)(?:创建)?"
    );

    /**
     * Parses a natural language query, extracting structured metadata filters
     * and returning the cleaned semantic query.
     *
     * @param rawQuery the user's input query
     * @return StructuredQuery containing clean semantic text and parsed filters
     */
    public StructuredQuery parse(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return new StructuredQuery("", List.of());
        }

        String queryText = rawQuery;
        List<MetadataFilter> filters = new ArrayList<>();

        // 1. Check for explicit key:value syntax
        Matcher kvMatcher = EXPLICIT_KV_PATTERN.matcher(queryText);
        List<int[]> matchedSpans = new ArrayList<>();

        while (kvMatcher.find()) {
            String key = kvMatcher.group(1).toLowerCase(Locale.ROOT);
            if (PROTOCOL_PREFIXES.contains(key)) {
                continue;
            }

            String val = kvMatcher.group(2);
            if (val == null) val = kvMatcher.group(3);
            if (val == null) val = kvMatcher.group(4);

            if (val == null || val.isBlank()) {
                throw new IllegalArgumentException("Filter value cannot be empty for field '" + key + "'");
            }

            if (!FIELD_ALIASES.containsKey(key)) {
                // Illegal/unrecognized field: must reject to prevent injection and silent scope widening
                throw new IllegalArgumentException(
                        "Disallowed metadata filter field: '" + key + "'. Whitelisted fields: " + MetadataFilter.ALLOWED_FIELDS);
            }

            String targetField = FIELD_ALIASES.get(key);
            FilterOperator op = determineOperatorForKey(key, val);
            String normalizedValue = normalizeFilterValue(targetField, val);

            filters.add(new MetadataFilter(targetField, op, normalizedValue));
            matchedSpans.add(new int[]{kvMatcher.start(), kvMatcher.end()});
        }

        // Remove explicit KV spans from text
        queryText = removeSpans(queryText, matchedSpans);

        // 2. Parse natural language book titles: 《...》
        Matcher bookMatcher = BOOK_TITLE_PATTERN.matcher(queryText);
        matchedSpans.clear();
        while (bookMatcher.find()) {
            String title = bookMatcher.group(1).trim();
            if (!title.isEmpty()) {
                filters.add(MetadataFilter.contains("originalFilename", title));
                matchedSpans.add(new int[]{bookMatcher.start(), bookMatcher.end()});
            }
        }
        queryText = removeSpans(queryText, matchedSpans);

        // 3. Parse explicit filename mentions: 文件名为 "..."
        Matcher fnMatcher = FILENAME_MENTION_PATTERN.matcher(queryText);
        matchedSpans.clear();
        while (fnMatcher.find()) {
            String filename = fnMatcher.group(1).trim();
            if (!filename.isEmpty()) {
                filters.add(MetadataFilter.contains("originalFilename", filename));
                matchedSpans.add(new int[]{fnMatcher.start(), fnMatcher.end()});
            }
        }
        queryText = removeSpans(queryText, matchedSpans);

        // 4. Parse heading mentions: "..." 章节
        Matcher headingMatcher = HEADING_MENTION_PATTERN.matcher(queryText);
        matchedSpans.clear();
        while (headingMatcher.find()) {
            String heading = headingMatcher.group(1).trim();
            if (!heading.isEmpty()) {
                filters.add(MetadataFilter.contains("sectionHeading", heading));
                matchedSpans.add(new int[]{headingMatcher.start(), headingMatcher.end()});
            }
        }
        queryText = removeSpans(queryText, matchedSpans);

        // 5. Parse mime-type mentions (PDF, Markdown, etc.)
        Matcher mimeMatcher = MIME_TYPE_PATTERN.matcher(queryText);
        matchedSpans.clear();
        while (mimeMatcher.find()) {
            String format = mimeMatcher.group(1).toUpperCase(Locale.ROOT);
            String mimeType = mapFormatToMimeType(format);
            if (mimeType != null) {
                filters.add(MetadataFilter.eq("mimeType", mimeType));
                matchedSpans.add(new int[]{mimeMatcher.start(), mimeMatcher.end()});
            }
        }
        queryText = removeSpans(queryText, matchedSpans);

        // 6. Parse time mentions
        Matcher timeAfterMatcher = TIME_AFTER_PATTERN.matcher(queryText);
        matchedSpans.clear();
        while (timeAfterMatcher.find()) {
            String timeStr = timeAfterMatcher.group(1).trim().replace('/', '-');
            filters.add(MetadataFilter.gte("createdAt", timeStr));
            matchedSpans.add(new int[]{timeAfterMatcher.start(), timeAfterMatcher.end()});
        }
        queryText = removeSpans(queryText, matchedSpans);

        Matcher timeBeforeMatcher = TIME_BEFORE_PATTERN.matcher(queryText);
        matchedSpans.clear();
        while (timeBeforeMatcher.find()) {
            String timeStr = timeBeforeMatcher.group(1).trim().replace('/', '-');
            filters.add(MetadataFilter.lte("createdAt", timeStr));
            matchedSpans.add(new int[]{timeBeforeMatcher.start(), timeBeforeMatcher.end()});
        }
        queryText = removeSpans(queryText, matchedSpans);

        // Clean up remaining semantic query
        String cleanedSemantic = cleanSemanticQuery(queryText);
        if (cleanedSemantic.isBlank() && !filters.isEmpty()) {
            // When query only specified filters, semantic query can be empty
            cleanedSemantic = "";
        }

        return new StructuredQuery(cleanedSemantic, filters);
    }

    /**
     * Parses structured filter specifications from parameter objects (e.g. JSON payloads).
     *
     * @param filterSpecs list of maps each containing "field", "operator", "value"
     * @return validated list of MetadataFilters
     */
    public List<MetadataFilter> parseStructuredFilters(List<Map<String, Object>> filterSpecs) {
        if (filterSpecs == null || filterSpecs.isEmpty()) {
            return List.of();
        }

        List<MetadataFilter> result = new ArrayList<>();
        for (Map<String, Object> spec : filterSpecs) {
            if (spec == null || spec.isEmpty()) {
                throw new IllegalArgumentException("Filter specification map cannot be null or empty");
            }

            Object fieldObj = spec.get("field");
            if (fieldObj == null || fieldObj.toString().isBlank()) {
                throw new IllegalArgumentException("Missing required 'field' in filter specification");
            }
            String rawField = fieldObj.toString().trim();
            String canonicalField = resolveCanonicalField(rawField);
            if (canonicalField == null) {
                throw new IllegalArgumentException(
                        "Disallowed metadata filter field: '" + rawField + "'. Whitelisted fields: " + MetadataFilter.ALLOWED_FIELDS);
            }

            Object opObj = spec.get("operator");
            FilterOperator operator = opObj != null
                    ? FilterOperator.fromString(opObj.toString())
                    : FilterOperator.EQ;

            Object value = spec.get("value");
            if (value == null) {
                throw new IllegalArgumentException("Missing required 'value' in filter specification for field '" + canonicalField + "'");
            }

            result.add(new MetadataFilter(canonicalField, operator, value));
        }

        return result;
    }

    /**
     * Parses a structured key-value map into metadata filters with strict whitelist checking.
     */
    public List<MetadataFilter> parseStructuredMap(Map<String, Object> filterMap) {
        if (filterMap == null || filterMap.isEmpty()) {
            return List.of();
        }

        List<MetadataFilter> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : filterMap.entrySet()) {
            String rawField = entry.getKey();
            String canonicalField = resolveCanonicalField(rawField);
            if (canonicalField == null) {
                throw new IllegalArgumentException(
                        "Disallowed metadata filter field in map: '" + rawField + "'. Whitelisted fields: " + MetadataFilter.ALLOWED_FIELDS);
            }

            Object val = entry.getValue();
            if (val == null) {
                throw new IllegalArgumentException("Filter value cannot be null for field '" + canonicalField + "'");
            }

            FilterOperator op = (val instanceof Collection<?> || val.getClass().isArray())
                    ? FilterOperator.IN
                    : FilterOperator.EQ;

            result.add(new MetadataFilter(canonicalField, op, val));
        }

        return result;
    }

    private String resolveCanonicalField(String field) {
        if (field == null) return null;
        String trimmed = field.trim();
        if (MetadataFilter.ALLOWED_FIELDS.contains(trimmed)) {
            return trimmed;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return FIELD_ALIASES.get(lower);
    }

    private FilterOperator determineOperatorForKey(String key, String value) {
        return switch (key) {
            case "since", "after" -> FilterOperator.GTE;
            case "before" -> FilterOperator.LTE;
            case "file", "filename", "name", "doc", "heading", "section" -> FilterOperator.CONTAINS;
            default -> FilterOperator.EQ;
        };
    }

    private String normalizeFilterValue(String field, String val) {
        if (val == null) return "";
        String trimmed = val.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            if (trimmed.length() >= 2) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        if ("mimeType".equals(field)) {
            String mapped = mapFormatToMimeType(trimmed);
            if (mapped != null) return mapped;
        }
        return trimmed;
    }

    private String mapFormatToMimeType(String format) {
        if (format == null) return null;
        return switch (format.toUpperCase(Locale.ROOT)) {
            case "PDF" -> "application/pdf";
            case "MARKDOWN", "MD" -> "text/markdown";
            case "WORD", "DOCX" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "TXT", "TEXT" -> "text/plain";
            default -> format.toLowerCase(Locale.ROOT);
        };
    }

    private String removeSpans(String text, List<int[]> spans) {
        if (spans.isEmpty()) return text;
        // Sort spans descending by start position to delete without offset corruption
        spans.sort((a, b) -> Integer.compare(b[0], a[0]));
        StringBuilder sb = new StringBuilder(text);
        for (int[] span : spans) {
            sb.delete(span[0], span[1]);
        }
        return sb.toString();
    }

    private String cleanSemanticQuery(String text) {
        if (text == null) return "";
        // Clean leading/trailing punctuation and extra whitespace
        String cleaned = text.replaceAll("^[，,。；;：:\\s]+", "")
                             .replaceAll("[，,。；;：:\\s]+$", "")
                             .replaceAll("\\s+", " ")
                             .trim();
        return cleaned;
    }
}
