package com.shitulelv.aicollab.planning.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shitulelv.aicollab.common.retrieval.SeedEvidenceInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateTaskPlanRequest(
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 2000) String goal,
        @Size(max = 4000) String constraints,
        @NotNull LocalDate planStartDate,
        @NotNull LocalDate planDueDate,
        @Min(1) @Max(40) int maxTaskCount,
        @Size(max = 10) List<UUID> documentIds,
        @Size(max = 20) List<SeedEvidenceInput> seedEvidence) {

    @JsonCreator
    public CreateTaskPlanRequest(
            @JsonProperty("title") String title,
            @JsonProperty("goal") String goal,
            @JsonProperty("constraints") String constraints,
            @JsonProperty("planStartDate") LocalDate planStartDate,
            @JsonProperty("planDueDate") LocalDate planDueDate,
            @JsonProperty("maxTaskCount") int maxTaskCount,
            @JsonProperty("documentIds") List<UUID> documentIds,
            @JsonProperty("seedEvidence") List<SeedEvidenceInput> seedEvidence) {
        this.title = title;
        this.goal = goal;
        this.constraints = constraints;
        this.planStartDate = planStartDate;
        this.planDueDate = planDueDate;
        this.maxTaskCount = maxTaskCount;
        this.documentIds = documentIds;
        this.seedEvidence = seedEvidence == null ? List.of() : List.copyOf(seedEvidence);
    }

    public CreateTaskPlanRequest(
            String title,
            String goal,
            String constraints,
            LocalDate planStartDate,
            LocalDate planDueDate,
            int maxTaskCount,
            List<UUID> documentIds) {
        this(title, goal, constraints, planStartDate, planDueDate, maxTaskCount, documentIds, List.of());
    }
}
