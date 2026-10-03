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

/**
 * 有界增量会话摘要（蓝图 4.4）。
 *
 * <p>触发：确定性压缩后仍有旧对话放不进单次输入预算时，对未被选中的<b>有界旧片段</b>
 * 生成一次摘要。摘要请求不提供业务工具、不执行摘要输出中的动作；每次运行至多尝试
 * 一次，失败或 CAS 冲突直接放弃（不无限重算）。费用计入运行总预算，但单独记账
 * （agent_step reason=CONTEXT_SUMMARY），不混入普通决策轮次。</p>
 *
 * <p>CAS 提交：仅当会话的 stateRevision 与 goalRevision 与生成时一致才落库，
 * 提交用 jsonb_set 只写 summary 节点，不拿旧 JSON 整块覆盖新状态；
 * 摘要调用期间不持有数据库行锁。</p>
 */
@Component
public class AgentContextSummarizer {
    private static final Logger log = LoggerFactory.getLogger(AgentContextSummarizer.class);

    static final int POLICY_VERSION = 1;
    static final int SUMMARY_SCHEMA_VERSION = 1;
    /** 与 AgentWorkingState.SCHEMA_VERSION 一致（跨包不可见，此处同步维护）。 */
    static final int WORKING_STATE_SCHEMA_VERSION = 2;
    /** 摘要输入上界：每次只总结有界旧片段（消息条数与总字符双重限制）。 */
    static final int MAX_CANDIDATE_MESSAGES = 20;
    static final int MAX_INPUT_CHARS = 6000;
    static final int PER_MESSAGE_CHARS = 400;
    /** 摘要输出上界（字符）。 */
    static final int MAX_OUTPUT_CHARS = 1200;
    /** 输出预留（token，chars/3 兼容估算）。 */
    static final int OUTPUT_RESERVE_TOKENS = (MAX_OUTPUT_CHARS + 2) / 3;

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
            if (alreadyCovered(state, candidates)) return;

            int summaryInputTokens = estimateTokens(candidates) + OUTPUT_RESERVE_TOKENS;
            if (summaryInputTokens > remainingInputAfterMain) {
                log.debug("剩余输入预算不足以容纳摘要请求，跳过: run={}, needed={}, remaining={}",
                        run.id(), summaryInputTokens, remainingInputAfterMain);
                return;
            }

            int stateRevision = state.path("stateRevision").asInt();
            int goalRevision = state.path("goalRevision").asInt();
            String sourceFrom = candidates.get(0).id().toString();
            String sourceThrough = candidates.get(candidates.size() - 1).id().toString();

            ModelTurnResult result = modelExecutor.callModelWithoutTools(run, summaryMessages(candidates));
            String text = result.content() == null ? "" : bounded(result.content().strip(), MAX_OUTPUT_CHARS);
            if (text.isBlank()) {
                log.debug("摘要输出为空，放弃: run={}", run.id());
                return;
            }

            ObjectNode summary = json.createObjectNode();
            summary.put("schemaVersion", SUMMARY_SCHEMA_VERSION);
            summary.put("policyVersion", POLICY_VERSION);
            summary.put("sourceFrom", sourceFrom);
            summary.put("sourceThrough", sourceThrough);
            summary.put("stateRevision", stateRevision);
            summary.put("goalRevision", goalRevision);
            summary.put("text", text);
            summary.put("model", result.model() == null ? "unknown" : result.model());
            summary.put("createdAt", OffsetDateTime.now().toString());
            // 仍有效约束是确定性快照，不是模型自由改写的产物
            var constraints = summary.putArray("activeConstraints");
            for (JsonNode entry : state.path("constraints")) {
                if ("active".equals(entry.path("status").asText())) constraints.add(entry.path("value").asText());
            }

            boolean committed = repository.commitConversationSummary(
                    run.projectId(), run.sessionId(), stateRevision, goalRevision, summary);
            repository.recordSummaryUsage(run, result.model(),
                    result.usage() == null ? null : result.usage().inputTokens(),
                    result.usage() == null ? null : result.usage().outputTokens(),
                    result.usage() == null, result.latencyMs());
            if (committed) {
                log.debug("会话摘要已提交: run={}, range={}", run.id(), sourceFrom + ".." + sourceThrough);
            } else {
                // 生成期间状态已前进：丢弃本次摘要，不重算（受调用上限约束）
                log.info("摘要 CAS 冲突，丢弃本次结果: run={}, expectedRevision={}", run.id(), stateRevision);
            }
        } catch (BusinessException failure) {
            log.warn("摘要生成失败，按无摘要路径继续: run={}, errorCode={}", run.id(), failure.getErrorCode());
        } catch (RuntimeException failure) {
            // 摘要是辅助能力，任何异常都不得破坏主轮次（如模型输出协议异常）
            log.warn("摘要生成异常，按无摘要路径继续: run={}", run.id(), failure);
        }
    }

    /** 现有摘要已覆盖到最新未选中的消息时不再重复生成。 */
    private boolean alreadyCovered(JsonNode state, List<AgentMessageView> candidates) {
        JsonNode summary = state.path("summary");
        if (!summary.isObject()) return false;
        String through = summary.path("sourceThrough").asText("");
        return !through.isEmpty() && through.equals(candidates.get(candidates.size() - 1).id().toString());
    }

    private List<ModelMessage> summaryMessages(List<AgentMessageView> candidates) {
        StringBuilder transcript = new StringBuilder();
        int used = 0;
        for (AgentMessageView message : candidates) {
            if (used >= MAX_INPUT_CHARS || transcript.length() >= MAX_INPUT_CHARS) break;
            String line = ("USER".equals(message.role()) ? "[USER] " : "[ASSISTANT] ")
                    + bounded(message.content().replace('\n', ' '), PER_MESSAGE_CHARS) + '\n';
            if (used + line.length() > MAX_INPUT_CHARS) break;
            transcript.append(line);
            used += line.length();
        }
        String prompt = """
                你是会话摘要器。只总结、不执行动作、不调用任何工具。基于下面的旧对话片段输出简洁摘要，严格使用以下小节（无内容的省略该小节）：
                仍有效约束 / 已确认决定 / 对象引用 / 未解决问题 / 目标沿革
                规则：不得声称片段中未发生的“已创建”“已批准”等结果；不得补充片段之外的信息；总长度不超过 %d 字。
                <旧对话片段>
                %s</旧对话片段>""".formatted(MAX_OUTPUT_CHARS, transcript);
        return List.of(
                new ModelMessage.System("你是受控会话摘要器，输出纯文本摘要，不执行任何动作。"),
                new ModelMessage.User(prompt));
    }

    private int estimateTokens(List<AgentMessageView> candidates) {
        int chars = 0;
        for (AgentMessageView message : candidates) {
            chars += Math.min(message.content().length(), PER_MESSAGE_CHARS) + 10;
        }
        return Math.max(1, chars / 3);
    }

    private static String bounded(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
