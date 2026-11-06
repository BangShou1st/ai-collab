package com.shitulelv.aicollab.retrieval.query;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Restricted structured metadata filter model for document and chunk retrieval.
 * Strict field whitelist and operator validation prevent arbitrary SQL injection
 * and ensure scoped access.
 */
public record MetadataFilter(
        String field,
        FilterOperator operator,
        Object value) {

    public static final Set<String> ALLOWED_FIELDS = Set.of(
            "originalFilename",
            "displayName",
            "mimeType",
            "createdAt",
            "updatedAt",
            "status",
            "tags",
            "sectionHeading"
    );

    private static final Map<String, Set<FilterOperator>> FIELD_OPERATORS = Map.of(
            "originalFilename", Set.of(FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.CONTAINS, FilterOperator.IN),
            "displayName", Set.of(FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.CONTAINS, FilterOperator.IN),
            "mimeType", Set.of(FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.IN),
            "createdAt", Set.of(FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.GT, FilterOperator.GTE, FilterOperator.LT, FilterOperator.LTE),
            "updatedAt", Set.of(FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.GT, FilterOperator.GTE, FilterOperator.LT, FilterOperator.LTE),
            "status", Set.of(FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.IN),
            "tags", Set.of(FilterOperator.EQ, FilterOperator.CONTAINS, FilterOperator.IN),
            "sectionHeading", Set.of(FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.CONTAINS, FilterOperator.IN)
    );

    public MetadataFilter {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("Metadata filter field cannot be null or empty");
        }
        field = field.trim();
        if (!ALLOWED_FIELDS.contains(field)) {
            throw new IllegalArgumentException(
                    "Illegal metadata filter field: '" + field + "'. Whitelisted fields are: " + ALLOWED_FIELDS);
        }

        if (operator == null) {
            throw new IllegalArgumentException("Metadata filter operator cannot be null for field '" + field + "'");
        }

        Set<FilterOperator> supportedOperators = FIELD_OPERATORS.get(field);
        if (supportedOperators == null || !supportedOperators.contains(operator)) {
            throw new IllegalArgumentException(
                    "Operator '" + operator + "' is not supported for field '" + field + "'. Supported: " + supportedOperators);
        }

        if (value == null) {
            throw new IllegalArgumentException("Metadata filter value cannot be null for field '" + field + "'");
        }
        if (value instanceof String s) {
            if (s.isBlank()) {
                throw new IllegalArgumentException("Metadata filter string value cannot be blank for field '" + field + "'");
            }
            if (s.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Metadata filter string value contains null byte");
            }
        }
        if (operator == FilterOperator.IN) {
            if (value instanceof Collection<?> c) {
                if (c.isEmpty()) {
                    throw new IllegalArgumentException("Filter value collection cannot be empty for operator IN");
                }
            } else if (value.getClass().isArray()) {
                if (java.lang.reflect.Array.getLength(value) == 0) {
                    throw new IllegalArgumentException("Filter value array cannot be empty for operator IN");
                }
            }
        }
    }

    public static MetadataFilter eq(String field, Object value) {
        return new MetadataFilter(field, FilterOperator.EQ, value);
    }

    public static MetadataFilter neq(String field, Object value) {
        return new MetadataFilter(field, FilterOperator.NEQ, value);
    }

    public static MetadataFilter contains(String field, Object value) {
        return new MetadataFilter(field, FilterOperator.CONTAINS, value);
    }

    public static MetadataFilter in(String field, Collection<?> values) {
        return new MetadataFilter(field, FilterOperator.IN, values);
    }

    public static MetadataFilter gt(String field, Object value) {
        return new MetadataFilter(field, FilterOperator.GT, value);
    }

    public static MetadataFilter gte(String field, Object value) {
        return new MetadataFilter(field, FilterOperator.GTE, value);
    }

    public static MetadataFilter lt(String field, Object value) {
        return new MetadataFilter(field, FilterOperator.LT, value);
    }

    public static MetadataFilter lte(String field, Object value) {
        return new MetadataFilter(field, FilterOperator.LTE, value);
    }

    /**
     * Evaluates this filter against an in-memory document/chunk metadata map.
     */
    public boolean matches(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }

        Object target = metadata.get(field);
        if (target == null && "sectionHeading".equals(field)) {
            // Also check 'heading' alias if present in chunk metadata
            target = metadata.get("heading");
        }
        if (target == null) {
            return false;
        }

        return switch (operator) {
            case EQ -> compareEquals(target, value);
            case NEQ -> !compareEquals(target, value);
            case CONTAINS -> compareContains(target, value);
            case IN -> compareIn(target, value);
            case GT -> compareComparable(target, value) > 0;
            case GTE -> compareComparable(target, value) >= 0;
            case LT -> compareComparable(target, value) < 0;
            case LTE -> compareComparable(target, value) <= 0;
        };
    }

    private boolean compareEquals(Object target, Object expected) {
        if (target == null || expected == null) return false;
        if (target instanceof String s1 && expected instanceof String s2) {
            return s1.equalsIgnoreCase(s2.trim());
        }
        return Objects.equals(target.toString(), expected.toString());
    }

    private boolean compareContains(Object target, Object expected) {
        if (target == null || expected == null) return false;
        if (target instanceof Collection<?> coll) {
            String expStr = expected.toString().trim();
            for (Object item : coll) {
                if (item != null && item.toString().equalsIgnoreCase(expStr)) {
                    return true;
                }
            }
            return false;
        }
        return target.toString().toLowerCase(Locale.ROOT)
                .contains(expected.toString().toLowerCase(Locale.ROOT).trim());
    }

    private boolean compareIn(Object target, Object expected) {
        if (target == null || expected == null) return false;
        Collection<?> candidates;
        if (expected instanceof Collection<?> coll) {
            candidates = coll;
        } else if (expected.getClass().isArray()) {
            candidates = Arrays.asList((Object[]) expected);
        } else {
            candidates = List.of(expected);
        }
        String targetStr = target.toString().trim();
        for (Object item : candidates) {
            if (item != null && item.toString().equalsIgnoreCase(targetStr)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareComparable(Object target, Object expected) {
        if (target instanceof Comparable c1 && target.getClass().isInstance(expected)) {
            return c1.compareTo(expected);
        }
        // Try parsing dates if comparing timestamps
        if (isTemporalField()) {
            Instant t1 = parseInstant(target);
            Instant t2 = parseInstant(expected);
            if (t1 != null && t2 != null) {
                return t1.compareTo(t2);
            }
        }
        return target.toString().compareTo(expected.toString());
    }

    private boolean isTemporalField() {
        return "createdAt".equals(field) || "updatedAt".equals(field);
    }

    private Instant parseInstant(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Instant inst) return inst;
        if (obj instanceof OffsetDateTime odt) return odt.toInstant();
        if (obj instanceof Date d) return d.toInstant();
        try {
            return OffsetDateTime.parse(obj.toString()).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(obj.toString());
            } catch (DateTimeParseException ignored2) {
                return null;
            }
        }
    }
}
