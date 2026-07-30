package com.shitulelv.aicollab.project.domain.policy;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.model.ProjectStatus;

public final class ProjectWritePolicy {

    private ProjectWritePolicy() {
    }

    public static void requireWritable(ProjectStatus status) {
        if (status != ProjectStatus.PREPARING && status != ProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.PROJECT_READ_ONLY);
        }
    }
}
