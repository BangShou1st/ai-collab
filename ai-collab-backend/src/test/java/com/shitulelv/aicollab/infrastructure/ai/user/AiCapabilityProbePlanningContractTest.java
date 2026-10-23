package com.shitulelv.aicollab.infrastructure.ai.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnGateway;
import com.shitulelv.aicollab.planning.application.PlanningJsonContract;
import com.shitulelv.aicollab.planning.application.TaskPlanOutputParser;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规划能力探针与真实规划的同源契约。
 *
 * <p>探针的价值在于"提前暴露跑不通规划的模型"。一旦探针与真实规划的口径分叉，
 * 就会出现"探针通过、真实规划失败"（假阳性，用户被坑）或
 * "探针失败、真实规划成功"（误报，用户白白换模型）两种伤害。
 * 线上真实发生过前者：探针只检查极简形状 {@code {"tasks":[{"title":"probe"}]}}，
 * 而真实规划要求完整骨架契约，minimax 因此显示"通过"却跑不通规划。</p>
 *
 * <p>本测试锁住三条口径：</p>
 * <ol>
 *   <li>探针跑完<b>骨架 + 细节</b>两个阶段（真实规划的失败点有两个）；</li>
 *   <li>细节阶段除解析契约外，还必须校验 key 与骨架一一对应
 *       （真实规划合并细节时会拒绝重复/未知/缺失 key）；</li>
 *   <li>不合契约时走一次<b>定向修复</b>（与真实规划同策略），
 *       而不是直接判失败或原样重试。</li>
 * </ol>
 */
class AiCapabilityProbePlanningContractTest {

    private static final String VALID_SKELETON = """
            {
              "summary": "合成能力测试摘要",
              "assumptions": [],
              "risks": [],
              "milestones": [
                {"tempKey":"m1","title":"里程碑一","objective":"验证规划契约","targetDate":null,"sortOrder":0}
              ],
              "tasks": [
                {"tempKey":"t1","milestoneTempKey":"m1","title":"任务一","objective":"产出合法 JSON","sortOrder":0}
              ]
            }
            """;

    private static final String VALID_DETAIL = """
            {
              "milestones": [
                {"tempKey":"m1","description":"里程碑描述","sourceRefs":[]}
              ],
              "tasks": [
                {"tempKey":"t1","description":"任务描述","priority":"MEDIUM","estimatedHours":null,
                 "startDate":null,"dueDate":null,"suggestedAssigneeId":null,
                 "dependencyTempKeys":[],"sourceRefs":[]}
              ]
            }
            """;

    private ChatModelGateway gateway;
    private AiCapabilityProbeService probe;
    private final ObjectMapper json = new ObjectMapper();
    private UUID userId;
    private UUID providerId;

    @BeforeEach
    void setUp() {
        gateway = mock(ChatModelGateway.class);
        userId = UUID.randomUUID();
        providerId = UUID.randomUUID();

        var providers = mock(UserAiProviderService.class);
        when(providers.requireRuntimeConfiguration(any(), any())).thenReturn(provider());
        // requireSnapshotAuthorized 是 void 且不抛异常，mock 默认即放行。

        var parser = new TaskPlanOutputParser(json);
        var contract = new PlanningJsonContract(parser, json);
        probe = new AiCapabilityProbeService(providers, gateway, mock(ModelTurnGateway.class), json, contract,
                mock(UserAiProviderProbeResultRepository.class));
    }

