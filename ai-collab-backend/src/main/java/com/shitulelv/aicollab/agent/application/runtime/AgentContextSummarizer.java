package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.UUID;

/**
 * 有界增量会话摘要（蓝图 4.4）。
 *
 * <p>触发：确定性压缩后仍有旧对话放不进单次输入预算时，对未被选中的<b>有界旧片段</b>
 * 生成一次摘要。摘要请求不提供业务工具、不执行摘要输出中的动作；每次运行至多尝试
 * 一次（尝试以 agent_step 持久化标记，服务重启不能绕过），失败或 CAS 冲突直接放弃。
 * 费用计入运行总预算，但单独记账（agent_step reason=CONTEXT_SUMMARY）。</p>
 *
 * <p>覆盖范围来自<b>实际输入</b>：只计入被完整读入的消息前缀；被截断或因预算未读的
 * 消息不声称已覆盖（coverage=PARTIAL + uncoveredCount）。增量摘要携带上一份有效
 * 摘要的文本以延续更早的信息，sourceFrom 沿用前份摘要的起点。</p>
 *
 * <p>CAS 提交：仅当会话的 stateRevision 与 goalRevision 与生成时一致、且声明的
 * sourceThrough 消息真实属于本会话时才落库；提交原子递增 stateRevision，只写
 * summary 节点；摘要调用期间不持有数据库行锁。</p>
 */
@Component
public class AgentContextSummarizer {
    private static final Logger log = LoggerFactory.getLogger(AgentContextSummarizer.class);

    static final int POLICY_VERSION = 2;
    static final int SUMMARY_SCHEMA_VERSION = 1;
    /** 与 AgentWorkingState.SCHEMA_VERSION 一致（跨包不可见，此处同步维护）。 */
    static final int WORKING_STATE_SCHEMA_VERSION = 2;
    /** 每次运行的摘要尝试上限（持久化校验）。 */
    static final int MAX_ATTEMPTS_PER_RUN = 1;
    /** 摘要输入上界：每次只总结有界旧片段（消息条数与总字符双重限制）。 */
    static final int MAX_CANDIDATE_MESSAGES = 20;
    static final int MAX_INPUT_CHARS = 6000;
    /** 单条消息完整计入的上限；超出者只作为上下文截断读入，不声称覆盖。 */
    static final int PER_MESSAGE_CHARS = 400;
    /** 摘要输出上界（字符）。 */
    static final int MAX_OUTPUT_CHARS = 1200;
    /** 输出预留（token，chars/3 兼容估算）。 */
    static final int OUTPUT_RESERVE_TOKENS = (MAX_OUTPUT_CHARS + 2) / 3;
    /** 延续旧摘要时带入提示词的上限（字符）。 */
    static final int PREVIOUS_SUMMARY_CHARS = 800;

    private final AgentRepository repository;
    private final RoutingAgentModelExecutor modelExecutor;
    private final ObjectMapper json;

