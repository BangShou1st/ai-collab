package com.shitulelv.aicollab.agent.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentRecoveryJob;
import com.shitulelv.aicollab.agent.application.AgentWorker;
import com.shitulelv.aicollab.infrastructure.mq.RocketMqConsumerLifecycle;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Agent run 入队通知消费者（M2 §3.1 第 3 行，{@code agent.mq.enabled=true} 时装配）。
 *
 * <p>收到消息触发一次 claim 尝试，与 P1 进程内唤醒（{@code AgentImmediateWakeListener}）
 * 走同一条 claim 提交路径（{@code recovery.claim} + {@code worker.process}，
 * {@code agentRunTaskExecutor} 承载）。claim 的 {@code FOR UPDATE SKIP LOCKED} 保证
 * 多实例下只有一个实例抢到，其余空手而归 —— 这正是双轨的价值：任一实例空闲即可承接。</p>
 *
 * <p><b>消费失败不重试（恒 ACK 丢弃）</b>，理由：消息是触发器而非载荷 —— 消费异常时
 * 重投递只是再触发一次 claim 尝试，没有额外信息增益；run 的状态行仍是 QUEUED，
 * 降频后的调度 tick 轮询兜底必然接管。畸形消息（坏 JSON）同样 ACK 丢弃。</p>
 *
 * <p><b>消息无序/重复无碍</b>：claim 是幂等竞争（数据库状态 + 租约 + claim_version CAS），
 * 重复触发最多多一次空 claim，无副作用。</p>
 */
public class AgentRunQueuedMqConsumer {

    private static final Logger log = LoggerFactory.getLogger(AgentRunQueuedMqConsumer.class);
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(6);

    private final AgentMqMetrics metrics;
    private final ObjectMapper objectMapper;
    private final AgentRecoveryJob recovery;
    private final AgentWorker worker;
    private final TaskExecutor agentRunTaskExecutor;
    private final String workerId = "mq-wake-" + UUID.randomUUID();
    private final RocketMqConsumerLifecycle lifecycle;

    public AgentRunQueuedMqConsumer(AgentMqProperties properties, AgentMqMetrics metrics,
                                    ObjectMapper objectMapper, AgentRecoveryJob recovery,
                                    AgentWorker worker,
                                    @Qualifier("agentRunTaskExecutor") TaskExecutor agentRunTaskExecutor) {
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.recovery = recovery;
        this.worker = worker;
        this.agentRunTaskExecutor = agentRunTaskExecutor;
        // 消费并发度 1：claim 是廉价短事务，单线程足够；多实例水平扩展靠实例数而非消费线程
        this.lifecycle = new RocketMqConsumerLifecycle(
                properties.consumerGroup(), properties.namesrvAddr(), properties.topic(),
                1, 1, properties.reconnectInterval(), new TriggerListener(), "agent run 入队通知");
    }

    @jakarta.annotation.PostConstruct
    void start() {
        lifecycle.start();
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        lifecycle.stop();
    }

    private class TriggerListener implements MessageListenerConcurrently {
        @Override
        public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages,
                                                        ConsumeConcurrentlyContext context) {
            // 批次固定 1（公共生命周期类设置），此处逐条防御性处理
            for (MessageExt message : messages) {
                handle(message);
            }
            // 恒 ACK：触发器语义，失败重试无增益（见类注释）
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
    }

    private void handle(MessageExt message) {
        UUID runId;
        String source;
        try {
            AgentRunQueuedMessage payload = objectMapper.readValue(message.getBody(),
                    AgentRunQueuedMessage.class);
            runId = payload.runId();
            source = payload.source();
        } catch (Exception malformed) {
            // 畸形消息 ACK 丢弃：触发器无状态行依赖，轮询兜底
            metrics.consumed("dropped");
            log.warn("run 入队通知消息畸形，ACK 丢弃，msgId={}，type={}",
                    message.getMsgId(), malformed.getClass().getSimpleName());
            return;
        }
        metrics.consumed("ok");
        log.debug("run 入队通知触发 claim 尝试，runId={}，source={}", runId, source);
        try {
            agentRunTaskExecutor.execute(() -> {
                try {
                    // 一次 claim 尝试：领到待执行运行即处理，领不到（被其他实例/tick 抢先
                    // 或无可领运行）直接结束 —— 空手而归是常态，不是错误。
                    recovery.claim(workerId, CLAIM_LEASE).ifPresent(worker::process);
                } catch (RuntimeException failure) {
                    // 触发失败只记录类型：状态行兜底轮询接管，绝不外溢到消费线程
                    log.warn("MQ 触发的 claim 尝试失败，等待调度 tick 兜底，type={}",
                            failure.getClass().getSimpleName());
                }
            });
        } catch (TaskRejectedException rejected) {
            // 执行器满载：等待中的运行保持 QUEUED，由后续 tick / 其他实例承接
            log.debug("MQ 触发被拒绝（执行器满载），由调度 tick 兜底");
        } catch (RuntimeException failure) {
            log.warn("MQ 触发提交失败，等待调度 tick 兜底，type={}", failure.getClass().getSimpleName());
        }
    }
}
