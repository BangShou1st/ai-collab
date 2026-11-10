package com.shitulelv.aicollab.agent.application.runtime;

import java.util.UUID;

/**
 * 子 Agent 委派终态唤醒事件（设计文档 §3.4 阶段 1：进程内事件唤醒）。
 *
 * <p>由 {@code AgentDelegationRecorder.resumeParent} 在"最后一个子运行终态、父运行
 * 重新排队"的同一事务提交后（afterCommit）发布；监听器
 * （{@code AgentRunWakeListener} 实现）据此立即向 {@code agentRunTaskExecutor}
 * 提交一次 claim 尝试，绕过 1s 调度 tick。</p>
 *
 * <p>唤醒是<b>优化而非常确性依赖</b>：事件丢失无害（tick 兜底仍在），监听器内部
 * 必须吞掉一切异常；claim 的 {@code FOR UPDATE SKIP LOCKED} 保证与 tick 竞争
 * 不会双跑同一运行。</p>
 *
 * <p>阶段 2（MQ 方案落地后）：改为子终态发 MQ 消息触发 claim，监听器接口
 * {@link AgentRunWakeListener} 保持不变，仅替换实现。</p>
 */
public record AgentDelegationWakeEvent(UUID parentRunId) {
}
