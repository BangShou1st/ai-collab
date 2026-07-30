package com.shitulelv.aicollab.project.domain.policy;

import java.util.UUID;

public interface ProjectWriteGuard {
    void requireWritable(UUID projectId);
}
