package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.domain.StructuredValidationIssue;
import java.util.List;

/** Only safe, structured locations; never retains raw model output. */
public final class TaskPlanRepairRejectedException extends BusinessException {
    private final List<StructuredValidationIssue> issues;
    public TaskPlanRepairRejectedException(ErrorCode code, List<StructuredValidationIssue> issues) {
        super(code);
        this.issues = List.copyOf(issues);
    }
    public List<StructuredValidationIssue> issues() { return issues; }
}
