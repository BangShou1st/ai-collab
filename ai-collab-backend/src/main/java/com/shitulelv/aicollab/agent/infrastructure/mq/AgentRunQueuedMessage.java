package com.shitulelv.aicollab.agent.infrastructure.mq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Agent run 入队通知消息体（M2 §3.1 第 3 行）：runId + source 语义。
 *
 * <p>消息是<b>触发器而非载荷</b>：消费者收到后只做一次 claim 尝试（claimNext 按数据库
 * 状态竞争领取），消息里的 runId 仅用于日志与诊断；消息丢失、重复、乱序均无害
 * （轮询兜底 + claim 幂等竞争）。</p>
 *
 * <p>source 标记"进入 QUEUED"的路径来源（与落点清单一一对应），供派发延迟观测与
 * 面板区分：RUN_CREATED / RUN_RETRY / RUN_RESUMED / TOOL_RESULT_REQUEUED /
 * RUN_REQUEUED / USER_INPUT_CONTINUED / DELEGATION_CHILD_CREATED /
 * DELEGATION_PARENT_RESUMED / APPROVAL_EXPIRED_REQUEUED。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentRunQueuedMessage(UUID runId, String source) {

    /** 消息身份属性（与 M1 文档链路同款：便于 broker 侧按属性检索/诊断）。 */
    public static final String PROP_RUN_ID = "agentRunId";
    public static final String PROP_SOURCE = "source";

    public static AgentRunQueuedMessage parse(UUID runId, String source) {
        return new AgentRunQueuedMessage(runId, source == null || source.isBlank() ? "UNKNOWN" : source);
    }
}
