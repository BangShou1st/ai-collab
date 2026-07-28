package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Single authority for UI permissions and command state guards.
 */
@Component
public class TaskPlanActionPolicy {
    public enum Action {
        EDIT,
        CANCEL,
        RETRY_DETAIL,
        REGENERATE,
        CONFIRM,
        DELETE,
        RESTORE,
        PARTIAL_REGENERATE
    }

    private static final Set<TaskPlanStatus> EDITABLE =
            EnumSet.of(TaskPlanStatus.READY, TaskPlanStatus.READY_WITH_ISSUES);
    private static final Set<TaskPlanStatus> REGENERATABLE =
            EnumSet.of(TaskPlanStatus.READY, TaskPlanStatus.READY_WITH_ISSUES,
                    TaskPlanStatus.FAILED, TaskPlanStatus.DETAIL_GENERATION_FAILED,
                    TaskPlanStatus.CANCELED);

    public boolean allows(TaskPlanStatus status, Action action) {
        return switch (action) {
            case EDIT, RESTORE, PARTIAL_REGENERATE -> EDITABLE.contains(status);
            case CANCEL -> status.isGenerating();
            case RETRY_DETAIL -> status == TaskPlanStatus.DETAIL_GENERATION_FAILED;
            case REGENERATE, DELETE -> REGENERATABLE.contains(status);
            case CONFIRM -> status == TaskPlanStatus.READY;
        };
    }

    public void require(TaskPlanStatus status, Action action) {
        if (!allows(status, action)) {
            throw new BusinessException(ErrorCode.TASK_PLAN_STATE_CONFLICT);
        }
    }

    public TaskPlanPermissions permissions(TaskPlanStatus status, boolean canWrite,
                                           boolean hasBlockingIssues) {
        if (!canWrite) {
            return new TaskPlanPermissions(false, false, false, false,
                    false, false, false, false);
        }
        return new TaskPlanPermissions(
                allows(status, Action.EDIT),
                allows(status, Action.CANCEL),
                allows(status, Action.RETRY_DETAIL),
                allows(status, Action.REGENERATE),
                allows(status, Action.CONFIRM) && !hasBlockingIssues,
                allows(status, Action.DELETE),
                allows(status, Action.RESTORE),
                allows(status, Action.PARTIAL_REGENERATE));
    }
}
