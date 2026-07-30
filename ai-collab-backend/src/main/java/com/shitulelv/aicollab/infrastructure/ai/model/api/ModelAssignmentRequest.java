package com.shitulelv.aicollab.infrastructure.ai.model.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ModelAssignmentRequest(@NotNull UUID configurationId) {
}
