package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.domain.PatchValue;
import com.shitulelv.aicollab.planning.domain.TaskPlanRepairPatch;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task 5: Parse AI repair patch JSON into structured TaskPlanRepairPatch.
 *
 * Distinguishes omitted fields (absent) from explicit null (clear).
 * Uses JsonNode presence detection for this purpose.
 */
@Component
public class TaskPlanRepairPatchParser {
    private static final Set<String> ROOT_FIELDS = Set.of("milestonePatches", "taskPatches");
    private static final Set<String> MILESTONE_FIELDS =
            Set.of("tempKey", "description", "targetDate", "sourceRefs");
    private static final Set<String> TASK_FIELDS = Set.of(
            "tempKey", "description", "priority", "estimatedHours", "startDate", "dueDate",
            "suggestedAssigneeId", "dependencyTempKeys", "sourceRefs");

    private final ObjectMapper json;

    public TaskPlanRepairPatchParser(ObjectMapper json) {
        this.json = json;
    }

    public TaskPlanRepairPatch parse(String patchJson) {
        try {
            JsonNode root = json.readTree(patchJson);
            requireObject(root);
            rejectUnknownFields(root, ROOT_FIELDS);
            List<TaskPlanRepairPatch.MilestonePatch> milestones = new ArrayList<>();
            JsonNode msNode = requireArray(root, "milestonePatches");
            for (JsonNode m : msNode) {
                requireObject(m);
                rejectUnknownFields(m, MILESTONE_FIELDS);
                milestones.add(parseMilestonePatch(m));
            }
            List<TaskPlanRepairPatch.TaskPatch> tasks = new ArrayList<>();
            JsonNode tNode = requireArray(root, "taskPatches");
            for (JsonNode t : tNode) {
                requireObject(t);
                rejectUnknownFields(t, TASK_FIELDS);
                tasks.add(parseTaskPatch(t));
            }
            return new TaskPlanRepairPatch(milestones, tasks);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid repair patch JSON");
        }
    }

    private TaskPlanRepairPatch.MilestonePatch parseMilestonePatch(JsonNode node) {
        return new TaskPlanRepairPatch.MilestonePatch(
                requiredString(node, "tempKey"),
                patchString(node, "description"),
                patchDate(node, "targetDate"),
                patchStringList(node, "sourceRefs"));
    }

    private TaskPlanRepairPatch.TaskPatch parseTaskPatch(JsonNode node) {
        return new TaskPlanRepairPatch.TaskPatch(
                requiredString(node, "tempKey"),
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
        if (!value.isTextual()) throw new IllegalArgumentException();
        return PatchValue.of(value.textValue());
    }

    private PatchValue<LocalDate> patchDate(JsonNode node, String field) {
        if (!node.has(field)) return PatchValue.absent();
        JsonNode value = node.get(field);
        if (value.isNull()) return PatchValue.of(null);
        if (!value.isTextual()) throw new IllegalArgumentException();
        return PatchValue.of(LocalDate.parse(value.textValue()));
    }

    private PatchValue<BigDecimal> patchBigDecimal(JsonNode node, String field) {
        if (!node.has(field)) return PatchValue.absent();
        JsonNode value = node.get(field);
        if (value.isNull()) return PatchValue.of(null);
        if (!value.isNumber()) throw new IllegalArgumentException();
        return PatchValue.of(value.decimalValue());
    }

    private PatchValue<UUID> patchUuid(JsonNode node, String field) {
        if (!node.has(field)) return PatchValue.absent();
        JsonNode value = node.get(field);
        if (value.isNull()) return PatchValue.of(null);
        if (!value.isTextual()) throw new IllegalArgumentException();
        return PatchValue.of(UUID.fromString(value.textValue()));
    }

    private PatchValue<List<String>> patchStringList(JsonNode node, String field) {
        if (!node.has(field)) return PatchValue.absent();
        JsonNode value = node.get(field);
        if (value.isNull()) return PatchValue.of(null);
        if (!value.isArray()) throw new IllegalArgumentException();
        List<String> list = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) throw new IllegalArgumentException();
            list.add(item.textValue());
        }
        return PatchValue.of(List.copyOf(list));
    }

    private String requiredString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException();
        return value.textValue();
    }

    private JsonNode requireArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) throw new IllegalArgumentException();
        return value;
    }

    private void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException();
    }

    private void rejectUnknownFields(JsonNode node, Set<String> allowedFields) {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowedFields.contains(fields.next())) throw new IllegalArgumentException();
        }
    }
}
