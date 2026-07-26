package com.shitulelv.aicollab.planning.api;

import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SaveTaskPlanVersionRequest(@NotNull UUID baseVersionId, @NotNull @Valid TaskPlanDraft draft) {}
