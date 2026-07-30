package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import org.junit.jupiter.api.Test;

import static com.shitulelv.aicollab.planning.application.TaskPlanActionPolicy.Action.*;
import static org.assertj.core.api.Assertions.assertThat;

class TaskPlanActionPolicyTest {
    private final TaskPlanActionPolicy policy = new TaskPlanActionPolicy();

    @Test
    void permissionsMatchCommandGuardsForEveryStatus() {
        for (TaskPlanStatus status : TaskPlanStatus.values()) {
            TaskPlanPermissions permissions = policy.permissions(status, true, false);

            assertThat(permissions.canEdit()).isEqualTo(policy.allows(status, EDIT));
            assertThat(permissions.canCancel()).isEqualTo(policy.allows(status, CANCEL));
            assertThat(permissions.canRetryDetail()).isEqualTo(policy.allows(status, RETRY_DETAIL));
            assertThat(permissions.canRegenerate()).isEqualTo(policy.allows(status, REGENERATE));
            assertThat(permissions.canConfirm()).isEqualTo(policy.allows(status, CONFIRM));
            assertThat(permissions.canDelete()).isEqualTo(policy.allows(status, DELETE));
            assertThat(permissions.canRestore()).isEqualTo(policy.allows(status, RESTORE));
            assertThat(permissions.canPartialRegenerate()).isEqualTo(policy.allows(status, PARTIAL_REGENERATE));
        }
    }

    @Test
    void edgeStatesHaveExplicitPermissions() {
        TaskPlanPermissions issues = policy.permissions(TaskPlanStatus.READY_WITH_ISSUES, true, true);
        assertThat(issues.canDelete()).isTrue();
        assertThat(issues.canRegenerate()).isTrue();
        assertThat(issues.canConfirm()).isFalse();

        TaskPlanPermissions repairing = policy.permissions(TaskPlanStatus.REPAIRING, true, false);
        assertThat(repairing.canCancel()).isTrue();
        assertThat(repairing).extracting(
                TaskPlanPermissions::canEdit,
                TaskPlanPermissions::canRetryDetail,
                TaskPlanPermissions::canRegenerate,
                TaskPlanPermissions::canConfirm,
                TaskPlanPermissions::canDelete,
                TaskPlanPermissions::canRestore,
                TaskPlanPermissions::canPartialRegenerate
        ).containsOnly(false);

        TaskPlanPermissions confirmed = policy.permissions(TaskPlanStatus.CONFIRMED, true, false);
        assertThat(confirmed).extracting(
                TaskPlanPermissions::canEdit,
                TaskPlanPermissions::canCancel,
                TaskPlanPermissions::canRetryDetail,
                TaskPlanPermissions::canRegenerate,
                TaskPlanPermissions::canConfirm,
                TaskPlanPermissions::canDelete,
                TaskPlanPermissions::canRestore,
                TaskPlanPermissions::canPartialRegenerate
        ).containsOnly(false);
    }

    @Test
    void blockingIssuesSuppressConfirmationWithoutChangingOtherReadyActions() {
        TaskPlanPermissions clean = policy.permissions(TaskPlanStatus.READY, true, false);
        TaskPlanPermissions blocked = policy.permissions(TaskPlanStatus.READY, true, true);

        assertThat(clean.canConfirm()).isTrue();
        assertThat(blocked.canConfirm()).isFalse();
        assertThat(blocked.canEdit()).isTrue();
        assertThat(blocked.canRegenerate()).isTrue();
    }

    @Test
    void readersNeverReceiveWritePermissions() {
        TaskPlanPermissions permissions = policy.permissions(TaskPlanStatus.READY, false, false);
        assertThat(permissions).extracting(
                TaskPlanPermissions::canEdit,
                TaskPlanPermissions::canCancel,
                TaskPlanPermissions::canRetryDetail,
                TaskPlanPermissions::canRegenerate,
                TaskPlanPermissions::canConfirm,
                TaskPlanPermissions::canDelete,
                TaskPlanPermissions::canRestore,
                TaskPlanPermissions::canPartialRegenerate
        ).containsOnly(false);
    }
}
