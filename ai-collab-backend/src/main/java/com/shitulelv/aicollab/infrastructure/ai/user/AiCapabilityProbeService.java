package com.shitulelv.aicollab.infrastructure.ai.user;

import com.fasterxml.jackson.databind.*;
import com.shitulelv.aicollab.infrastructure.ai.*;
import com.shitulelv.aicollab.infrastructure.ai.model.*;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import com.shitulelv.aicollab.common.exception.*;
import com.shitulelv.aicollab.planning.application.PlanningJsonContract;
import com.shitulelv.aicollab.planning.domain.PlanMilestone;
import com.shitulelv.aicollab.planning.domain.PlanTask;
import com.shitulelv.aicollab.planning.domain.SkeletonModelOutput;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One synthetic request per selected capability. Never executes model-proposed tools.
 *
 * <p>{@code PLANNING_JSON} 必须复现真实规划的输出契约，而不是一个宽松的近似：
 * 线上曾出现过探针显示“规划 JSON 通过”、真实规划却因骨架契约不满足而失败的情况
 * （模型只吐了极简形状，缺少 summary/assumptions/risks/milestones 等必填字段）。
 * 因此该模式直接复用生产 schema 与生产解析器（{@link PlanningJsonContract}），
 * 并按真实链路顺序跑完<b>骨架</b>与<b>细节</b>两个阶段，
 * 让“探针通过”等价于“该模型能完成真实规划”。</p>
 *
 * <p>两个阶段都采用与真实规划相同的“生成 + 一次定向修复”策略
 * （见 {@code TaskPlanGenerationOrchestrator} 的骨架/细节修复路径）：
 * 失败时把原始输出与结构化失败信息交给模型定向修一次，而不是原样重试。
 * 若探针只采样一次而真实规划有修复机会，探针会比真实规划更悲观，
 * 出现“探针偶发失败、真实规划成功”的误报。</p>
 */
@Service
public class AiCapabilityProbeService {
    /** 规划阶段统一的系统提示词，与生产的规划系统提示同形。 */
    private static final String PLANNING_SYSTEM =
            "你是项目规划 JSON 生成器。只输出符合指定 JSON Schema 的 JSON，不输出 Markdown。";

    public enum Mode { CONNECTIVITY, PLANNING_JSON, STREAMING, NATIVE_TOOLS }
    public record Evidence(Mode mode, String provider, String model, long latencyMs, boolean verified) {}

    /**
     * 对外暴露的探针结果（含读取时判定的 STALE）。
     * status 为落库原值；stale=true 表示配置已变更，结论不再描述当前配置。
     */
    public record ProbeResultView(
            String mode, String status, String model, String detail, String errorCode,
            Long latencyMs, java.time.OffsetDateTime testedAt, boolean stale) {}

    private final UserAiProviderService providers;
    private final ChatModelGateway chat;
    private final ModelTurnGateway turns;
    private final ObjectMapper json;
    private final PlanningJsonContract planningContract;
    private final UserAiProviderProbeResultRepository probeResults;

    @org.springframework.beans.factory.annotation.Autowired
    public AiCapabilityProbeService(UserAiProviderService providers, ChatModelGateway chat, ModelTurnGateway turns,
            ObjectMapper json, PlanningJsonContract planningContract,
            UserAiProviderProbeResultRepository probeResults) {
        this.providers=providers; this.chat=chat; this.turns=turns; this.json=json;
        this.planningContract=planningContract;
        this.probeResults=probeResults;
    }

    /**
     * 执行一次能力探针并落库结果；异常原样抛出（前端仍拿到分类错误），失败结论同时持久化。
     */
    public Evidence test(UUID user, UUID id, Mode mode) {
        var selected = providers.requireRuntimeConfiguration(user, id);
        providers.requireSnapshotAuthorized(selected);
        long startedAt = System.currentTimeMillis();
        try (var snapshot = new AiConfigurationContext(selected)) {
            Evidence evidence = execute(selected, user, mode);
            record(selected, mode, UserAiProviderProbeResultRepository.PASS,
                    evidence.model(), null, null, evidence.latencyMs());
            return evidence;
        } catch (Exception failure) {
            String status = classify(failure);
            String detail = failure instanceof BusinessException business && business.getMessage() != null
                    ? business.getMessage() : "所选能力测试未满足输出契约";
            String errorCode = failure instanceof BusinessException business
                    ? business.getErrorCode().name() : null;
            record(selected, mode, status, selected.modelName(), detail, errorCode,
                    System.currentTimeMillis() - startedAt);
            throw failure;
        }
    }