    private UserAiProvider provider() {
        return new UserAiProvider(providerId, userId, "Kilo Gateway Free",
                ModelProviderType.OPENAI_COMPATIBLE, "https://ai-gateway.kilo.ai/api/v1", "/chat/completions",
                null, "minimax/minimax-m3.1-flash:free", true, 0.2, 6000,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STRUCTURED_OUTPUT),
                true, OffsetDateTime.now(), OffsetDateTime.now(), "KILO_GATEWAY_FREE");
    }

    private void respondWith(String... contents) {
        var queue = new ArrayList<String>(List.of(contents));
        when(gateway.complete(any())).thenAnswer(invocation -> {
            String content = queue.isEmpty() ? "{}" : queue.remove(0);
            return new ChatCompletionResult(content, "openai-compatible",
                    "minimax/minimax-m3.1-flash:free", 10, 20, 100L);
        });
    }

    @Test
    void planningProbeRunsBothSkeletonAndDetailStages() {
        respondWith(VALID_SKELETON, VALID_DETAIL);

        var evidence = probe.test(userId, providerId, AiCapabilityProbeService.Mode.PLANNING_JSON);

        assertThat(evidence.verified()).isTrue();
        // 骨架 + 细节 = 两次出站；探针只跑骨架就会漏掉真实规划的第二个失败点。
        verify(gateway, times(2)).complete(any());
    }

    @Test
    void planningProbeRejectsDetailWhoseKeysDoNotMatchSkeleton() {
        // 细节输出用了骨架里不存在的 tempKey：解析契约能过，但真实规划合并时必然失败
        // （DETAIL_UNKNOWN_MILESTONE_KEY / DETAIL_UNKNOWN_TASK_KEY）。
        String mismatchedDetail = """
                {
                  "milestones": [
                    {"tempKey":"m9","description":"里程碑描述","sourceRefs":[]}
                  ],
                  "tasks": [
                    {"tempKey":"t9","description":"任务描述","priority":"MEDIUM","estimatedHours":null,
                     "startDate":null,"dueDate":null,"suggestedAssigneeId":null,
                     "dependencyTempKeys":[],"sourceRefs":[]}
                  ]
                }
                """;
        // 骨架一次就合法，细节两次都不合法（生成 + 一次修复后仍不合法）。
        respondWith(VALID_SKELETON, mismatchedDetail, mismatchedDetail);

        assertThatThrownBy(() -> probe.test(userId, providerId, AiCapabilityProbeService.Mode.PLANNING_JSON))
                .isInstanceOf(BusinessException.class);
        // 骨架 1 次 + 细节 2 次（生成 + 修复）
        verify(gateway, times(3)).complete(any());
    }

    @Test
    void planningProbeRepairsInvalidSkeletonOnceInsteadOfSamplingAgain() {
        // 第一次骨架不合契约；修复一次后合法。
        String brokenSkeleton = """
                {"summary":"缺少里程碑与任务","assumptions":[],"risks":[]}
                """;
        respondWith(brokenSkeleton, VALID_SKELETON, VALID_DETAIL);

        var evidence = probe.test(userId, providerId, AiCapabilityProbeService.Mode.PLANNING_JSON);

        assertThat(evidence.verified()).isTrue();
        // 修复请求必须携带原始输出与结构化失败信息（定向修复），而不是原样重试。
        ArgumentCaptor<ChatCompletionCommand> captor = ArgumentCaptor.forClass(ChatCompletionCommand.class);
        verify(gateway, times(3)).complete(captor.capture());
        String repairPrompt = captor.getAllValues().get(1).userPrompt();
        assertThat(repairPrompt).contains("<UNTRUSTED_INVALID_OUTPUT_BASE64>");
        assertThat(repairPrompt).contains("</UNTRUSTED_INVALID_OUTPUT_BASE64>");
        assertThat(repairPrompt).contains("stage=SKELETON");
        // 原始输出以 base64 承载（与生产 repairPrompt 一致），避免不可信内容破坏提示词结构。
        assertThat(repairPrompt).contains(
                java.util.Base64.getEncoder().encodeToString(
                        brokenSkeleton.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // 修复请求仍须携带生产 schema —— 判定标准不因修复而放宽。
        assertThat(repairPrompt).contains("\"required\":[\"summary\",\"assumptions\",\"risks\",\"milestones\",\"tasks\"]");
    }

    @Test
    void planningProbeNeverRequestsProviderNativeJsonMode() {
        respondWith(VALID_SKELETON, VALID_DETAIL);

        probe.test(userId, providerId, AiCapabilityProbeService.Mode.PLANNING_JSON);

        ArgumentCaptor<ChatCompletionCommand> captor = ArgumentCaptor.forClass(ChatCompletionCommand.class);
        verify(gateway, times(2)).complete(captor.capture());
        for (ChatCompletionCommand command : captor.getAllValues()) {
            assertThat(command.outputFormat()).isEqualTo(ChatCompletionCommand.OutputFormat.PROMPT_JSON);
            assertThat(command.outputSchema()).isNull();
        }
    }
}
