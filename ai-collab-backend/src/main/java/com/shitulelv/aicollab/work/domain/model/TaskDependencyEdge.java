package com.shitulelv.aicollab.work.domain.model;

import java.util.UUID;

public record TaskDependencyEdge(UUID taskId, UUID dependsOnTaskId) {
}
