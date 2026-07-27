package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.domain.ValidationAssessment;
import org.springframework.stereotype.Component;

/**
 * Task 6: Centralized decision logic for generation outcomes.
 *
 * All status判断集中在这里，不得散落在多个 catch 中。
 *
 * Rules:
 * - HARD issues → FAILED
 * - BLOCKING_EDITABLE issues → READY_WITH_ISSUES
 * - No issues or warnings only → READY
 */
@Component
public class GenerationOutcomeDecider {

    public enum GenerationOutcome {
        READY,
        READY_WITH_ISSUES,
        FAILED
    }

    /**
     * Decide the final status based on validation assessment.
     */
    public GenerationOutcome decide(ValidationAssessment assessment) {
        if (assessment.hasHardIssues()) {
            return GenerationOutcome.FAILED;
        }
        if (assessment.hasBlockingEditableIssues()) {
            return GenerationOutcome.READY_WITH_ISSUES;
        }
        return GenerationOutcome.READY;
    }

    /**
     * Map outcome to the target TaskPlanStatus.
     */
    public TaskPlanStatus toStatus(GenerationOutcome outcome) {
        return switch (outcome) {
            case READY -> TaskPlanStatus.READY;
            case READY_WITH_ISSUES -> TaskPlanStatus.READY_WITH_ISSUES;
            case FAILED -> TaskPlanStatus.FAILED;
        };
    }

    /**
     * Convenience: decide and map to status in one call.
     */
    public TaskPlanStatus decideStatus(ValidationAssessment assessment) {
        return toStatus(decide(assessment));
    }
}
