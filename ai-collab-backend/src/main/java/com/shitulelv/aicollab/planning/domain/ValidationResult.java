package com.shitulelv.aicollab.planning.domain;

import java.util.List;

public record ValidationResult(List<String> errorCodes, List<String> warningCodes) {
    public ValidationResult {
        errorCodes = List.copyOf(errorCodes);
        warningCodes = List.copyOf(warningCodes);
    }

    public boolean valid() {
        return errorCodes.isEmpty();
    }
}
