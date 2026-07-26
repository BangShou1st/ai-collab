package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import org.springframework.stereotype.Component;

@Component
public class TaskPlanOutputParser {
    private final ObjectMapper json;
    public TaskPlanOutputParser(ObjectMapper json) { this.json = json; }

    public TaskPlanDraft parse(String output) {
        String value = output == null ? "" : output.strip();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        try { return json.readValue(value, TaskPlanDraft.class); }
        catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.PLANNING_MODEL_INVALID_OUTPUT);
        }
    }
}
