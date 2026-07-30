package com.shitulelv.aicollab.project.domain.policy;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.model.ProjectStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectWritePolicyTest {

    @Test
    void preparingAndActiveProjectsAreWritable() {
        assertDoesNotThrow(() -> ProjectWritePolicy.requireWritable(ProjectStatus.PREPARING));
        assertDoesNotThrow(() -> ProjectWritePolicy.requireWritable(ProjectStatus.ACTIVE));
    }

    @Test
    void completedAndArchivedProjectsAreReadOnly() {
        assertReadOnly(ProjectStatus.COMPLETED);
        assertReadOnly(ProjectStatus.ARCHIVED);
    }

    private static void assertReadOnly(ProjectStatus status) {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> ProjectWritePolicy.requireWritable(status));

        assertEquals(ErrorCode.PROJECT_READ_ONLY, exception.getErrorCode());
    }
}
