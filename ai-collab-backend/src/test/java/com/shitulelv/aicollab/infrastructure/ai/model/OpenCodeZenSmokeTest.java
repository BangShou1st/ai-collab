package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.shitulelv.aicollab.acceptance.AcceptanceDatabaseSupport;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderRepository;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import java.nio.file.*;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in; one synthetic SSE request, no credentials discovery or retries. */
class OpenCodeZenSmokeTest {
    @Test @EnabledIfEnvironmentVariable(named="AI_ZEN_SMOKE",matches="true")
    void oneSyntheticRequestThroughProductionZenExecution() throws Exception {
        String key=System.getenv("AI_ZEN_SMOKE_KEY");
        String keyFile=System.getenv("AI_ZEN_SMOKE_KEY_FILE");
        if (keyFile!=null) key=Files.readString(Path.of(keyFile)).strip();
        String model=System.getenv("AI_ZEN_SMOKE_MODEL");
        assertThat(key).as("Provide this project's key through environment").isNotBlank();
        assertThat(model).as("Provide the model to test").isNotBlank();
        var registry=new ProviderPresetRegistry(); var policy=registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE);
        var now=OffsetDateTime.now();
        var provider=new UserAiProvider(UUID.randomUUID(),UUID.randomUUID(),"Smoke",policy.protocol(),policy.baseUrl(),policy.completionPath(),null,
                model,true,0.2,4000,policy.capabilities(),false,now,now,policy.code().name());
        if ("true".equals(System.getenv("AI_ZEN_USE_ACCEPTANCE_DB"))) {
            var jdbc=AcceptanceDatabaseSupport.jdbc();
            UUID id=jdbc.queryForObject("select id from user_ai_provider where preset_code='OPENCODE_ZEN_FREE' and enabled order by created_at limit 1",UUID.class);
            var cipher=new ModelSecretCipher(AcceptanceDatabaseSupport.localProperties().getProperty("MODEL_CONFIG_MASTER_KEY"));
            jdbc.update("update user_ai_provider set encrypted_api_key=?,updated_at=now() where id=?",cipher.encrypt(key),id);
            provider=new UserAiProviderRepository(jdbc).findById(id).orElseThrow();
            key=cipher.decrypt(provider.encryptedApiKey());
        }
        var zen=new ZenModelExecution(registry,new ObjectMapper(),new OutboundEndpointPolicy());
        try {
            if ("CATALOG".equals(System.getenv("AI_ZEN_SMOKE_MODE"))) {
                var models = new HttpOpenCodeZenTransport(new ObjectMapper(), new OutboundEndpointPolicy(), registry)
                        .listFreeModels(key, AiRequestMetadata.fresh());
                assertThat(models).isNotEmpty();
                System.out.println("Zen real free model catalog: " + String.join(",", models));
            } else if ("TOOLS".equals(System.getenv("AI_ZEN_SMOKE_MODE"))) {
                var json=new ObjectMapper();
                var schema=json.readTree("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\",\"enum\":[\"probe\"]}},\"required\":[\"value\"],\"additionalProperties\":false}");
                var result=zen.turn(provider,key,new ModelTurnCommand(ModelPurpose.AGENT,null,null,
                        List.of(new ModelMessage.User("调用 capability_probe，value 必须为 probe。仅合成工具能力测试。")),
                        List.of(new ModelToolDefinition("capability_probe","Synthetic probe, never executed",schema)),true,provider.userId()),AiRequestMetadata.fresh());
                assertThat(result.toolCalls()).hasSize(1);
                assertThat(result.toolCalls().getFirst().id()).isNotBlank();
                assertThat(result.toolCalls().getFirst().name()).isEqualTo("capability_probe");
                assertThat(result.toolCalls().getFirst().arguments()).isEqualTo(json.readTree("{\"value\":\"probe\"}"));
                System.out.println("Zen real NATIVE_TOOLS contract passed: model="+result.model()+"; tool not executed");
            } else {
                var result=zen.complete(provider,key,new ChatCompletionCommand("仅回答合成测试请求。","仅输出 JSON：{\"probe\":true}",ChatCompletionCommand.OutputFormat.PROMPT_JSON),AiRequestMetadata.fresh());
                String body=result.content().strip();
                if (body.startsWith("```") && body.endsWith("```") && body.contains("\n"))
                    body=body.substring(body.indexOf('\n')+1,body.length()-3).strip();
                var parsed=new ObjectMapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
                assertThat(parsed).isEqualTo(new ObjectMapper().readTree("{\"probe\":true}"));
                System.out.println("Zen real JSON/SSE contract passed: model="+result.model()+", latencyMs="+result.latencyMs()+"; native tools and full planning not implied");
            }
        } finally { zen.close(); }
    }
}
