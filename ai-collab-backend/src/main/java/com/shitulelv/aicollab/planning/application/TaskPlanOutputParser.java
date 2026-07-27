package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.domain.DetailModelOutput;
import com.shitulelv.aicollab.planning.domain.SkeletonModelOutput;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import org.springframework.stereotype.Component;

/**
 * Parses AI model output using stage-specific strict ObjectMappers.
 * Each stage enforces its own contract — unknown properties cause immediate failure.
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
            throw new BusinessException(ErrorCode.PLANNING_MODEL_INVALID_OUTPUT);
        }
    }

    /** Parse detail output with strict contract validation. */
    public DetailModelOutput parseDetail(String output) {
        String value = clean(output);
        try { return strictDetail.readValue(value, DetailModelOutput.class); }
        catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.PLANNING_MODEL_INVALID_OUTPUT);
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
