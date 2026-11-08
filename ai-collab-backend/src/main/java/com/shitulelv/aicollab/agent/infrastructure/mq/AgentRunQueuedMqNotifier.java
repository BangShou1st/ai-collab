package com.shitulelv.aicollab.agent.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.runtime.AgentRunQueuedNotifier;
import com.shitulelv.aicollab.infrastructure.mq.RocketMqProducerLifecycle;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * RocketMQ 实现的 run 入队通知发送器（M2 §3.1 第 3 行，{@code agent.mq.enabled=true} 时装配）。
 *
 * <p>发送失败<b>无害</b>：run 的状态行已是 QUEUED，调度 tick 轮询兜底必然接管；
 * 这里只记 WARN 与 {@code agent.mq.sent{outcome=failed}} 指标，绝不向调用方抛出 ——
 * 通知是优化非正确性依赖，发送异常不得影响 run 创建/重试/恢复等主流程。</p>
 */
public class AgentRunQueuedMqNotifier implements AgentRunQueuedNotifier {

    private static final Logger log = LoggerFactory.getLogger(AgentRunQueuedMqNotifier.class);

    private final AgentMqProperties properties;
    private final AgentMqMetrics metrics;
    private final ObjectMapper objectMapper;
    private final RocketMqProducerLifecycle producerLifecycle;

    public AgentRunQueuedMqNotifier(AgentMqProperties properties, AgentMqMetrics metrics,
                                    ObjectMapper objectMapper) {
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.producerLifecycle = new RocketMqProducerLifecycle(
                properties.producerGroup(), properties.namesrvAddr(), "agent run 入队通知");
    }

    @Override
    public void notifyQueued(UUID runId, String source) {
        try {
            var active = producerLifecycle.acquire();
            if (active == null) {
                metrics.sent("failed");
                return;
            }
            AgentRunQueuedMessage payload = AgentRunQueuedMessage.parse(runId, source);
            Message message = new Message(properties.topic(),
                    runId.toString(),
                    objectMapper.writeValueAsBytes(payload));
            message.putUserProperty(AgentRunQueuedMessage.PROP_RUN_ID, runId.toString());
            message.putUserProperty(AgentRunQueuedMessage.PROP_SOURCE, payload.source());
            active.send(message);
            metrics.sent("ok");
            log.debug("run 入队通知已发送，runId={}，source={}", runId, payload.source());
        } catch (Exception exception) {
            // 消息丢失无害：状态行仍是 QUEUED，调度 tick（MQ 模式下降频后的兜底轮询）接管
            log.warn("run 入队通知发送失败，等待调度 tick 兜底，runId={}，source={}，type={}",
                    runId, source, exception.getClass().getSimpleName());
            metrics.sent("failed");
        }
    }

    /** 销毁生产者（Spring 容器关闭时由配置类调用）。 */
    public void shutdown() {
        producerLifecycle.shutdown();
    }
}
