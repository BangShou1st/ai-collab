package com.shitulelv.aicollab.infrastructure.mq;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * RocketMQ Push 消费者通用生命周期管理（M2 从 document 模块的 M1 实现提升到公共层，
 * 供 document / agent / planning 各链路的消费者复用）。
 *
 * <p>懒启动 + 周期性重连：broker 尚未就绪（compose 内 broker 慢启动、网络抖动）时
 * 宿主应用仍可正常启动，由内部单线程定时器按 {@code reconnectInterval} 反复尝试接入；
 * 每次尝试失败即丢弃实例（DefaultMQPushConsumer 内部状态机 START_FAILED 后不可复用），
 * 下次重建。重连定时器随 {@link #stop()} 释放，不依赖 Spring 调度器。</p>
 *
 * <p>消费失败期间消息不会丢：消费不到的消息停在 broker，业务侧既有状态行 + 兜底
 * 轮询对账 —— 与"消息丢失无害"总设计一致。</p>
 */
public final class RocketMqConsumerLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RocketMqConsumerLifecycle.class);

    private final String consumerGroup;
    private final String namesrvAddr;
    private final String topic;
    private final int consumeThreads;
    private final int maxReconsumeTimes;
    private final Duration reconnectInterval;
    private final MessageListenerConcurrently listener;
    private final String logLabel;

    /** 运行中的消费者实例；null 表示当前不可用（等待重连）。 */
    private volatile DefaultMQPushConsumer consumer;
    private final Object lock = new Object();
    private volatile ScheduledExecutorService reconnectTimer;

    public RocketMqConsumerLifecycle(String consumerGroup, String namesrvAddr, String topic,
                                     int consumeThreads, int maxReconsumeTimes,
                                     Duration reconnectInterval,
                                     MessageListenerConcurrently listener, String logLabel) {
        this.consumerGroup = consumerGroup;
        this.namesrvAddr = namesrvAddr;
        this.topic = topic;
        this.consumeThreads = consumeThreads;
        this.maxReconsumeTimes = maxReconsumeTimes;
        this.reconnectInterval = reconnectInterval == null || reconnectInterval.isNegative()
                || reconnectInterval.isZero() ? Duration.ofSeconds(60) : reconnectInterval;
        this.listener = listener;
        this.logLabel = logLabel;
    }

    /** 尝试接入；已在运行则空操作。 */
    public void start() {
        tryStart();
        if (reconnectTimer == null) {
            synchronized (lock) {
                if (reconnectTimer == null) {
                    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread thread = new Thread(r, "rocketmq-" + logLabel + "-reconnect");
                        thread.setDaemon(true);
                        return thread;
                    });
                    timer.scheduleWithFixedDelay(this::startIfDown,
                            reconnectInterval.toMillis(), reconnectInterval.toMillis(), TimeUnit.MILLISECONDS);
                    reconnectTimer = timer;
                }
            }
        }
    }

    /** broker 恢复后自动接入；已在运行则空操作。 */
    public void startIfDown() {
        if (consumer == null) {
            tryStart();
        }
    }

    /** 停止消费者并释放重连定时器。 */
    public void stop() {
        ScheduledExecutorService timer = this.reconnectTimer;
        this.reconnectTimer = null;
        if (timer != null) {
            timer.shutdownNow();
        }
        DefaultMQPushConsumer active = this.consumer;
        this.consumer = null;
        if (active != null) {
            active.shutdown();
        }
    }

    private void tryStart() {
        synchronized (lock) {
            if (this.consumer != null) {
                return;
            }
            DefaultMQPushConsumer fresh = new DefaultMQPushConsumer(consumerGroup);
            fresh.setNamesrvAddr(namesrvAddr);
            try {
                fresh.setConsumeThreadMin(consumeThreads);
                fresh.setConsumeThreadMax(consumeThreads);
                // 批次固定 1：单条消息的 ACK / RECONSUME 语义一一对应，幂等判定不跨消息
                fresh.setConsumeMessageBatchMaxSize(1);
                // 重试耗尽（达到 maxReconsumeTimes）后客户端自动转投 %DLQ%<group>（需预建）
                fresh.setMaxReconsumeTimes(maxReconsumeTimes);
                fresh.registerMessageListener(listener);
                fresh.subscribe(topic, "*");
                fresh.start();
                this.consumer = fresh;
                log.info("RocketMQ {} 消费者已启动，topic={}，group={}，namesrv={}",
                        logLabel, topic, consumerGroup, namesrvAddr);
            } catch (Exception exception) {
                fresh.shutdown();
                log.warn("RocketMQ {} 消费者启动失败，等待周期重连，topic={}，namesrv={}，type={}",
                        logLabel, topic, namesrvAddr, exception.getClass().getSimpleName());
            }
        }
    }
}
