package com.shitulelv.aicollab.agent.infrastructure.tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.domain.tool.*;
import org.springframework.stereotype.Component;
import java.util.List;

/** Typed clarification pauses the run and grants no business authority. */
@Component
public class RequestUserInputAgentTool implements AgentTool {
    public static final String NAME = "request_user_input";
    @Override public String name() { return NAME; }
    @Override public boolean writesBusinessData() { return false; }
    @Override public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(NAME, "必要信息缺失时向用户提问并等待补充，不得与提案同批。", """
                {"type":"object","required":["questions"],"additionalProperties":false,"properties":{
                  "questions":{"type":"array","minItems":1,"maxItems":5,"items":{"type":"string","minLength":1,"maxLength":500}},
                  "targetReference":{"type":"string","maxLength":300}}}
                """, false);
    }
    @Override public AgentToolResult execute(AgentToolContext context, JsonNode arguments) { return new AgentToolResult(arguments, List.of(), List.of()); }
}
