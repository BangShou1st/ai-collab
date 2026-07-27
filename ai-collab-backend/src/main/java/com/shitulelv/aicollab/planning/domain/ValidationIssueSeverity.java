package com.shitulelv.aicollab.planning.domain;

/**
 * Task 2: Centralized severity classification for validation issues.
 *
 * HARD — structural/contract errors that cannot be edited away (JSON syntax, unknown property, tempKey duplicate, etc.)
 * BLOCKING_EDITABLE — business conflicts that can be resolved by editing or AI repair (date conflicts, missing assignee, etc.)
 * WARNING — non-blocking observations (unassigned, no source, duplicate title)
 */
public enum ValidationIssueSeverity {
    HARD,
    BLOCKING_EDITABLE,
    WARNING
}
