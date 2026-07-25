package com.shitulelv.aicollab.work.domain.policy;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class TaskStatusPolicy {
    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = transitions();

    public void validateTransition(TaskStatus current, TaskStatus target, int unfinishedDependencyCount) {
        if (current == target) {
            return;
        }
        if (!TRANSITIONS.get(current).contains(target)) {
            throw new BusinessException(ErrorCode.TASK_INVALID_STATUS_TRANSITION);
        }
        if ((target == TaskStatus.IN_PROGRESS || target == TaskStatus.DONE)
                && unfinishedDependencyCount > 0) {
            throw new BusinessException(ErrorCode.TASK_BLOCKED_BY_DEPENDENCY);
        }
    }

    private static Map<TaskStatus, Set<TaskStatus>> transitions() {
        Map<TaskStatus, Set<TaskStatus>> result = new EnumMap<>(TaskStatus.class);
        result.put(TaskStatus.TODO, EnumSet.of(TaskStatus.IN_PROGRESS, TaskStatus.BLOCKED, TaskStatus.CANCELED));
        result.put(TaskStatus.IN_PROGRESS,
                EnumSet.of(TaskStatus.TODO, TaskStatus.BLOCKED, TaskStatus.DONE, TaskStatus.CANCELED));
        result.put(TaskStatus.BLOCKED, EnumSet.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.CANCELED));
        result.put(TaskStatus.DONE, EnumSet.of(TaskStatus.IN_PROGRESS));
        result.put(TaskStatus.CANCELED, EnumSet.of(TaskStatus.TODO));
        return Map.copyOf(result);
    }
}