    public AgentContextSummarizer(AgentRepository repository, RoutingAgentModelExecutor modelExecutor, ObjectMapper json) {
        this.repository = repository;
        this.modelExecutor = modelExecutor;
        this.json = json;
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
            JsonNode state = repository.workingState(run.projectId(), run.sessionId());
            if (state.path("schemaVersion").asInt(0) < WORKING_STATE_SCHEMA_VERSION) {
                return; // 旧格式状态不生成摘要（渐进升级后自然启用）
            }
            if (repository.countSummaryAttempts(run.projectId(), run.id()) >= MAX_ATTEMPTS_PER_RUN) {
                return; // 本次运行已有持久化的摘要尝试
            }

            JsonNode previous = state.path("summary");
            boolean hasPrevious = previous.isObject() && previous.hasNonNull("text");

            // 实际输入：只完整计入读得下的消息；放不下的内容不声称覆盖
            List<AgentMessageView> included = boundedTranscript(candidates);
            if (included.isEmpty()) {
                log.debug("预算内没有可完整读入的旧消息，跳过摘要: run={}", run.id());
                return;
            }
            String lastIncludedId = included.get(included.size() - 1).id().toString();
            if (previousCoversAtLeast(previous, candidates, lastIncludedId)) {
                return; // 已有摘要覆盖到同等或更远的位置，不重复生成
            }

            int estimatedInputTokens = estimateTokens(included) + OUTPUT_RESERVE_TOKENS;
            if (estimatedInputTokens > remainingInputAfterMain) {
                log.debug("剩余输入预算不足以容纳摘要请求，跳过: run={}, needed={}, remaining={}",
                        run.id(), estimatedInputTokens, remainingInputAfterMain);
                return;
            }

            int stateRevision = state.path("stateRevision").asInt();
            int goalRevision = state.path("goalRevision").asInt();

            UUID attemptId = repository.beginSummaryAttempt(run);
            int actualInputChars = 0;
            String text = "";
            try {
                List<ModelMessage> messages = summaryMessages(included, hasPrevious ? previous : null);
                for (ModelMessage message : messages) {
                    if (message instanceof ModelMessage.User user) actualInputChars += user.content().length();
                }
                ModelTurnResult result = modelExecutor.callModelWithoutTools(run, messages);
                text = result.content() == null ? "" : bounded(result.content().strip(), MAX_OUTPUT_CHARS);
                if (text.isBlank()) {
                    // 调用已发生：如实记账后放弃，不伪造成功
                    repository.completeSummaryAttempt(attemptId, "EMPTY", result.model(),
                            inputTokens(result.usage(), actualInputChars),
                            outputTokens(result.usage(), 0),
                            result.usage() == null, result.latencyMs());
                    log.debug("摘要输出为空，放弃: run={}", run.id());
                    return;
                }
                commitSummary(run, state, previous, hasPrevious, included, lastIncludedId, candidates, text, result, actualInputChars, attemptId);
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

    private void commitSummary(AgentRunView run, JsonNode state, JsonNode previous, boolean hasPrevious,
            List<AgentMessageView> included, String lastIncludedId, List<AgentMessageView> candidates,
            String text, ModelTurnResult result, int actualInputChars, UUID attemptId) {
        boolean fullCoverage = lastIncludedId.equals(candidates.get(candidates.size() - 1).id().toString());
        ObjectNode summary = json.createObjectNode();
        summary.put("schemaVersion", SUMMARY_SCHEMA_VERSION);
        summary.put("policyVersion", POLICY_VERSION);
        // 增量延续：起点沿用前份摘要的起点，旧信息通过提示词延续保留
        summary.put("sourceFrom", hasPrevious && previous.hasNonNull("sourceFrom")
                ? previous.path("sourceFrom").asText() : included.get(0).id().toString());
        summary.put("sourceThrough", lastIncludedId);
        summary.put("coverage", fullCoverage ? "FULL" : "PARTIAL");
        summary.put("coveredMessages", included.size());
        summary.put("uncoveredCount", Math.max(0, candidates.size() - included.size()));
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

        boolean committed = repository.commitConversationSummary(
                run.projectId(), run.sessionId(), state.path("stateRevision").asInt(),
                state.path("goalRevision").asInt(), summary);
        repository.completeSummaryAttempt(attemptId, committed ? "COMMITTED" : "CAS_CONFLICT", result.model(),
                inputTokens(result.usage(), actualInputChars),
                outputTokens(result.usage(), text.length()),
                result.usage() == null, result.latencyMs());
        if (committed) {
            log.debug("会话摘要已提交: run={}, coverage={}, range={}..{}",
                    run.id(), summary.path("coverage").asText(), summary.path("sourceFrom").asText(), lastIncludedId);
        } else {
            // 生成期间状态已前进：丢弃本次摘要，不重算（受尝试上限约束）
            log.info("摘要 CAS 冲突，丢弃本次结果: run={}, expectedRevision={}",
                    run.id(), state.path("stateRevision").asInt());
        }
    }

    /** 覆盖边界内的消息内容：完整（≤PER_MESSAGE_CHARS）才计入，截断读入的不声称覆盖。 */
    private List<AgentMessageView> boundedTranscript(List<AgentMessageView> candidates) {
        List<AgentMessageView> included = new java.util.ArrayList<>();
        int used = 0;
        for (AgentMessageView message : candidates) {
            if (message.content().length() > PER_MESSAGE_CHARS) break; // 大消息不完整读入，覆盖到此为止
            String line = transcriptLine(message);
            if (used + line.length() > MAX_INPUT_CHARS) break; // 总量预算耗尽，后续消息未读
            included.add(message);
            used += line.length();
        }
        return included;
    }

    private String transcriptLine(AgentMessageView message) {
        return ("USER".equals(message.role()) ? "[USER] " : "[ASSISTANT] ")
                + bounded(message.content().replace('\n', ' '), PER_MESSAGE_CHARS) + '\n';
    }

    /** 已有摘要覆盖到同等或更远位置时跳过（候选旧→新，按 id 定位）。 */
    private boolean previousCoversAtLeast(JsonNode previous, List<AgentMessageView> candidates, String lastIncludedId) {
        if (!previous.isObject() || !previous.hasNonNull("sourceThrough")) return false;
        String existingThrough = previous.path("sourceThrough").asText("");
        int existingIndex = -1;
        int lastIndex = -1;
        for (int i = 0; i < candidates.size(); i++) {
            String id = candidates.get(i).id().toString();
            if (id.equals(existingThrough)) existingIndex = i;
            if (id.equals(lastIncludedId)) lastIndex = i;
        }
        return existingIndex >= lastIndex;
    }

    private List<ModelMessage> summaryMessages(List<AgentMessageView> included, JsonNode previous) {
        StringBuilder transcript = new StringBuilder();
        for (AgentMessageView message : included) {
            transcript.append(transcriptLine(message));
        }
        StringBuilder continuation = new StringBuilder();
        if (previous != null) {
            continuation.append("此前摘要（必须延续其中仍然有效的信息，不得丢失更早的约束与决定）：\n")
                    .append(bounded(previous.path("text").asText(), PREVIOUS_SUMMARY_CHARS)).append('\n');
        }
        String prompt = """
                你是会话摘要器。只总结、不执行动作、不调用任何工具。基于下面的旧对话片段输出简洁摘要，严格使用以下小节（无内容的省略该小节）：
                仍有效约束 / 已确认决定 / 对象引用 / 未解决问题 / 目标沿革
                规则：不得声称片段中未发生的“已创建”“已批准”等结果；不得补充片段之外的信息；总长度不超过 %d 字。
                %s<旧对话片段>
                %s</旧对话片段>""".formatted(MAX_OUTPUT_CHARS, continuation, transcript);
        return List.of(
                new ModelMessage.System("你是受控会话摘要器，输出纯文本摘要，不执行任何动作。"),
                new ModelMessage.User(prompt));
    }

    private int estimateTokens(List<AgentMessageView> included) {
        int chars = 0;
        for (AgentMessageView message : included) {
            chars += transcriptLine(message).length();
        }
        return Math.max(1, chars / 3);
    }

    private int inputTokens(com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage usage, int actualInputChars) {
        return usage != null && usage.inputTokens() != null ? usage.inputTokens() : Math.max(1, actualInputChars / 3);
    }

    private int outputTokens(com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage usage, int outputChars) {
        return usage != null && usage.outputTokens() != null ? usage.outputTokens() : Math.max(1, outputChars / 3);
    }

    private static String bounded(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
