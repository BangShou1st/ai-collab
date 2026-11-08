package com.shitulelv.aicollab.agent.application.runtime;

import java.util.UUID;

/**
 * Agent run "进入 QUEUED" 的通知端口（M2 §3.1 第 3 行双轨制的 MQ 侧）。
 *
 * <p>生产侧由仓储层在各"进入 QUEUED"落点调用（{@link #notifyQueuedAfterCommit}）：
 * 实现方（MQ 装配时）在事务提交后发送入队通知消息；未装配 MQ（总闸关闭，实现为 null）
 * 时调用方直接跳过 —— 通知是优化非正确性依赖，调度 tick 轮询兜底永不删。</p>
 *
 * <p>消费侧语义见 {@code AgentRunQueuedMqConsumer}：收到消息触发一次 claim 尝试，
 * 与 P1 进程内事件唤醒（{@code AgentImmediateWakeListener}）走同一条 claim 提交路径；
 * 区别在于进程内事件只有发布实例自己收到，MQ 消息每个实例都会消费到 ——
 * 多实例下任一实例空闲即可承接，{@code FOR UPDATE SKIP LOCKED} 保证只有一个实例抢到。</p>
 */
public interface AgentRunQueuedNotifier {

    /** 发送入队通知；实现必须自行吞掉发送失败（仅指标观测，不外溢）。 */
    void notifyQueued(UUID runId, String source);

    /**
     * 事务提交后发送入队通知：事务活跃时注册 afterCommit 同步（项目惯例，与
     * {@code AgentEventService} / {@code publishDelegationWake} 同款），无事务时
     * 直接发送（测试路径）。
     */
    default void notifyQueuedAfterCommit(UUID runId, String source) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()
                && org.springframework.transaction.support.TransactionSynchronizationManager
                        .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    notifyQueued(runId, source);
                                }
                            });
        } else {
            notifyQueued(runId, source);
        }
    }
}
