package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.view.AgentMessageView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPlan;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单次请求的准备链一致性：提示词准备、工具协议与出站调用必须共用请求准备阶段解析出的
 * 同一份模式快照；准备之后切换配置不改变本次请求，下一次请求重新解析。
 */
class AgentModelMessageComposerRequestConsistencyTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private AgentModelMessageComposer composer;
    private AgentRunView run;

    @BeforeEach
    void setUp() {
        var repository = mock(AgentRepository.class);
        when(repository.workingState(any(), any())).thenReturn(null);
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(new ArrayList<AgentMessageView>());
        composer = new AgentModelMessageComposer(repository, mock(AgentMemoryService.class), json);
        run = run();
    }

    @Test
    void legacySnapshotPutsReadOnlyContractInPromptWithoutRereadingConfiguration() {
        var composition = composer.composeV2(run, context(), skill(), plan(), List.of(), 30_000, 1.0, true);
        String systemPrompt = systemPrompt(composition.messages());
        assertThat(systemPrompt).contains("当前运行模式：Legacy（只读）", "写操作不可用");
    }

    @Test
    void nativeSnapshotKeepsWritePathAvailableInPrompt() {
        var composition = composer.composeV2(run, context(), skill(), plan(), List.of(), 30_000, 1.0, false);
        assertThat(systemPrompt(composition.messages())).doesNotContain("当前运行模式：Legacy（只读）");
    }

    @Test
    void legacyFallbackAssemblyPathUsesTheSameSnapshot() {
        var messages = composer.buildMessageHistory(run, context(), skill(), plan(), List.of(), true);
        assertThat(systemPrompt(messages)).contains("当前运行模式：Legacy（只读）");
        assertThat(systemPrompt(composer.buildMessageHistory(run, context(), skill(), plan(), List.of(), false)))
                .doesNotContain("当前运行模式：Legacy（只读）");
    }

    @Test
    void degradedRecomposeKeepsTheSameModeSnapshot() {
        var composition = composer.composeV2(run, context(), skill(), plan(), List.of(), 30_000, 0.6, true);
        assertThat(systemPrompt(composition.messages())).contains("当前运行模式：Legacy（只读）");
    }

    @Test
    void listPagingContractIsStatedInSystemPrompt() {
        var composition = composer.composeV2(run, context(), skill(), plan(), List.of(), 30_000, 1.0, false);
        assertThat(systemPrompt(composition.messages()))
                .contains("data.nextCursor", "hasMore=false", "不得宣称已列全");
    }

    @Test
    void resolvedSnapshotDerivesLegacyModeFromCapabilities() {
        var legacy = RoutingAgentModelExecutor.ResolvedRequest.of(
                provider("legacy-model", EnumSet.of(ModelCapability.CHAT)), config("legacy-model", EnumSet.of(ModelCapability.CHAT)));
        var nativeRequest = RoutingAgentModelExecutor.ResolvedRequest.of(
                provider("native-model", EnumSet.of(ModelCapability.NATIVE_TOOLS, ModelCapability.CHAT)),
                config("native-model", EnumSet.of(ModelCapability.NATIVE_TOOLS, ModelCapability.CHAT)));
        assertThat(legacy.legacyMode()).isTrue();
        assertThat(nativeRequest.legacyMode()).isFalse();
    }

    private String systemPrompt(List<ModelMessage> messages) {
        assertThat(messages.get(0)).isInstanceOf(ModelMessage.System.class);
        return ((ModelMessage.System) messages.get(0)).content();
    }

    private UserAiProvider provider(String modelName, EnumSet<ModelCapability> capabilities) {
        var provider = mock(UserAiProvider.class);
        when(provider.providerType()).thenReturn(ModelProviderType.OPENAI_COMPATIBLE);
        when(provider.modelName()).thenReturn(modelName);
        return provider;
    }

    private ModelConfiguration config(String modelName, EnumSet<ModelCapability> capabilities) {
        return new ModelConfiguration(UUID.randomUUID(), null, modelName, ModelProviderType.OPENAI_COMPATIBLE,
                "https://example.invalid", "/v1/chat/completions", "enc", modelName, true, 0.2, 1024,
                capabilities, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private AgentRunView run() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查本周任务是否影响交付", AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                0, 0, 0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, null, 1, now, now);
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SUPERVISOR", false, AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 0, List.of());
    }

    private AgentPlan plan() {
        return AgentPlan.create("查询任务", List.of());
    }

    private AgentSkill skill() {
        AgentSkill skill = mock(AgentSkill.class);
        when(skill.instruction()).thenReturn("只读查询指令");
        when(skill.outputContract()).thenReturn("自然语言回答");
        return skill;
    }
}
