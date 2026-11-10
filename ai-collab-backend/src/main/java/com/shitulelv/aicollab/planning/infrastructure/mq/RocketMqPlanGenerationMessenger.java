package com.shitulelv.aicollab.planning.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.infrastructure.mq.RocketMqProducerLifecycle;
import com.shitulelv.aicollab.planning.application.service.PlanGenerationMessenger;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * RocketMQ 实现的规划 dispatch 消息门面（M2 §3.1 第 2 行，
 * {@code planning.mq.enabled=true} 时装配）。
 *
 * <p>发送失败<b>无害</b>：返回 false 由调用方回落进程内派发（M1 文档链路同款
 * "消息丢失无害"模式），恢复 Job 对账兜底 —— 绝不向调用方抛出。</p>
 */
public class RocketMqPlanGenerationMessenger implements PlanGenerationMessenger {

    private static final Logger log = LoggerFactory.getLogger(RocketMqPlanGenerationMessenger.class);

    private final PlanningMqProperties properties;
    private final PlanningMqMetrics metrics;
    private final ObjectMapper objectMapper;
    private final RocketMqProducerLifecycle producerLifecycle;

    public RocketMqPlanGenerationMessenger(PlanningMqProperties properties,
                                           PlanningMqMetrics metrics, ObjectMapper objectMapper) {
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.producerLifecycle = new RocketMqProducerLifecycle(
                properties.producerGroup(), properties.namesrvAddr(), "规划 dispatch");
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean trySendGeneration(TaskPlanRecord plan, UUID actor, boolean detailOnly) {
        try {
            var active = producerLifecycle.acquire();
            if (active == null) {
                metrics.sent("failed");
                return false;
            }
            PlanGenerationMessage payload = new PlanGenerationMessage(
                    plan.projectId(), plan.id(), actor, detailOnly,
                    plan.generationSeq(), plan.activeAttemptId());
            Message message = new Message(properties.topic(),
                    plan.id().toString(),
                    objectMapper.writeValueAsBytes(payload));
            message.putUserProperty(PlanGenerationMessage.PROP_PLAN_ID, plan.id().toString());
            message.putUserProperty(PlanGenerationMessage.PROP_DETAIL_ONLY, Boolean.toString(detailOnly));
            active.send(message);
            metrics.sent("ok");
            log.debug("规划 dispatch 消息已发送，planId={}，generationSeq={}，detailOnly={}",
                    plan.id(), plan.generationSeq(), detailOnly);
            return true;
        } catch (Exception exception) {
            // 消息丢失无害：计划状态行已落库，发送失败回落进程内派发；恢复 Job 对账兜底
            log.warn("规划 dispatch 消息发送失败，回落进程内派发（恢复 Job 兜底），planId={}，type={}",
                    plan.id(), exception.getClass().getSimpleName());
            metrics.sent("failed");
            return false;
        }
    }

    /** 销毁生产者（Spring 容器关闭时由配置类调用）。 */
    public void shutdown() {
        producerLifecycle.shutdown();
    }
}
