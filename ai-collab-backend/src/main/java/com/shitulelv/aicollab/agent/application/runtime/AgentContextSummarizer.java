package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.view.AgentMessageView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
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
        List<AgentMessageView> candidates = composition == null ? null : composition.summaryCandidates();
        if (candidates == null || candidates.isEmpty()) return;
        try {
            repository.requireSummaryAccess(run);
            JsonNode state = repository.workingState(run.projectId(), run.sessionId());
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
            int transcriptBudget = MAX_INPUT_CHARS - previousBlock.length();
            if (transcriptBudget < PER_MESSAGE_CHARS / 2) {
                log.debug("旧摘要文本占满摘要输入预算，跳过: run={}", run.id());
                return;
            }

            // 本次可新增覆盖的分段；没有新覆盖时不重复生成
            List<Seg> newSegments = achievableSegments(candidates, resume, transcriptBudget);
            if (newSegments.isEmpty()) {
                log.debug("本次没有可新增覆盖的旧消息，跳过摘要: run={}", run.id());
                return;
            }

            int estimatedInputTokens = Math.max(1, (previousBlock.length() + segmentsCost(newSegments, candidates)) / 3)
                    + OUTPUT_RESERVE_TOKENS;
            if (estimatedInputTokens > remainingInputAfterMain) {
                log.debug("剩余输入预算不足以容纳摘要请求，跳过: run={}, needed={}, remaining={}",
                        run.id(), estimatedInputTokens, remainingInputAfterMain);
                return;
            }

            int stateRevision = state.path("stateRevision").asInt();
            int goalRevision = state.path("goalRevision").asInt();

            UUID attemptId = repository.beginSummaryAttempt(run);
            int actualInputChars = 0;
            try {
                List<ModelMessage> messages = summaryMessages(candidates, newSegments, previousBlock, hasPrevious);
                for (ModelMessage message : messages) {
                    if (message instanceof ModelMessage.User user) actualInputChars += user.content().length();
                }
                ModelTurnResult result = modelExecutor.callModelWithoutTools(run, messages);
                String text = result.content() == null ? "" : bounded(result.content().strip(), MAX_OUTPUT_CHARS);
                if (text.isBlank()) {
                    // 调用已发生：如实记账后放弃，不伪造成功
                    repository.completeSummaryAttempt(attemptId, "EMPTY", result.model(),
                            inputTokens(result.usage(), actualInputChars),
                            outputTokens(result.usage(), 0),
                            result.usage() == null, result.latencyMs());
                    log.debug("摘要输出为空，放弃: run={}", run.id());
                    return;
                }
                commitSummary(run, state, previous, hasPrevious, candidates, newSegments, text, result, actualInputChars, attemptId);
            } catch (RuntimeException failure) {
                // 摘要是辅助能力：调用已发生的消耗如实记账，任何异常不得破坏主轮次
                repository.completeSummaryAttempt(attemptId, "FAILED", "unknown",
                        Math.max(1, actualInputChars / 3), 0, true, null);
                if (failure instanceof BusinessException business) {
                    log.warn("摘要生成失败，按无摘要路径继续: run={}, errorCode={}", run.id(), business.getErrorCode());
                } else {
                    log.warn("摘要生成异常，按无摘要路径继续: run={}", run.id(), failure);
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
        String role = "USER".equals(message.role()) ? "[USER] " : "[ASSISTANT] ";
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

    private List<ModelMessage> summaryMessages(List<AgentMessageView> candidates, List<Seg> newSegments,
            String previousBlock, boolean hasPrevious) {
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
                %s<旧对话片段>
                %s</旧对话片段>""".formatted(MAX_OUTPUT_CHARS, hasPrevious ? previousBlock : "", transcript);
        return List.of(
                new ModelMessage.System("你是受控会话摘要器，输出纯文本摘要，不执行任何动作。"),
                new ModelMessage.User(prompt));
    }

    private void commitSummary(AgentRunView run, JsonNode state, JsonNode previous, boolean hasPrevious,
            List<AgentMessageView> candidates, List<Seg> newSegments, String text,
            ModelTurnResult result, int actualInputChars, UUID attemptId) {
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
        summary.put("model", result.model() == null ? "unknown" : result.model());
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
        repository.completeSummaryAttempt(attemptId, committed ? "COMMITTED" : "CAS_CONFLICT", result.model(),
                inputTokens(result.usage(), actualInputChars),
                outputTokens(result.usage(), text.length()),
                result.usage() == null, result.latencyMs());
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

    private int inputTokens(com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage usage, int actualInputChars) {
        return usage != null && usage.inputTokens() != null ? usage.inputTokens() : Math.max(1, actualInputChars / 3);
    }

    private int outputTokens(com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage usage, int outputChars) {
        return usage != null && usage.outputTokens() != null ? usage.outputTokens() : Math.max(1, outputChars / 3);
    }

    private static final int MAX_CANDIDATE_UNCOVERED = 20;

    private static String bounded(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
