package com.shitulelv.aicollab.infrastructure.ai.user;

import com.fasterxml.jackson.databind.*;
import com.shitulelv.aicollab.infrastructure.ai.*;
import com.shitulelv.aicollab.infrastructure.ai.model.*;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import com.shitulelv.aicollab.common.exception.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** One synthetic request per selected capability. Never executes model-proposed tools. */
@Service
public class AiCapabilityProbeService {
    public enum Mode { CONNECTIVITY, PLANNING_JSON, STREAMING, NATIVE_TOOLS }
    public record Evidence(Mode mode, String provider, String model, long latencyMs, boolean verified) {}
    private final UserAiProviderService providers;
    private final ChatModelGateway chat;
    private final ModelTurnGateway turns;
    private final ObjectMapper json;
    public AiCapabilityProbeService(UserAiProviderService providers, ChatModelGateway chat, ModelTurnGateway turns, ObjectMapper json) {
        this.providers=providers; this.chat=chat; this.turns=turns; this.json=json;
    }
    public Evidence test(UUID user, UUID id, Mode mode) {
        var selected = providers.requireRuntimeConfiguration(user,id);
        providers.requireSnapshotAuthorized(selected);
        try (var snapshot = new AiConfigurationContext(selected)) {
            if (mode == Mode.NATIVE_TOOLS) {
                var schema = json.createObjectNode().put("type","object");
                schema.set("properties",json.createObjectNode().set("value",json.createObjectNode().put("type","string").put("const","probe")));
                schema.set("required",json.createArrayNode().add("value"));
                schema.put("additionalProperties",false);
                var result = turns.turn(new ModelTurnCommand(ModelPurpose.AGENT,null,null,
                        List.of(new ModelMessage.User("必须调用 capability_probe，value 为 probe。不要执行任何其他操作。")),
                        List.of(new ModelToolDefinition("capability_probe","Synthetic capability test",schema)),true,user));
                if (result.toolCalls().size()!=1 || !"capability_probe".equals(result.toolCalls().getFirst().name())
                        || !"probe".equals(result.toolCalls().getFirst().arguments().path("value").asText())) invalid();
                return new Evidence(mode,result.provider(),result.model(),result.latencyMs(),true);
            }
            var command = new ChatCompletionCommand(null,"仅回答合成测试请求。",
                    mode == Mode.PLANNING_JSON ? "仅输出 JSON 对象 {\"tasks\":[{\"title\":\"probe\"}]}，不要添加其他文本。" : "回复 probe",
                    mode == Mode.PLANNING_JSON ? ChatCompletionCommand.OutputFormat.PROMPT_JSON : ChatCompletionCommand.OutputFormat.TEXT,
                    mode == Mode.PLANNING_JSON ? ModelPurpose.PLANNING : ModelPurpose.KNOWLEDGE_CHAT,null,List.of(),user);
            ChatCompletionResult result;
            if (mode == Mode.STREAMING) {
                AtomicReference<ChatCompletionResult> completed = new AtomicReference<>();
                AtomicReference<Exception> failed = new AtomicReference<>();
                StringBuilder tokens=new StringBuilder();
                chat.completeStream(command,tokens::append,completed::set,failed::set);
                if (failed.get()!=null) throw failed.get() instanceof BusinessException business ? business : new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
                result=completed.get();
                if (result==null || tokens.isEmpty()) invalid();
            } else result=chat.complete(command);
            if (result==null || result.content()==null || result.content().isBlank()) invalid();
            if (mode == Mode.PLANNING_JSON) {
                String content=result.content().strip();
                if (content.startsWith("```") && content.endsWith("```")) content=content.substring(content.indexOf('\n')+1,content.length()-3).strip();
                try {
                    JsonNode parsed=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content);
                    if (!parsed.path("tasks").isArray() || parsed.path("tasks").size()!=1 || !"probe".equals(parsed.path("tasks").get(0).path("title").asText())) invalid();
                } catch (java.io.IOException malformed) { invalid(); }
            }
            return new Evidence(mode,result.provider(),result.model(),result.latencyMs(),true);
        }
    }
    private static void invalid() { throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,"所选能力测试未满足输出契约"); }
}