    /** 该 Provider 各探针模式的最新落库结果；配置变更后的旧结果以 stale 标记。 */
    public List<ProbeResultView> results(UUID user, UUID id) {
        var provider = providers.requireRuntimeConfiguration(user, id);
        return probeResults.findAllByProviderId(id).stream()
                .map(row -> new ProbeResultView(row.mode(), row.status(), row.modelName(), row.detail(),
                        row.errorCode(), row.latencyMs(), row.testedAt(),
                        !provider.updatedAt().equals(row.testedConfigUpdatedAt())))
                .toList();
    }

    private void record(UserAiProvider provider, Mode mode, String status, String model,
            String detail, String errorCode, Long latencyMs) {
        try {
            probeResults.upsert(new UserAiProviderProbeResultRepository.ProbeResultRow(
                    provider.id(), mode.name(), status, model, detail, errorCode, latencyMs,
                    provider.updatedAt(), null));
        } catch (RuntimeException persistenceFailure) {
            // 结果落库失败不影响探针结论本身：调用方已拿到真实结果/错误。
            org.slf4j.LoggerFactory.getLogger(AiCapabilityProbeService.class)
                    .warn("Failed to persist probe result for provider {}: {}", provider.id(),
                            persistenceFailure.getMessage());
        }
    }

    /**
     * 失败分类：临时故障（网络/超时/限流/5xx）不形成能力结论，记 INCONCLUSIVE；
     * 鉴权/路径/契约问题记 FAIL；能力未声明（生产门控拒绝）记 NOT_SUPPORTED。
     */
    static String classify(Throwable failure) {
        if (failure instanceof BusinessException business) {
            ErrorCode code = business.getErrorCode();
            if (code == ErrorCode.AI_PROVIDER_UNAVAILABLE) {
                String message = String.valueOf(business.getMessage());
                if (message.contains("不支持") || message.contains("必须支持")) {
                    return UserAiProviderProbeResultRepository.NOT_SUPPORTED;
                }
                return UserAiProviderProbeResultRepository.INCONCLUSIVE;
            }
            if (code == ErrorCode.AI_MODEL_CREDENTIAL_INVALID
                    || code == ErrorCode.AI_PROVIDER_REQUEST_REJECTED
                    || code == ErrorCode.AI_PROVIDER_NOT_FOUND
                    || code == ErrorCode.AI_PROVIDER_INVALID_RESPONSE
                    || code == ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED
                    || code == ErrorCode.VALIDATION_ERROR) {
                return UserAiProviderProbeResultRepository.FAIL;
            }
        }
        // 超时/限流/HTTP 5xx/未知故障：未能形成能力结论。
        return UserAiProviderProbeResultRepository.INCONCLUSIVE;
    }

