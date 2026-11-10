package com.shitulelv.aicollab.retrieval.query;

/**
 * Supported operators for structured metadata filtering in retrieval.
 */
public enum FilterOperator {
    EQ("="),
    NEQ("!="),
    CONTAINS("contains"),
    IN("in"),
    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<=");

    private final String symbol;

    FilterOperator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public static FilterOperator fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Filter operator cannot be null or empty");
        }
        String normalized = raw.trim().toUpperCase();
        return switch (normalized) {
            case "=", "==", "EQ", "EQUALS" -> EQ;
            case "!=", "<>", "NEQ", "NOT_EQUALS", "NOT_EQ" -> NEQ;
            case "CONTAINS", "LIKE", "HAS" -> CONTAINS;
            case "IN" -> IN;
            case ">", "GT", "GREATER_THAN" -> GT;
            case ">=", "GTE", "GREATER_THAN_OR_EQUAL", "GE" -> GTE;
            case "<", "LT", "LESS_THAN" -> LT;
            case "<=", "LTE", "LESS_THAN_OR_EQUAL", "LE" -> LTE;
            default -> throw new IllegalArgumentException("Unsupported filter operator: '" + raw + "'");
        };
    }
}
