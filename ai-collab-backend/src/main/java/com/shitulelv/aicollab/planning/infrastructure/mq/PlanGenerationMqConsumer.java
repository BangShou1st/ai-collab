package com.shitulelv.aicollab.planning.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.application.TaskPlanGenerationOrchestrator;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.infrastructure.mq.RocketMqConsumerLifecycle;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 规划 dispatch 消息消费者（M2 §3.1 第 2 行，{@code planning.mq.enabled=true} 时装配）。
 *
 * <p>消费语义：按消息重建计划身份快照（generationSeq/activeAttemptId 取 dispatch 时刻
 * 值，见 {@link PlanGenerationMessage}），调用编排器的执行入口
 * {@code executeDispatch} —— 与进程内 {@code dispatchNow} 完全共用注册表、幂等与
 * 取消语义。</p>
 *
 * <p><b>幂等（attemptId 双保险）</b>：重复投递时，</p>
 * <ul>
 *   <li>运行中 —— 编排器运行注册表 {@code runRegistry.putIfAbsent(planId, generationSeq)}
 *       命中即丢弃；</li>
 *   <li>已收口（终态/已取消/已换代）—— {@code repository.active()} 的
 *       generationSeq+attemptId+状态校验失败，执行入口直接返回，模型不被调用。</li>
 * </ul>
 * 两层任意一层命中都无副作用，重复/乱序投递安全。</p>
 *
 * <p>ACK 策略：业务性丢弃（幂等命中、计划不存在/已删除）恒 ACK；基础设施异常
 * （读计划失败等）RECONSUME_LATER 重试，耗尽后转 DLQ 并由恢复 Job（300s 对账）
 * 按 PROCESS_RESTARTED 收口 —— dispatch 消息丢失不会让计划永久卡在生成中。</p>
 */
public class PlanGenerationMqConsumer {

    private static final Logger log = LoggerFactory.getLogger(PlanGenerationMqConsumer.class);

    private final PlanningMqProperties properties;
    private final PlanningMqMetrics metrics;
    private final ObjectMapper objectMapper;
    private final TaskPlanRepository repository;
    private final TaskPlanGenerationOrchestrator orchestrator;
    private final RocketMqConsumerLifecycle lifecycle;

    public PlanGenerationMqConsumer(PlanningMqProperties properties, PlanningMqMetrics metrics,
                                    ObjectMapper objectMapper, TaskPlanRepository repository,
                                    TaskPlanGenerationOrchestrator orchestrator) {
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.orchestrator = orchestrator;
        // 消费并发度与 planningTaskExecutor 对齐由编排器派发承接（注册表 + 执行器排队），
        // 消费线程本身只做短读 + 提交，1 线程足够
        this.lifecycle = new RocketMqConsumerLifecycle(
                properties.consumerGroup(), properties.namesrvAddr(), properties.topic(),
                1, properties.maxReconsumeTimes(), properties.reconnectInterval(),
                new DispatchListener(), "规划 dispatch");
    }

    @jakarta.annotation.PostConstruct
    void start() {
        lifecycle.start();
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        lifecycle.stop();
    }

    private class DispatchListener implements MessageListenerConcurrently {
        @Override
        public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages,
                                                        ConsumeConcurrentlyContext context) {
            var status = ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            for (MessageExt message : messages) {
                if (!handle(message)) {
                    status = ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return status;
        }
    }

    /** @return true = 已按业务语义处理（含幂等丢弃）；false = 基础设施异常，请求重投递。 */
    private boolean handle(MessageExt message) {
        PlanGenerationMessage payload;
        try {
            payload = objectMapper.readValue(message.getBody(), PlanGenerationMessage.class);
        } catch (Exception malformed) {
            // 畸形消息重投递也无法恢复（载荷本身坏了）：ACK 丢弃 + 告警指标
            metrics.consumed("dropped");
            log.warn("规划 dispatch 消息畸形，ACK 丢弃，msgId={}，type={}",
                    message.getMsgId(), malformed.getClass().getSimpleName());
            return true;
        }
        try {
            TaskPlanRecord current = repository.require(payload.projectId(), payload.planId());
            // 重建 dispatch 时刻的身份快照：幂等链（active()/markRunning）依赖
            // "快照身份 = dispatch 身份"，见 PlanGenerationMessage 类注释
            TaskPlanRecord snapshot = withDispatchIdentity(current, payload);
            metrics.consumed("ok");
            orchestrator.executeDispatch(snapshot, payload.actor(), payload.detailOnly());
            return true;
        } catch (com.shitulelv.aicollab.common.exception.BusinessException absent) {
            // 计划不存在/已删除：业务性丢弃（迟到的旧消息），重试无意义
            metrics.consumed("dropped");
            log.warn("规划 dispatch 消息对应的计划不存在（已删除或迟到旧消息），ACK 丢弃，planId={}",
                    payload.planId());
            return true;
        } catch (RuntimeException infrastructureFailure) {
            // 读计划等基础设施异常：重投递（耗尽后 DLQ + 恢复 Job 按 PROCESS_RESTARTED 收口）
            metrics.failed("dispatch-consume");
            log.warn("规划 dispatch 消息消费失败，请求重投递，planId={}，type={}",
                    payload.planId(), infrastructureFailure.getClass().getSimpleName());
            return false;
        }
    }

    /** 用消息携带的 dispatch 身份替换 DB 当前身份，得到与进程内 dispatch 等价的快照。 */
    private TaskPlanRecord withDispatchIdentity(TaskPlanRecord current, PlanGenerationMessage payload) {
        return new TaskPlanRecord(current.id(), current.projectId(), current.title(), current.goal(),
                current.constraints(), current.planStartDate(), current.planDueDate(),
                current.maxTaskCount(), current.selectedDocumentIdsJson(), current.status(),
                current.latestVersionNo(), current.latestVersionId(),
                payload.generationSeq(), payload.activeAttemptId(), current.createdBy(),
                current.lastErrorCode(), current.lastErrorSummary(),
                current.createdAt(), current.updatedAt());
    }
}
