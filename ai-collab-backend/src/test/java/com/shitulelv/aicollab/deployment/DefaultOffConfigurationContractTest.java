package com.shitulelv.aicollab.deployment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 冻结默认关闭/静态回退开关，防止可选开启能力被意外变成生产默认。 */
class DefaultOffConfigurationContractTest {

    private final String applicationYaml = readApplicationYaml();

    @Test
    void defaultOffSwitchesRemainFalse() {
        assertThat(applicationYaml).contains("${AGENT_MCP_GATEWAY_ENABLED:false}");
        assertThat(applicationYaml).contains("${MEMORY_JOB_ENABLED:false}");
        assertThat(applicationYaml).contains("${QUERY_PLAN_ENABLED:false}");
        assertThat(applicationYaml).contains("${PROMPT_REGISTRY_ENABLED:false}");
    }

    @Test
    void documentMqDefaultsOnAndPlanningAgentMqStayDeleted() {
        assertThat(count("${MQ_ENABLED:false}")).isZero();
        assertThat(count("enabled: \"${MQ_ENABLED:true}\"")).isEqualTo(1);
        assertThat(applicationYaml)
                .doesNotContain("PLANNING_MQ_ENABLED")
                .doesNotContain("AGENT_MQ_ENABLED")
                .doesNotContain("AGENT_MQ_FALLBACK_POLL_MS")
                .doesNotContain("ROCKETMQ_PLANNING_")
                .doesNotContain("ROCKETMQ_AGENT_RUN_");
    }

    @Test
    void removedModeSwitchesAndStaticTokenStayDeleted() {
        assertThat(applicationYaml)
                .doesNotContain("RERANK_MODE")
                .doesNotContain("DECIDE_MODE")
                .doesNotContain("PLANNING_DRAFT_MODE")
                .doesNotContain("INTERNAL_AI_TOKEN_MODE")
                .doesNotContain("RERANK_SERVICE_TOKEN")
                .doesNotContain("RERANK_MODEL_USER_ID");
    }

    @Test
    void ed25519PrivateKeyHasNoBuiltInDefault() {
        assertThat(applicationYaml).contains("private-key: \"${INTERNAL_AI_ED25519_PRIVATE_KEY:}\"");
        assertThat(applicationYaml).contains("kid: \"${INTERNAL_AI_ED25519_KID:k1}\"");
    }

    @Test
    void agentRuntimeHardCutoffDefaultsToEnabledOnlyByExplicitDesign() {
        assertThat(applicationYaml).contains("runtime-allowed: ${AGENT_RUNTIME_ALLOWED:true}");
    }

    private long count(String needle) {
        long found = 0;
        int from = 0;
        while ((from = applicationYaml.indexOf(needle, from)) >= 0) {
            found++;
            from += needle.length();
        }
        return found;
    }

    private static String readApplicationYaml() {
        try {
            return Files.readString(Path.of("src/main/resources/application.yml"));
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取 application.yml", failure);
        }
    }
}
