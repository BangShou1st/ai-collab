package com.shitulelv.aicollab.planning.domain;

/**
 * Task 5: Distinguish "field omitted" from "field explicitly null" in patch DTOs.
 *
 * - absent() → field not in JSON → do not modify
 * - of(value) → field present in JSON → set to value (may be null to clear)
 */
public record PatchValue<T>(boolean present, T value) {
    public static <T> PatchValue<T> absent() {
        return new PatchValue<>(false, null);
    }

    public static <T> PatchValue<T> of(T value) {
        return new PatchValue<>(true, value);
    }

    public boolean isNull() {
        return present && value == null;
    }

    public T orElse(T defaultValue) {
        return present ? value : defaultValue;
    }
}
