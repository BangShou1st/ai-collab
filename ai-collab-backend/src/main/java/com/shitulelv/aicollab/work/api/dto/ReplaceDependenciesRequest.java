package com.shitulelv.aicollab.work.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record ReplaceDependenciesRequest(@NotNull @Size(max = 10) List<@NotNull UUID> dependencyIds) {
}
