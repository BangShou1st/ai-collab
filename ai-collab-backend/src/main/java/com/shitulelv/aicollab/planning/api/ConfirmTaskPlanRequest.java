package com.shitulelv.aicollab.planning.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ConfirmTaskPlanRequest(@NotNull UUID versionId) {}
