package com.shitulelv.aicollab.planning.infrastructure.mq;

import com.shitulelv.aicollab.planning.application.service.PlanGenerationMessenger;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;

import java.util.UUID;

/**
 * MQ 总闸关闭（{@code planning.mq.enabled=false}）时的回落实现：
 * {@link #enabled()} 恒为 false，调用方据此前提走进程内派发路径；发送方法
 * 在该语义下不应被调用，被调用即抛出（fail-fast 暴露装配错误）。
 */
public class DisabledPlanGenerationMessenger implements PlanGenerationMessenger {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public boolean trySendGeneration(TaskPlanRecord plan, UUID actor, boolean detailOnly) {
        throw new IllegalStateException("规划链路 MQ 总闸未开启，不应调用 dispatch 消息发送");
    }
}
