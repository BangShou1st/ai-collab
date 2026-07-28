package com.shitulelv.aicollab.planning.domain;

import java.util.Map;
import java.util.Set;

/**
 * Task 5: Defines which fields AI repair is allowed to modify and which are locked.
 *
 * Generated from validation issues via ValidationIssueCatalog.repairableFields().
 * Always locked: tempKey, milestoneTempKey, title, objective, sortOrder, non-target tasks.
 */
public record RepairScope(
        Set<String> targetTempKeys,
        Map<String, Set<String>> allowedFields,
        Set<String> lockedFields
) {
    public RepairScope {
        targetTempKeys = Set.copyOf(targetTempKeys);
        allowedFields = Map.copyOf(allowedFields);
        lockedFields = Set.copyOf(lockedFields);
    }

    /** Returns the allowed fields for a specific task/milestone tempKey, or empty set if not a target. */
    public Set<String> allowedFieldsFor(String tempKey) {
        return allowedFields.getOrDefault(tempKey, Set.of());
    }

    /** Returns true if the field is locked for the given tempKey. */
    public boolean isLocked(String tempKey, String field) {
        if (lockedFields.contains(field)) return true;
        return !allowedFieldsFor(tempKey).contains(field);
    }

    /** Always-locked fields that AI repair must never modify. */
    public static final Set<String> ALWAYS_LOCKED = Set.of(
            "tempKey", "milestoneTempKey", "title", "objective", "sortOrder"
    );
}
