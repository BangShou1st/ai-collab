package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.domain.StructuredValidationIssue;
import java.util.List;

public final class RepairPatchFormatException extends IllegalArgumentException {
    private final List<StructuredValidationIssue> issues;
    public RepairPatchFormatException(StructuredValidationIssue issue) {
        super("Invalid repair patch JSON");
        issues = List.of(issue);
    }
    public List<StructuredValidationIssue> issues() { return issues; }
}
