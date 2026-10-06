package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPlan;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;

import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.model.ZenModelExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请求准备链的单次一致性：协调器在准备阶段解析一次配置，随后组装的提示词、
 * 暴露的写入工具与出站执行路径必须来自同一份快照；准备之后切换配置不改变本次请求，
 * 下一次请求才采用新配置（Native → Legacy、Legacy → Native 双向覆盖）。
 */
class AgentRuntimeRequestSnapshotTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private AgentRepository repository;
    private AgentModelConfigurationStore store;
    private RoutingAgentModelExecutor modelExecutor;
    private NativeToolCallingExecutor nativeExecutor;
    private LegacyReadOnlyAgentExecutor legacyExecutor;
    private AgentRunView run;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        store = mock(AgentModelConfigurationStore.class);
        nativeExecutor = mock(NativeToolCallingExecutor.class);
        legacyExecutor = mock(LegacyReadOnlyAgentExecutor.class);
        var zen = mock(ZenModelExecution.class);
        when(zen.isZen(any())).thenReturn(false);
        modelExecutor = new RoutingAgentModelExecutor(nativeExecutor, legacyExecutor, zen, store);
        run = run();

        when(repository.workingState(any(), any())).thenReturn(null);
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(new ArrayList<>());
        when(repository.listSteps(any(), any())).thenReturn(List.of());
        when(repository.pendingModelTurn(any())).thenReturn(java.util.Optional.empty());
        when(repository.beginModelCall(any(), any(), any())).thenReturn(UUID.randomUUID().toString());
        when(repository.recordModelTurnWithSettlement(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.recordToolResult(any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void nativeSnapshotKeepsPromptAndWriteToolsWhenConfigurationSwitchesToLegacyMidFlight() {
        var providerA = provider("model-native-a", EnumSet.of(ModelCapability.NATIVE_TOOLS, ModelCapability.CHAT));
        var providerB = provider("model-legacy-b", EnumSet.of(ModelCapability.CHAT));
        when(store.require(run)).thenReturn(providerA, providerB);
        var captured = new AtomicReference<List<ModelMessage>>();
        var capturedTools = new AtomicReference<List<AgentToolDefinition>>();
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            capturedTools.set(invocation.getArgument(1));
            return new ModelTurnResult("", List.of(new ModelToolCall("call-1", "create_task_after_approval",
                    json.createObjectNode().put("title", "x"))), ModelFinishReason.TOOL_CALLS, null,
                    "OPENAI_COMPATIBLE", "model-native-a", 5L);
        });

        var coordinator = coordinator(List.of(writeTool()));
        coordinator.advance(run);

        // 提示词按准备时的原生快照渲染：不出现 Legacy 只读契约
        String prompt = systemPrompt(captured.get());
        assertThat(prompt).doesNotContain("当前运行模式：Legacy（只读）");
        // 工具协议同样按 A：写工具仍然暴露
        assertThat(capturedTools.get()).extracting(AgentToolDefinition::name).contains("create_task_after_approval");
        // 出站走原生执行器
        verify(nativeExecutor).callModel(anyList(), anyList(), any(), any(), any());
        // 下一次请求才解析到 B（Legacy）
        assertThat(modelExecutor.resolveRequest(run).legacyMode()).isTrue();
    }

    @Test
    void legacySnapshotKeepsReadOnlyContractWhenConfigurationSwitchesToNativeMidFlight() {
        var providerA = provider("model-legacy-a", EnumSet.of(ModelCapability.CHAT));
        var providerB = provider("model-native-b", EnumSet.of(ModelCapability.NATIVE_TOOLS, ModelCapability.CHAT));
        when(store.require(run)).thenReturn(providerA, providerB);
        var captured = new AtomicReference<List<ModelMessage>>();
        when(legacyExecutor.callModel(anyList(), anyList(), any(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    captured.set(invocation.getArgument(0));
                    return new ModelTurnResult("只读回答", List.of(), ModelFinishReason.STOP, null,
                            "OPENAI_COMPATIBLE", "model-legacy-a", 5L);
                });

        var coordinator = coordinator(List.of(writeTool()));
        coordinator.advance(run);

        // 提示词按准备时的 Legacy 快照渲染：写操作不可用
        assertThat(systemPrompt(captured.get())).contains("当前运行模式：Legacy（只读）");
        // 下一次请求才解析到 B（原生，写工具可用）
        assertThat(modelExecutor.resolveRequest(run).legacyMode()).isFalse();
    }

    @Test
    void configurationIsResolvedOncePerRequestEvenWhenToolsAreTriggered() {
        var providerA = provider("model-native-a", EnumSet.of(ModelCapability.NATIVE_TOOLS, ModelCapability.CHAT));
        when(store.require(run)).thenReturn(providerA);
        var resolves = new AtomicInteger();
        when(store.require(run)).thenAnswer(invocation -> {
            resolves.incrementAndGet();
            return providerA;
        });
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any()))
                .thenReturn(new ModelTurnResult("完成", List.of(), ModelFinishReason.STOP, null,
                        "OPENAI_COMPATIBLE", "model-native-a", 5L));

        coordinator(List.of()).advance(run);

        assertThat(resolves.get()).isEqualTo(1);
    }

    private AgentRuntimeCoordinator coordinator(List<AgentTool> tools) {
        return new AgentRuntimeCoordinator(repository, contextAssembler(), skillRegistry(), planService(),
                new AgentToolRegistry(tools), new AgentCancellationService(repository), new AgentLoopGuard(),
                mock(AgentApprovalService.class), modelExecutor, new AgentToolResultSanitizer(json), json);
    }

    private AgentContextAssembler contextAssembler() {
        var assembler = mock(AgentContextAssembler.class);
        when(assembler.assemble(any(), any(), any())).thenReturn(context());
        return assembler;
    }

    private com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry skillRegistry() {
        return new com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry();
    }

    private AgentPlanService planService() {
        var service = mock(AgentPlanService.class);
        when(service.ensurePlan(any(), any())).thenReturn(AgentPlan.create("检查本周任务是否影响交付", List.of()));
        return service;
    }

    private AgentTool writeTool() {
        return new AgentTool() {
            @Override public String name() { return "create_task_after_approval"; }
            @Override public boolean writesBusinessData() { return true; }
            @Override public AgentToolDefinition definition() {
                return AgentToolDefinition.fromJson("create_task_after_approval", "创建任务",
                        "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}}}", true);
            }
            @Override public AgentToolResult execute(AgentToolContext context, com.fasterxml.jackson.databind.JsonNode arguments) {
                return new AgentToolResult(arguments, List.of(), List.of());
            }
        };
    }

    private String systemPrompt(List<ModelMessage> messages) {
        assertThat(messages).isNotNull();
        return messages.stream().filter(ModelMessage.System.class::isInstance)
                .map(ModelMessage.System.class::cast).map(ModelMessage.System::content)
                .filter(content -> content.contains("AI Collab"))
                .findFirst().orElseThrow();
    }

    private UserAiProvider provider(String modelName, EnumSet<ModelCapability> capabilities) {
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), modelName,
                ModelProviderType.OPENAI_COMPATIBLE, "https://example.invalid", "/v1/chat/completions",
                "enc", modelName, true, 0.2, 1024, capabilities, true,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    private AgentRunView run() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查本周任务是否影响交付", AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                0, 0, 0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, "ITERATION_PLANNING", 1, now, now);
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(), "OWNER", false,
                AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 0, List.of());
    }
}