    private Evidence execute(UserAiProvider selected, UUID user, Mode mode) {
        // AiConfigurationContext 已由 test() 建立（ThreadLocal 钉住本 provider）。
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
                    mode == Mode.PLANNING_JSON ? skeletonPrompt() : "回复 probe",
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
                var skeleton = verifyPlanningSkeleton(result.content(), user);
                verifyPlanningDetail(skeleton, user);
            }
            return new Evidence(mode,result.provider(),result.model(),result.latencyMs(),true);
    }

    /**
     * 规划能力探针的提示词：携带生产骨架 schema，要求模型按真实契约输出。
     *
     * <p>用最少的任务与里程碑数量，让探针保持低成本；同时覆盖 union 类型字段
     * （{@code targetDate}），这正是真实规划中容易触发上游结构化输出限制的地方。</p>
     */
    private String skeletonPrompt() {
        String schema = planningContract == null ? null : planningContract.skeletonSchema(1);
        if (schema == null) {
            // 契约门面不可用时退化为显式形状要求（仍要求完整必填字段集）。
            return "只输出一个 JSON 对象，字段必须完整包含 summary、assumptions、risks、milestones、tasks，"
                    + "其中 milestones 与 tasks 各至少 1 项，字段名为 tempKey/title/objective/sortOrder，"
                    + "任务额外包含 milestoneTempKey。不要输出其他文字。";
        }
        return PLANNING_SYSTEM + "\n"
                + "<JSON_SCHEMA>\n" + schema + "\n</JSON_SCHEMA>\n"
                + "为「合成能力测试」生成 1 个里程碑与 1 个任务的骨架 JSON，不要输出解释。\n"
                + "只输出合法 JSON 对象，不要输出其他文字、解释或 Markdown 代码块。";
    }

    /**
     * 用生产解析器校验骨架输出，并检查业务完整性；不合契约时按生产口径做一次定向修复。
     *
     * <p>任何不符合契约的输出都判为该能力不可用——这正是探针存在的意义：
     * 提前把“规划跑不通”的模型暴露出来，而不是等用户点提交才失败。</p>
     *
     * <p>修复方式与真实规划一致（生成 + 一次定向修复，见
     * {@code TaskPlanGenerationOrchestrator.generateSkeletonWithOneRepair}）：
     * 把原始输出与结构化失败信息交给模型修一次，而不是原样重试。
     * 原样重试对“结构性错误”无效——模型会重复同样的错误；
     * 而真实规划用的正是定向修复，所以探针必须照抄，否则探针会比真实规划更悲观（误报）。</p>
     */
    private SkeletonModelOutput verifyPlanningSkeleton(String content, UUID callerUserId) {
        if (planningContract == null) {
            // 无契约门面时至少要求完整必填字段集，避免退化为“宽松近似”。
            try {
                String value = content.strip();
                if (value.startsWith("```") && value.endsWith("```")) {
                    value = value.substring(value.indexOf('\n') + 1, value.length() - 3).strip();
                }
                JsonNode parsed = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
                for (String field : List.of("summary", "assumptions", "risks", "milestones", "tasks")) {
                    if (!parsed.has(field)) invalid();
                }
                if (!parsed.path("milestones").isArray() || parsed.path("milestones").isEmpty()) invalid();
                if (!parsed.path("tasks").isArray() || parsed.path("tasks").isEmpty()) invalid();
            } catch (java.io.IOException malformed) {
                invalid();
            }
            return null;
        }
        SkeletonModelOutput skeleton = tryParseSkeleton(content);
        if (skeleton != null) return skeleton;
        // 与生产一致：一次定向修复（见 TaskPlanGenerationOrchestrator.generateSkeletonWithOneRepair）。
        // 原样重试对结构性错误无效——模型会重复同样的错误；真实规划用的正是定向修复，
        // 探针若照抄生产口径，就不会比真实规划更悲观（避免误报）。
        String repairPrompt = skeletonPrompt() + "\n"
                + planningContract.repairPrompt(content, planningContract.skeletonSchema(1),
                        "SKELETON", "UNKNOWN", null, List.of());
        ChatCompletionResult repaired = chat.complete(planningCommand(repairPrompt, callerUserId));
        if (repaired == null || repaired.content() == null || repaired.content().isBlank()) invalid();
        SkeletonModelOutput repairedSkeleton = tryParseSkeleton(repaired.content());
        if (repairedSkeleton == null) invalid();
        return repairedSkeleton;
    }

    /**
     * 解析并校验骨架输出；不符合契约时返回 {@code null}，由调用方决定是否走修复。
     */
    private SkeletonModelOutput tryParseSkeleton(String content) {
        try {
            var skeleton = planningContract.parseSkeleton(content);
            if (!PlanningJsonContract.isSkeletonCoherent(skeleton)) return null;
            return skeleton;
        } catch (RuntimeException contractViolation) {
            return null;
        }
    }

    /**
     * 细节阶段探针：用生产 schema 让模型补全细节，再用生产解析器与生产合并规则校验。
     *
     * <p>细节阶段的 union 类型字段（{@code estimatedHours}/{@code startDate}/{@code dueDate}/
     * {@code suggestedAssigneeId}）是上游结构化输出语法最容易拒绝的形状，骨架通过并不代表
     * 细节也能过——真实规划中这正是第二个失败点。探针必须把这一段也覆盖到。</p>
     *
     * <p>除了解析契约，还必须校验细节的 key 与骨架<b>一一对应</b>：
     * 真实规划合并细节时会拒绝重复/未知/缺失 key，探针漏掉这一层就会放行
     * 一个真实规划必然失败的模型。</p>
     */
    private void verifyPlanningDetail(SkeletonModelOutput skeleton, UUID callerUserId) {
        if (planningContract == null || skeleton == null) return;
        List<PlanMilestone> milestones = skeleton.milestones().stream()
                .map(m -> new PlanMilestone(m.tempKey(), m.title(), m.objective(), null, null, m.sortOrder(), List.of()))
                .toList();
        List<PlanTask> tasks = skeleton.tasks().stream()
                .map(t -> new PlanTask(t.tempKey(), t.milestoneTempKey(), t.title(), t.objective(),
                        null, null, null, null, null, null, null, List.of(), List.of(), t.sortOrder()))
                .toList();
        String detailSchema = planningContract.detailSchema(milestones, tasks);
        String detailPrompt = PLANNING_SYSTEM + "\n"
                + "<JSON_SCHEMA>\n" + detailSchema + "\n</JSON_SCHEMA>\n"
                + "<SKELETON_IDENTITY>\n"
                + skeleton.milestones().stream().map(m -> "milestone " + m.tempKey()).toList() + "\n"
                + skeleton.tasks().stream().map(t -> "task " + t.tempKey()).toList() + "\n"
                + "</SKELETON_IDENTITY>\n"
                + "为上述里程碑与任务补全细节 JSON：每个 milestone 输出 tempKey/description/sourceRefs，"
                + "每个 task 输出 tempKey/description/priority/estimatedHours/startDate/dueDate/"
                + "suggestedAssigneeId/dependencyTempKeys/sourceRefs；没有依据的字段显式输出 null 或空数组。\n"
                + "只输出合法 JSON 对象，不要输出其他文字、解释或 Markdown 代码块。";
        String raw = requestDetail(detailPrompt, callerUserId);
        if (detailIsValid(skeleton, raw)) return;
        // 与生产一致：一次定向修复，判定标准保持不变。
        String repairPrompt = detailPrompt + "\n"
                + planningContract.repairPrompt(raw, detailSchema, "DETAIL",
                        "UNKNOWN", null, List.of());
        raw = requestDetail(repairPrompt, callerUserId);
        if (!detailIsValid(skeleton, raw)) invalid();
    }

    /**
     * 发起一次细节阶段请求并返回原始输出内容。
     */
    private String requestDetail(String prompt, UUID callerUserId) {
        ChatCompletionResult result = chat.complete(planningCommand(prompt, callerUserId));
        if (result == null || result.content() == null || result.content().isBlank()) invalid();
        return result.content();
    }

    /**
     * 细节输出是否同时满足解析契约与“key 与骨架一一对应”。
     *
     * <p>后者是探针曾经的盲区：真实规划合并细节时会拒绝重复/未知/缺失 key，
     * 只做解析校验会放行一个真实规划必然失败的模型。</p>
     */
    private boolean detailIsValid(SkeletonModelOutput skeleton, String raw) {
        try {
            var detail = planningContract.parseDetail(raw);
            return PlanningJsonContract.detailKeyMismatch(skeleton, detail) == null;
        } catch (RuntimeException contractViolation) {
            return false;
        }
    }

    /**
     * 规划阶段统一的出站命令：提示词强制 JSON，不携带原生 schema。
     *
     * <p>与生产的规划出站形态一致（见 {@code TaskPlanModelClient.command}）：
     * 规划永不请求上游原生 JSON 模式。</p>
     */
    private ChatCompletionCommand planningCommand(String prompt, UUID callerUserId) {
        return new ChatCompletionCommand(null, PLANNING_SYSTEM, prompt,
                ChatCompletionCommand.OutputFormat.PROMPT_JSON,
                ModelPurpose.PLANNING, null, List.of(), callerUserId);
    }

    private static void invalid() { throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,"所选能力测试未满足输出契约"); }
}
