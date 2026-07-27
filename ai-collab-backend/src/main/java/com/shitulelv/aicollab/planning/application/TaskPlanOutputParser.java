package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.domain.DetailModelOutput;
import com.shitulelv.aicollab.planning.domain.SkeletonModelOutput;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Parses AI model output using stage-specific strict ObjectMappers.
 * Each stage enforces its own contract — unknown properties cause immediate failure.
 * S4: Throws ModelOutputContractException with safe category/path for structured diagnosis.
 */
@Component
public class TaskPlanOutputParser {
    private final ObjectMapper json;
    private final ObjectMapper strictSkeleton;
    private final ObjectMapper strictDetail;

    public TaskPlanOutputParser(ObjectMapper json) {
        this.json = json;
        this.strictSkeleton = json.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.strictDetail = json.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** Legacy parse — used only for repair prompt which expects full draft. */
    public TaskPlanDraft parse(String output) {
        String value = clean(output);
        try { return json.readValue(value, TaskPlanDraft.class); }
        catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.PLANNING_MODEL_INVALID_OUTPUT);
        }
    }

    /** Parse skeleton output with strict contract validation. */
    public SkeletonModelOutput parseSkeleton(String output) {
        String value = clean(output);
        try { return strictSkeleton.readValue(value, SkeletonModelOutput.class); }
        catch (JsonProcessingException exception) {
            throw toContractException(exception);
        }
    }

    /** Parse detail output with strict contract + required field validation. */
    public DetailModelOutput parseDetail(String output) {
        String value = clean(output);
        // Pre-check: verify required fields appear (even if null) before deserialization
        validateDetailRequiredFieldsExist(value);
        DetailModelOutput result;
        try { result = strictDetail.readValue(value, DetailModelOutput.class); }
        catch (JsonProcessingException exception) {
            throw toContractException(exception);
        }
        validateDetailRequiredFields(result);
        return result;
    }

    /**
     * S6: Pre-check that all required fields appear in JSON tree before deserialization.
     * Distinguishes "omitted" (reject) from "explicit null" (accept).
     */
    private void validateDetailRequiredFieldsExist(String value) {
        try {
            JsonNode root = json.readTree(value);
            JsonNode milestones = root.path("milestones");
            if (milestones.isArray()) {
                for (int i = 0; i < milestones.size(); i++) {
                    JsonNode m = milestones.get(i);
                    String path = "milestones[" + i + "]";
                    checkRequired(m, path, "tempKey");
                    checkRequired(m, path, "description");
                    checkRequired(m, path, "sourceRefs");
                }
            }
            JsonNode tasks = root.path("tasks");
            if (tasks.isArray()) {
                for (int i = 0; i < tasks.size(); i++) {
                    JsonNode t = tasks.get(i);
                    String path = "tasks[" + i + "]";
                    checkRequired(t, path, "tempKey");
                    checkRequired(t, path, "description");
                    checkRequired(t, path, "priority");
                    checkRequired(t, path, "estimatedHours");
                    checkRequired(t, path, "startDate");
                    checkRequired(t, path, "dueDate");
                    checkRequired(t, path, "suggestedAssigneeId");
                    checkRequired(t, path, "dependencyTempKeys");
                    checkRequired(t, path, "sourceRefs");
                }
            }
        } catch (JsonProcessingException e) {
            throw new ModelOutputContractException("JSON_SYNTAX_INVALID", null);
        }
    }

    private static void checkRequired(JsonNode node, String parentPath, String field) {
        if (!node.has(field)) {
            throw new ModelOutputContractException("MISSING_REQUIRED_FIELD",
                    parentPath + "." + field, List.of("FIELD_OMITTED"));
        }
    }

    /**
     * S4: Convert Jackson exceptions to safe ModelOutputContractException.
     */
    private static ModelOutputContractException toContractException(JsonProcessingException exception) {
        String path = safePath(exception);
        if (exception instanceof UnrecognizedPropertyException) {
            return new ModelOutputContractException("UNKNOWN_PROPERTY", path);
        }
        String msg = exception.getOriginalMessage();
        if (msg != null && msg.contains("required")) {
            return new ModelOutputContractException("MISSING_REQUIRED_FIELD", path);
        }
        if (msg != null && msg.contains("Cannot deserialize")) {
            return new ModelOutputContractException("INVALID_FIELD_TYPE", path);
        }
        return new ModelOutputContractException("JSON_SYNTAX_INVALID", path);
    }

    /**
     * S4: Extract safe field path from Jackson exception using JsonMappingException.getPath().
     * Falls back to parsing UnrecognizedPropertyException property name.
     */
    private static String safePath(JsonProcessingException exception) {
        try {
            if (exception instanceof JsonMappingException mapping) {
                List<JsonMappingException.Reference> path = mapping.getPath();
                if (path != null && !path.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonMappingException.Reference ref : path) {
                        if (ref.getFieldName() != null) {
                            if (!sb.isEmpty()) sb.append(".");
                            sb.append(ref.getFieldName());
                        }
                    }
                    return sb.length() > 0 ? sb.toString() : null;
                }
            }
            if (exception instanceof UnrecognizedPropertyException unrecognized) {
                return unrecognized.getPropertyName();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * S3: Enforce required fields that JSON Schema specifies but Jackson alone cannot.
     * Milestone: tempKey, description, sourceRefs required.
     * Task: tempKey, description, priority, dependencyTempKeys, sourceRefs required.
     */
    private static void validateDetailRequiredFields(DetailModelOutput output) {
        for (int i = 0; i < output.milestones().size(); i++) {
            DetailModelOutput.DetailMilestone m = output.milestones().get(i);
            String path = "milestones[" + i + "]";
            if (m.tempKey() == null || m.tempKey().isBlank())
                throw new ModelOutputContractException("MISSING_REQUIRED_FIELD", path + ".tempKey");
            if (m.description() == null || m.description().isBlank())
                throw new ModelOutputContractException("MISSING_REQUIRED_FIELD", path + ".description");
            if (m.sourceRefs() == null)
                throw new ModelOutputContractException("MISSING_REQUIRED_FIELD", path + ".sourceRefs");
        }
        for (int i = 0; i < output.tasks().size(); i++) {
            DetailModelOutput.DetailTask t = output.tasks().get(i);
            String path = "tasks[" + i + "]";
            if (t.tempKey() == null || t.tempKey().isBlank())
                throw new ModelOutputContractException("MISSING_REQUIRED_FIELD", path + ".tempKey",
                        List.of("MISSING_TEMP_KEY"));
            if (t.description() == null || t.description().isBlank())
                throw new ModelOutputContractException("MISSING_REQUIRED_FIELD", path + ".description",
                        List.of("MISSING_DESCRIPTION"));
            if (t.priority() == null || t.priority().isBlank())
                throw new ModelOutputContractException("MISSING_REQUIRED_FIELD", path + ".priority",
                        List.of("MISSING_PRIORITY"));
            if (t.dependencyTempKeys() == null)
                throw new ModelOutputContractException("MISSING_REQUIRED_FIELD", path + ".dependencyTempKeys",
                        List.of("MISSING_DEPENDENCY_TEMP_KEYS"));
            if (t.sourceRefs() == null)
                throw new ModelOutputContractException("MISSING_REQUIRED_FIELD", path + ".sourceRefs",
                        List.of("MISSING_SOURCE_REFS"));
        }
    }

    private static String clean(String output) {
        String value = output == null ? "" : output.strip();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return value;
    }
}
