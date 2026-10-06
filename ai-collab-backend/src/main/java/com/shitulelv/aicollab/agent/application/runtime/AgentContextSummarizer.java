package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.view.AgentMessageView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder.UsageSettlement;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 有界增量会话摘要（蓝图 4.4）。
 *
 * <p>触发：确定性压缩后仍有旧对话放不进单次输入预算时，对未被选中的<b>有界旧片段</b>
 * 生成一次摘要。摘要请求不提供业务工具、不执行摘要输出中的动作；每次运行至多尝试
 * 一次（尝试以 agent_step 持久化标记，服务重启不能绕过），失败或 CAS 冲突直接放弃。
 * 费用计入所属运行总预算，但单独记账（agent_step reason=CONTEXT_SUMMARY）。</p>
 *
 * <p>覆盖语义（以消息 ID + 偏移记录，不虚报）：</p>
 * <ul>
 *   <li>短消息（≤ {@value #PER_MESSAGE_CHARS} 字符）被触碰即完整覆盖；</li>
 *   <li>长消息按偏移分段覆盖（每段 {@value #SEGMENT_CHARS} 字符），跨运行推进，
 *       未读完的消息明确保留在 uncoveredMessageIds 中，后续运行继续读取后续片段；</li>
 *   <li>上一份摘要文本<b>完整</b>进入本次请求以延续更早信息，不截断——
 *       新增片段的预算相应减少，incorporatedPrevious 因此真实成立。</li>
 * </ul>
 *
 * <p>CAS 提交：仅当会话的 stateRevision 与 goalRevision 与生成时一致、且声明的
 * sourceThrough 消息真实属于本会话时才落库；提交原子递增 stateRevision，只写
 * summary 节点；摘要调用期间不持有数据库行锁。</p>
 */
@Component
public class AgentContextSummarizer {
    private static final Logger log = LoggerFactory.getLogger(AgentContextSummarizer.class);

    static final int POLICY_VERSION = 4;
    static final int SUMMARY_SCHEMA_VERSION = 1;
    /** 与 AgentWorkingState.SCHEMA_VERSION 一致（跨包不可见，此处同步维护）。 */
    static final int WORKING_STATE_SCHEMA_VERSION = 2;
    /** 每次运行的摘要尝试上限（持久化校验）。 */
    static final int MAX_ATTEMPTS_PER_RUN = 1;
    /** 摘要输入上界（字符），含完整旧摘要文本与本次新增片段。 */
    static final int MAX_INPUT_CHARS = 6000;
    /** 单条消息被触碰即完整覆盖的上限；超出者按偏移分段覆盖。 */
    static final int PER_MESSAGE_CHARS = 400;
    /** 长消息每次运行读取的片段长度（字符）。 */
    static final int SEGMENT_CHARS = 600;
    /** 摘要输出上界（字符）。 */
    static final int MAX_OUTPUT_CHARS = 1200;
    /** 输出预留（token，chars/3 兼容估算）。 */
    static final int OUTPUT_RESERVE_TOKENS = (MAX_OUTPUT_CHARS + 2) / 3;
    /** 摘要节点保留的覆盖分段上限。 */

    private final AgentRepository repository;
    private final RoutingAgentModelExecutor modelExecutor;
    private final ObjectMapper json;

    public AgentContextSummarizer(AgentRepository repository, RoutingAgentModelExecutor modelExecutor, ObjectMapper json) {
        this.repository = repository;
        this.modelExecutor = modelExecutor;
        this.json = json;
    }

    /** 一段已覆盖内容：消息 ID + [from,to) 偏移（to == 消息长度即完整覆盖）。 */
    private record Seg(String messageId, int from, int to) {
    }

    /**
     * 尝试为本次组装未覆盖的旧对话生成摘要。
     *
     * @param remainingInputAfterMain 主请求预算扣除后剩余的运行输入预算（token）
     */
    public void maybeSummarize(
            AgentRunView run,
            AgentModelMessageComposer.Composition composition,
            int remainingInputAfterMain) {
        maybeSummarize(run, composition, remainingInputAfterMain, () -> true);
    }

    /**
     * 同上；timeRemaining 供重压缩前复查运行剩余时长（不把重压缩耗在已到时的运行上）。
     */
    public void maybeSummarize(
            AgentRunView run,
            AgentModelMessageComposer.Composition composition,
            int remainingInputAfterMain,
            java.util.function.BooleanSupplier timeRemaining) {
        maybeSummarize(run, composition, remainingInputAfterMain, Integer.MAX_VALUE, timeRemaining);
    }

    /**
     * 同上；remainingOutputTokens 是运行的剩余输出预算：摘要请求（含重压缩）与主调用
     * 的输出预留分别核算，输出额度不足时禁止再消耗，已发生用量如实保留。
     */
    public void maybeSummarize(
            AgentRunView run,
            AgentModelMessageComposer.Composition composition,
            int remainingInputAfterMain,
            int remainingOutputTokens,
            java.util.function.BooleanSupplier timeRemaining) {
        List<AgentMessageView> candidates = composition == null ? null : composition.summaryCandidates();
        if (candidates == null || candidates.isEmpty()) return;
        try {
            repository.requireSummaryAccess(run);
            JsonNode state = repository.workingState(run.projectId(), run.sessionId());
            if (state == null) return; // 无工作状态（无 v2 结构可延续）不生成摘要
            if (state.path("schemaVersion").asInt(0) < WORKING_STATE_SCHEMA_VERSION) {
                return; // 旧格式状态不生成摘要（渐进升级后自然启用）
            }
            if (repository.countSummaryAttempts(run.projectId(), run.id()) >= MAX_ATTEMPTS_PER_RUN) {
                return; // 本次运行已有持久化的摘要尝试
            }

            JsonNode previous = state.path("summary");
            boolean hasPrevious = previous.isObject() && previous.hasNonNull("text");
            Map<String, Integer> resume = resumeOffsets(previous);

            // 上一份摘要文本完整进入请求（不截断），新增片段使用剩余输入预算
            String previousBlock = hasPrevious
                    ? "此前摘要（必须延续其中仍然有效的信息，不得丢失更早的约束与决定）：\n"
                      + previous.path("text").asText() + "\n"
                    : "";
            String currentStateBlock = currentStateBlock(state);
            int transcriptBudget = MAX_INPUT_CHARS - previousBlock.length() - currentStateBlock.length();
            if (transcriptBudget < PER_MESSAGE_CHARS / 2) {
                log.debug("当前约束或旧摘要占满摘要输入预算，跳过: run={}", run.id());
                return;
            }

            // 本次可新增覆盖的分段；没有新覆盖时不重复生成
            List<Seg> newSegments = achievableSegments(candidates, resume, transcriptBudget);
            if (newSegments.isEmpty()) {
                log.debug("本次没有可新增覆盖的旧消息，跳过摘要: run={}", run.id());
                return;
            }

            int estimatedInputTokens = Math.max(1, (previousBlock.length() + currentStateBlock.length() + segmentsCost(newSegments, candidates)) / 3)
                    + OUTPUT_RESERVE_TOKENS;
            if (estimatedInputTokens > remainingInputAfterMain) {
                log.debug("剩余输入预算不足以容纳摘要请求，跳过: run={}, needed={}, remaining={}",
                        run.id(), estimatedInputTokens, remainingInputAfterMain);
                return;
            }
            // 输出预算独立核算：输出预留放不下时不发起摘要（估算进输入不等于输出额度检查）
            if (OUTPUT_RESERVE_TOKENS > remainingOutputTokens) {
                log.debug("剩余输出预算不足以容纳摘要输出预留，跳过: run={}, reserve={}, remaining={}",
                        run.id(), OUTPUT_RESERVE_TOKENS, remainingOutputTokens);
                return;
            }

            int stateRevision = state.path("stateRevision").asInt();
            int goalRevision = state.path("goalRevision").asInt();

            // 首次摘要请求走持久化准入边界（beginSummaryAttempt 内的运行行锁检查）：
            // 暂停意图先落库时不创建请求身份、不发模型请求——这是控制结果，
            // 不是摘要失败，不消耗重试，主请求由 beginModelCall 的同一边界收口 PAUSED。
            UUID attemptId;
            try {
                attemptId = repository.beginSummaryAttempt(run);
            } catch (BusinessException admissionRefused) {
                if (admissionRefused.getErrorCode() == ErrorCode.AGENT_RUN_PAUSED) {
                    log.debug("暂停意图已落库，摘要请求未获准入，按无摘要路径继续: run={}", run.id());
                    return;
                }
                throw admissionRefused;
            }
            int actualInputChars = 0;
            UsageSettlement firstUsage = null;
            try {
                List<ModelMessage> messages = summaryMessages(candidates, newSegments, previousBlock, currentStateBlock, hasPrevious);
                for (ModelMessage message : messages) {
                    if (message instanceof ModelMessage.User user) actualInputChars += user.content().length();
                }
                ModelTurnResult result = modelExecutor.callModelWithoutTools(run, messages);
                // 记账依据完整实际响应，绝不按截短后的文本估算；
                // 来源按原始 usage 显式判定，缺失侧用实际请求/响应证据补齐
                String text = result.content() == null ? "" : result.content().strip();
                firstUsage = UsageSettlement.fromRaw(result.usage(),
                        Math.max(1, actualInputChars / 3), Math.max(1, text.length() / 3), result.latencyMs());
                if (text.isBlank()) {
                    // 调用已发生：如实记账后放弃，不伪造成功
                    repository.completeSummaryAttempt(attemptId, "EMPTY", result.model(), firstUsage, null);
                    log.debug("摘要输出为空，放弃: run={}", run.id());
                    return;
                }
                String recompressNote = null;
                if (!qualifies(text)) {
                    // 一次有界重新压缩：超长或结尾不完整的输出不能当作完整增量摘要提交。
                    // 第二次调用前重新核算取消状态、剩余时长与剩余预算（扣除第一次真实/估算用量），
                    // 请求自身保持有界；不足以承担重压缩时保留上一份有效摘要并明确降级原因。
                    if (repository.isCancelRequested(run.projectId(), run.id())) {
                        // 第一次调用已发生：结算其真实用量，保留上一份摘要、不推进覆盖
                        repository.completeSummaryAttempt(attemptId, "CANCELED", result.model(), firstUsage,
                                "RECOMPRESS_SKIPPED_CANCELED");
                        log.info("摘要重压缩前检测到取消，保留上一份摘要并结算首次用量: run={}", run.id());
                        return;
                    }
                    if (!timeRemaining.getAsBoolean()) {
                        repository.completeSummaryAttempt(attemptId, "DOWNSGRADED_UNQUALIFIED", result.model(), firstUsage,
                                "RECOMPRESS_SKIPPED_TIME_EXHAUSTED");
                        log.warn("运行剩余时长不足以承担重压缩，保留上一份摘要: run={}", run.id());
                        return;
                    }
                    int recompressEstimate = recompressRequestTokens(text);
                    int remainingForRecompress = remainingInputAfterMain - firstUsage.bookedInput();
                    if (recompressEstimate > remainingForRecompress) {
                        repository.completeSummaryAttempt(attemptId, "DOWNSGRADED_UNQUALIFIED", result.model(), firstUsage,
                                "RECOMPRESS_SKIPPED_BUDGET_INSUFFICIENT");
                        log.warn("剩余预算不足以容纳有界重压缩请求，保留上一份摘要: run={}, needed={}, remaining={}",
                                run.id(), recompressEstimate, remainingForRecompress);
                        return;
                    }
                    // 输出预算独立复查：首次摘要的输出消耗计入后，输出预留仍须放得下重压缩
                    int remainingOutputForRecompress = remainingOutputTokens - firstUsage.bookedOutput();
                    if (OUTPUT_RESERVE_TOKENS > remainingOutputForRecompress) {
                        repository.completeSummaryAttempt(attemptId, "DOWNSGRADED_UNQUALIFIED", result.model(), firstUsage,
                                "RECOMPRESS_SKIPPED_OUTPUT_BUDGET");
                        log.warn("剩余输出预算不足以容纳重压缩输出预留，保留上一份摘要: run={}, reserve={}, remaining={}",
                                run.id(), OUTPUT_RESERVE_TOKENS, remainingOutputForRecompress);
                        return;
                    }
                    // 重压缩是新的出站请求：再次走持久化准入边界。暂停意图先落库时
                    // 不创建重压缩身份、不发出；首次调用已发生的结果照常结算，
                    // 沿用上一份有效摘要（控制结果，不是摘要失败，不消耗重试）
                    UUID recompressId;
                    try {
                        recompressId = repository.beginSummaryRecompressAttempt(run);
                    } catch (BusinessException admissionRefused) {
                        if (admissionRefused.getErrorCode() == ErrorCode.AGENT_RUN_PAUSED) {
                            repository.completeSummaryAttempt(attemptId, "PAUSED", result.model(), firstUsage,
                                    "RECOMPRESS_SKIPPED_PAUSED");
                            log.info("摘要重压缩前检测到暂停意图，保留上一份摘要并结算首次用量: run={}", run.id());
                            return;
                        }
                        if (admissionRefused.getErrorCode() == ErrorCode.AGENT_RUN_CANCELED) {
                            repository.completeSummaryAttempt(attemptId, "CANCELED", result.model(), firstUsage,
                                    "RECOMPRESS_SKIPPED_CANCELED");
                            log.info("摘要重压缩前运行已离开运行状态，保留上一份摘要并结算首次用量: run={}", run.id());
                            return;
                        }
                        throw admissionRefused;
                    }
                    ModelTurnResult retry;
                    try {
                        retry = modelExecutor.callModelWithoutTools(run, recompressMessages(text));
                    } catch (RuntimeException reFailure) {
                        // 第二次失败：第一次的真实用量已单独结算；第二次请求正文已构造完成——
                        // 异常携带的提供商用量优先，缺失侧输入按实际请求大小估算，
                        // 输出确实无响应证据才标 UNKNOWN，不把有据可估记成未知零值
                        com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage carried = reFailure instanceof
                                com.shitulelv.aicollab.infrastructure.ai.model.ProviderResponseFailure provider
                                ? new com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage(
                                        provider.promptTokens(), provider.completionTokens()) : null;
                        repository.completeSummaryRecompressAttempt(recompressId, "FAILED", "unknown",
                                UsageSettlement.fromRaw(carried, Math.max(1, recompressRequestChars(text) / 3), 0, null), null);
                        repository.completeSummaryAttempt(attemptId, "DOWNSGRADED_UNQUALIFIED", result.model(), firstUsage,
                                "RECOMPRESS_FAILED");
                        log.warn("摘要重压缩失败，保留上一份摘要且首次用量已结算: run={}", run.id(), reFailure);
                        return;
                    }
                    String recompressed = retry.content() == null ? "" : retry.content().strip();
                    repository.completeSummaryRecompressAttempt(recompressId, "SETTLED", retry.model(),
                            UsageSettlement.fromRaw(retry.usage(),
                                    Math.max(1, recompressRequestChars(text) / 3),
                                    Math.max(1, recompressed.length() / 3), retry.latencyMs()), null);
                    recompressNote = "RECOMPRESS_SETTLED";
                    text = recompressed;
                }
                if (!qualifies(text)) {
                    // 无法得到合格摘要：保留上一份有效摘要、不推进本次覆盖，降级原因入账
                    repository.completeSummaryAttempt(attemptId, "DOWNSGRADED_UNQUALIFIED", result.model(), firstUsage,
                            recompressNote == null ? null : "RECOMPRESS_STILL_UNQUALIFIED");
                    log.warn("摘要不合格（超长或结尾不完整），保留上一份摘要且不推进覆盖: run={}, length={}",
                            run.id(), text.length());
                    return;
                }
                commitSummary(run, state, previous, hasPrevious, candidates, newSegments, text,
                        result.model(), firstUsage, attemptId, recompressNote);
            } catch (RuntimeException failure) {
                // 摘要是辅助能力：任何异常不得破坏主轮次。ProviderResponseFailure 携带的
                // 提供商用量优先保留（含单侧）；第一次调用已返回的真实 usage 不因后续失败
                // 丢失；无响应证据时按实际请求大小估算，输出侧显式 UNKNOWN
                if (firstUsage != null) {
                    repository.completeSummaryAttempt(attemptId, "FAILED", "unknown", firstUsage,
                            "POST_RESPONSE_FAILURE");
                } else if (failure instanceof com.shitulelv.aicollab.infrastructure.ai.model.ProviderResponseFailure provider) {
                    repository.completeSummaryAttempt(attemptId, "FAILED", "unknown",
                            UsageSettlement.fromRaw(new com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage(
                                    provider.promptTokens(), provider.completionTokens()),
                                    Math.max(1, actualInputChars / 3), 0, null), null);
                } else {
                    repository.completeSummaryAttempt(attemptId, "FAILED", "unknown",
                            UsageSettlement.fromRaw(null, Math.max(1, actualInputChars / 3), 0, null), null);
                }
                if (failure instanceof BusinessException business) {
                    log.warn("摘要生成失败，按无摘要路径继续: run={}, errorCode={}", run.id(), business.getErrorCode());
                } else {
                    log.warn("摘要生成异常，按无摘要路径继续: run={}, {}", run.id(), failure);
                }
            }
        } catch (BusinessException failure) {
            log.warn("摘要生成失败，按无摘要路径继续: run={}, errorCode={}", run.id(), failure.getErrorCode());
        }
    }

    /** 上一份摘要的覆盖进度：messageId → 已覆盖到的最大偏移。 */
    private Map<String, Integer> resumeOffsets(JsonNode previous) {
        Map<String, Integer> resume = new java.util.LinkedHashMap<>();
        if (previous != null && previous.isObject()) {
            for (JsonNode segment : previous.path("segments")) {
                String messageId = segment.path("messageId").asText("");
                if (messageId.isEmpty()) continue;
                resume.merge(messageId, segment.path("to").asInt(0), Math::max);
            }
        }
        return resume;
    }

    /** 按旧→新顺序计算本次可新增的覆盖分段；预算耗尽即止，不虚报未读内容。 */
    private List<Seg> achievableSegments(List<AgentMessageView> candidates, Map<String, Integer> resume, int transcriptBudget) {
        List<Seg> segments = new ArrayList<>();
        int used = 0;
        for (AgentMessageView message : candidates) {
            String messageId = message.id().toString();
            int contentLen = normalized(message).length();
            int from = resume.getOrDefault(messageId, 0);
            if (from >= contentLen) continue; // 该消息已完整覆盖
            int to = contentLen <= PER_MESSAGE_CHARS
                    ? contentLen
                    : Math.min(contentLen, from + SEGMENT_CHARS);
            int cost = segmentLine(message, from, to).length();
            if (used + cost > transcriptBudget) continue; // 预算放不下，本条保留为未覆盖
            segments.add(new Seg(messageId, from, to));
            used += cost;
        }
        return segments;
    }

    private int segmentsCost(List<Seg> segments, List<AgentMessageView> candidates) {
        Map<String, AgentMessageView> byId = new java.util.LinkedHashMap<>();
        for (AgentMessageView message : candidates) byId.put(message.id().toString(), message);
        int cost = 0;
        for (Seg segment : segments) {
            AgentMessageView message = byId.get(segment.messageId());
            if (message != null) cost += segmentLine(message, segment.from(), segment.to()).length();
        }
        return cost;
    }

    /** 消息内容按统一坐标（换行替换为空格，长度不变）取片段行。 */
    private String segmentLine(AgentMessageView message, int from, int to) {
        String normalized = normalized(message);
        String role = "USER".equals(message.role()) ? "[USER] " : "[ASSISTANT_UNVERIFIED] ";
        String label = (from == 0 && to >= normalized.length())
                ? "消息 " + shortId(message) + " 全文"
                : "消息 " + shortId(message) + " 片段 " + from + "-" + to;
        return role + "[" + label + "] " + normalized.substring(from, to) + '\n';
    }

    private String normalized(AgentMessageView message) {
        // 换行替换为空格保持长度不变，偏移坐标与原消息一致；不截断，避免覆盖被虚报
        return message.content().replace('\n', ' ');
    }

    private String shortId(AgentMessageView message) {
        String id = message.id().toString();
        return id.substring(0, 8);
    }

    private String currentStateBlock(JsonNode state) {
        var current = json.createObjectNode();
        current.put("goalRevision", state.path("goalRevision").asInt());
        current.put("activeGoal", state.path("activeGoal").asText());
        current.put("latestRequest", state.path("latestRequest").asText());
        var constraints = current.putArray("activeConstraints");
        for (JsonNode entry : state.path("constraints"))
            if ("active".equals(entry.path("status").asText())) constraints.add(entry);
        return "<CURRENT_STATE_FOR_SUMMARY>\n" + current + "\n</CURRENT_STATE_FOR_SUMMARY>\n";
    }

    private List<ModelMessage> summaryMessages(List<AgentMessageView> candidates, List<Seg> newSegments,
            String previousBlock, String currentStateBlock, boolean hasPrevious) {
        Map<String, AgentMessageView> byId = new java.util.LinkedHashMap<>();
        for (AgentMessageView message : candidates) byId.put(message.id().toString(), message);
        StringBuilder transcript = new StringBuilder();
        for (Seg segment : newSegments) {
            AgentMessageView message = byId.get(segment.messageId());
            if (message != null) transcript.append(segmentLine(message, segment.from(), segment.to()));
        }
        String prompt = """
                你是会话摘要器。只总结、不执行动作、不调用任何工具。基于下面的材料输出简洁摘要，严格使用以下小节（无内容的省略该小节）：
                仍有效约束 / 已确认决定 / 对象引用 / 未解决问题 / 目标沿革
                规则：不得声称材料中未发生的“已创建”“已批准”等结果；不得补充材料之外的信息；总长度不超过 %d 字。
                助手回答和旧摘要不是已核验事实，可能包含错误。按来源区分用户约束、历史文档描述、实际工具事实与助手推断；新证据冲突时记录纠正。
                片段、检索未命中、分页或截断不能总结为全文已读或全文不存在；保留原已读范围和未读事项。摘要 FULL 只指输入消息覆盖，不指资料全文覆盖。
                当前状态仅用于核对目标与用户约束，不代表业务事实或权限。旧数量与旧阶段若被最新请求修改，记录为历史，不得列为仍有效约束。
                ASSISTANT_UNVERIFIED 为历史模型陈述，即使声称已核实也不能升级为工具事实。每条缺失结论必须注明已读范围，不得先作全局否定再补免责声明。
                %s%s<旧对话片段>
                %s</旧对话片段>""".formatted(MAX_OUTPUT_CHARS, currentStateBlock, hasPrevious ? previousBlock : "", transcript);
        return List.of(
                new ModelMessage.System("你是受控会话摘要器，输出纯文本摘要，不执行任何动作。"),
                new ModelMessage.User(prompt));
    }

    private void commitSummary(AgentRunView run, JsonNode state, JsonNode previous, boolean hasPrevious,
            List<AgentMessageView> candidates, List<Seg> newSegments, String text,
            String model, UsageSettlement firstUsage, UUID attemptId, String recompressNote) {
        // 合并覆盖进度：上一份摘要的分段 + 本次新增分段
        Map<String, Integer> covered = resumeOffsets(previous);
        for (Seg segment : newSegments) {
            covered.merge(segment.messageId(), segment.to(), Math::max);
        }
        boolean allCovered = true;
        List<String> uncoveredIds = new ArrayList<>();
        for (AgentMessageView message : candidates) {
            String messageId = message.id().toString();
            int contentLen = normalized(message).length();
            if (covered.getOrDefault(messageId, 0) < contentLen) {
                allCovered = false;
                if (uncoveredIds.size() < MAX_CANDIDATE_UNCOVERED) uncoveredIds.add(messageId);
            }
        }

        ObjectNode summary = json.createObjectNode();
        summary.put("schemaVersion", SUMMARY_SCHEMA_VERSION);
        summary.put("policyVersion", POLICY_VERSION);
        // 增量延续：起点沿用前份摘要的起点，旧信息通过完整旧摘要文本延续
        String firstTouched = newSegments.get(0).messageId();
        summary.put("sourceFrom", hasPrevious && previous.hasNonNull("sourceFrom")
                ? previous.path("sourceFrom").asText() : firstTouched);
        summary.put("sourceThrough", newSegments.get(newSegments.size() - 1).messageId());
        summary.put("coverage", allCovered ? "FULL" : "PARTIAL");
        summary.put("coveredMessages", covered.size());
        summary.put("uncoveredCount", uncoveredIds.size());
        var uncovered = summary.putArray("uncoveredMessageIds");
        for (String id : uncoveredIds) uncovered.add(id);
        summary.put("incorporatedPrevious", hasPrevious);
        summary.put("stateRevision", state.path("stateRevision").asInt());
        summary.put("goalRevision", state.path("goalRevision").asInt());
        summary.put("text", text);
        summary.put("model", model == null ? "unknown" : model);
        summary.put("createdAt", OffsetDateTime.now().toString());
        // 仍有效约束是确定性快照，不是模型自由改写的产物
        var constraints = summary.putArray("activeConstraints");
        for (JsonNode entry : state.path("constraints")) {
            if ("active".equals(entry.path("status").asText())) constraints.add(entry.path("value").asText());
        }
        if (previous.hasNonNull("completedBefore")) summary.set("completedBefore", previous.get("completedBefore"));
        var segmentsNode = summary.putArray("segments");
        // 单消息覆盖始终是连续前缀；合并偏移，绝不能因旧记录数量丢弃新进度。
        covered.entrySet().forEach(entry ->
                segmentsNode.addObject().put("messageId", entry.getKey()).put("from",0).put("to",entry.getValue()));
        repository.compactSummaryCoverage(run, summary);

        boolean committed = repository.commitConversationSummary(
                run.projectId(), run.sessionId(), state.path("stateRevision").asInt(),
                state.path("goalRevision").asInt(), summary);
        // CAS 冲突：文本不覆盖进度，但第一次调用的真实用量照常结算（终态转换一次性）
        repository.completeSummaryAttempt(attemptId, committed ? "COMMITTED" : "CAS_CONFLICT", model,
                firstUsage, recompressNote);
        if (committed) {
            log.debug("会话摘要已提交: run={}, coverage={}, newSegments={}, range={}..{}",
                    run.id(), summary.path("coverage").asText(), newSegments.size(),
                    summary.path("sourceFrom").asText(), summary.path("sourceThrough").asText());
        } else {
            // 生成期间状态已前进：丢弃本次摘要，不重算（受尝试上限约束）
            log.info("摘要 CAS 冲突，丢弃本次结果: run={}, expectedRevision={}",
                    run.id(), state.path("stateRevision").asInt());
        }
    }

    /** 重压缩请求自身有界：估算其输入 token（提示词 + 草稿全文 + 输出预留）。 */
    private int recompressRequestTokens(String draft) {
        return Math.max(1, recompressRequestChars(draft) / 3) + OUTPUT_RESERVE_TOKENS;
    }

    private int recompressRequestChars(String draft) {
        int prompt = 220; // recompressMessages 固定模板字符数（不含草稿）
        return prompt + draft.length();
    }

    /** 摘要合格标准（确定性）：非空且长度在输出容量内；超长即不可当作完整增量摘要提交。
     *  持久化从不 substring 截尾——容量不足时走一次有界重压缩，仍不合格则降级保留上一份。 */
    private boolean qualifies(String text) {
        return !text.isBlank() && text.length() <= MAX_OUTPUT_CHARS;
    }

    /** 一次有界重新压缩：要求保留小节结构与关键决定、完整收尾，只输出文本。 */
    private List<ModelMessage> recompressMessages(String draft) {
        String prompt = """
                你是受控会话摘要器。下面这份摘要草稿超出了 %d 字上限。
                请输出压缩后的完整摘要：保留小节结构（仍有效约束/已确认决定/对象引用/未解决问题/目标沿革）、
                更早的约束与决定（尤其是结尾部分的关键决定）、以及与当前最新请求相关的内容；
                不得新增材料之外的信息；总长度不超过 %d 字；以完整内容收尾；只输出摘要文本，不解释。
                <摘要草稿>
                %s
                </摘要草稿>""".formatted(MAX_OUTPUT_CHARS, MAX_OUTPUT_CHARS, draft);
        return List.of(
                new ModelMessage.System("你是受控会话摘要器，输出纯文本摘要，不执行任何动作。"),
                new ModelMessage.User(prompt));
    }

    private static final int MAX_CANDIDATE_UNCOVERED = 20;
}
