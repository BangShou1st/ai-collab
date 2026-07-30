package com.shitulelv.aicollab.project.domain.policy;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.model.ProjectStatus;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DatabaseProjectWriteGuard implements ProjectWriteGuard {
    private final ProjectRepository projects;

    public DatabaseProjectWriteGuard(ProjectRepository projects) {
        this.projects = projects;
    }

    @Override
    public void requireWritable(UUID projectId) {
        ProjectStatus status = projects.findStatus(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND));
        ProjectWritePolicy.requireWritable(status);
    }
}
