package com.shitulelv.aicollab.planning.application.service;

import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;

import java.util.UUID;

/**
 * 规划生成 dispatch 的 MQ 消息门面（应用层端口；实现位于
 * {@code planning/infrastructure/mq}，与文档链路 {@code DocumentPipelineMessenger} 同构）。
 *
 * <p>M2 迁移语义（§3.1 第 2 行）：编排器 dispatch 在事务提交后改发 dispatch 消息，
 * 消费侧调用编排器的执行入口（与进程内路径共用）；发送失败回落进程内
 * {@code dispatchNow}（M1 文档链路同款"消息丢失无害"模式）。</p>
 *
 * <p>取消语义说明：进程内 FutureTask 运行注册表保留（本实例内取消需要立即中断），
 * 跨实例取消经既有 DB 状态机在执行循环的阶段边界生效
 * （{@code repository.active()} / {@code markRunning()} 校验失败即 DISCARDED）。</p>
 */
public interface PlanGenerationMessenger {

    /** MQ 总闸是否开启。开启时调用方在事务提交后发 dispatch 消息，关闭时走进程内派发。 */
    boolean enabled();

    /**
     * 发送规划 dispatch 消息（调用方保证在事务提交后调用）。
     *
     * @return true  = 消息已发送，等待消费者触发执行入口；
     *         false = 发送失败（broker 不可用等），调用方应回落进程内派发路径。
     */
    boolean trySendGeneration(TaskPlanRecord plan, UUID actor, boolean detailOnly);
}
