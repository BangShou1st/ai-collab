package com.shitulelv.aicollab.project.api.dto;

import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(@NotNull ProjectRole role) {
}
