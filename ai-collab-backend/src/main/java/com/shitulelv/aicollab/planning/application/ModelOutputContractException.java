package com.shitulelv.aicollab.planning.application;

import java.util.List;

/**
 * S4: Safe structured failure diagnosis for model output parsing.
 * Carries only category, jsonPath, and validation codes — no raw output, no prompt content.
 */
class ModelOutputContractException extends RuntimeException {
    private final String category;
    private final String jsonPath;
    private final List<String> validationCodes;

    ModelOutputContractException(String category, String jsonPath) {
        this(category, jsonPath, List.of());
    }

    ModelOutputContractException(String category, String jsonPath, List<String> validationCodes) {
        super(category + (jsonPath != null ? " / " + jsonPath : ""));
        this.category = category;
        this.jsonPath = jsonPath;
        this.validationCodes = validationCodes != null ? validationCodes : List.of();
    }

    String category() { return category; }
    String jsonPath() { return jsonPath; }
    List<String> validationCodes() { return validationCodes; }

    /** Build a safe error summary for persistence (max 300 code points). */
    String safeSummary(String stage) {
        String base = stage + " / " + category + (jsonPath != null ? " / " + jsonPath : "");
        return base.codePointCount(0, Math.min(base.length(), base.codePointCount(0, base.length())))
                > 300 ? base.substring(0, 300) : base;
    }
}
