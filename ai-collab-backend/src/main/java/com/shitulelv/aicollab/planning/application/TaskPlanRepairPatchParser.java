package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.domain.PatchValue;
import com.shitulelv.aicollab.planning.domain.TaskPlanRepairPatch;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Task 5: Parse AI repair patch JSON into structured TaskPlanRepairPatch.
 *
 * Distinguishes omitted fields (absent) from explicit null (clear).
 * Uses JsonNode presence detection for this purpose.
 */
@Component
public class TaskPlanRepairPatchParser {
    private final ObjectMapper json;

    public TaskPlanRepairPatchParser(ObjectMapper json) {
        this.json = json;
    }

    public TaskPlanRepairPatch parse(String patchJson) {
        try {
            JsonNode root = json.readTree(patchJson);
            List<TaskPlanRepairPatch.MilestonePatch> milestones = new ArrayList<>();
            JsonNode msNode = root.path("milestonePatches");
            if (msNode.isArray()) {
                for (JsonNode m : msNode) {
                    milestones.add(parseMilestonePatch(m));
                }
            }
            List<TaskPlanRepairPatch.TaskPatch> tasks = new ArrayList<>();
            JsonNode tNode = root.path("taskPatches");
            if (tNode.isArray()) {
                for (JsonNode t : tNode) {
                    tasks.add(parseTaskPatch(t));
                }
            }
            return new TaskPlanRepairPatch(milestones, tasks);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid repair patch JSON: " + e.getMessage());
        }
    }

    private TaskPlanRepairPatch.MilestonePatch parseMilestonePatch(JsonNode node) {
        return new TaskPlanRepairPatch.MilestonePatch(
                node.path("tempKey").asText(null),
                patchString(node, "description"),
                patchDate(node, "targetDate"),
                patchStringList(node, "sourceRefs"));
    }

    private TaskPlanRepairPatch.TaskPatch parseTaskPatch(JsonNode node) {
        return new TaskPlanRepairPatch.TaskPatch(
                node.path("tempKey").asText(null),
                patchString(node, "description"),
                patchString(node, "priority"),
                patchBigDecimal(node, "estimatedHours"),
                patchDate(node, "startDate"),
                patchDate(node, "dueDate"),
                patchUuid(node, "suggestedAssigneeId"),
                patchStringList(node, "dependencyTempKeys"),
                patchStringList(node, "sourceRefs"));
    }

    private PatchValue<String> patchString(JsonNode node, String field) {
        if (!node.has(field)) return PatchValue.absent();
        JsonNode value = node.get(field);
        if (value.isNull()) return PatchValue.of(null);
        return PatchValue.of(value.asText());
    }

    private PatchValue<LocalDate> patchDate(JsonNode node, String field) {
        if (!node.has(field)) return PatchValue.absent();
        JsonNode value = node.get(field);
        if (value.isNull()) return PatchValue.of(null);
        return PatchValue.of(LocalDate.parse(value.asText()));
    }

    private PatchValue<BigDecimal> patchBigDecimal(JsonNode node, String field) {
        if (!node.has(field)) return PatchValue.absent();
        JsonNode value = node.get(field);
        if (value.isNull()) return PatchValue.of(null);
        return PatchValue.of(BigDecimal.valueOf(value.asDouble()));
    }

    private PatchValue<UUID> patchUuid(JsonNode node, String field) {
        if (!node.has(field)) return PatchValue.absent();
        JsonNode value = node.get(field);
        if (value.isNull()) return PatchValue.of(null);
        return PatchValue.of(UUID.fromString(value.asText()));
    }

    private PatchValue<List<String>> patchStringList(JsonNode node, String field) {
        if (!node.has(field)) return PatchValue.absent();
        JsonNode value = node.get(field);
        if (value.isNull()) return PatchValue.of(null);
        List<String> list = new ArrayList<>();
        if (value.isArray()) {
            for (JsonNode item : value) {
                list.add(item.asText());
            }
        }
        return PatchValue.of(List.copyOf(list));
    }
}
