package com.shitulelv.aicollab.agent.application.runtime;

import java.util.UUID;

/**
 * 子 Agent 委派终态的运行唤醒监听器接口（设计文档 §3.4）。
 *
 * <p>阶段 1 为进程内事件实现（{@code AgentImmediateWakeListener}）；
 * 阶段 2（MQ 方案落地后）仅替换实现，接口保持不变。</p>
 *
 * <p>契约：实现必须自行吞掉一切异常——唤醒是优化而非常确性依赖，
 * 调度 tick（默认 1s）是兜底路径，监听器失败不得影响子终态事务。</p>
 */
public interface AgentRunWakeListener {

    /**
     * 父运行的全部子运行已终态、父运行已重新排队：立即尝试一次 claim。
     *
     * @param parentRunId 重新排队等待领取的父运行 ID
     */
    void wake(UUID parentRunId);
}
