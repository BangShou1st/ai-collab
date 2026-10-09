package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.ApprovalWriteAgentTool;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.util.Set;

abstract class AbstractApprovalWriteAgentTool implements ApprovalWriteAgentTool {
    protected final ObjectMapper json;
    private final Validator validator;

    protected AbstractApprovalWriteAgentTool(ObjectMapper json, Validator validator) {
        this.json = json;
        this.validator = validator;
    }

    @Override
    public final boolean writesBusinessData() {
        return true;
    }

    protected final <T> T request(JsonNode arguments, Class<T> type) {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        }
        T request;
        try {
            request = json.treeToValue(arguments, type);
        } catch (Exception exception) {
            throw new IllegalArgumentException("工具参数格式无效", exception);
        }
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.iterator().next().getMessage());
        }
        return request;
    }

    protected final JsonNode tree(Object value) {
        return json.valueToTree(value);
    }

    /** Stored task proposals include a derived display name, which is not a business request field. */
    protected final <T> T taskRequest(JsonNode arguments, Class<T> type) {
        if (arguments == null || !arguments.isObject()) {
            return request(arguments, type);
        }
        var businessArguments = ((com.fasterxml.jackson.databind.node.ObjectNode) arguments).deepCopy();
        businessArguments.remove("assigneeName");
        return request(businessArguments, type);
    }
}
