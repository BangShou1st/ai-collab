package com.shitulelv.aicollab.infrastructure.mq;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RocketMQ 生产者通用生命周期管理（M2 从 document 模块的 M1 实现提升到公共层，
 * 供 document / agent / planning 各链路的 Messenger 复用；各模块自己的 topic、
 * 消息体与发送语义留在各自模块）。
 *
 * <p>客户端选型说明：Spring Boot 4.1 下不使用 {@code rocketmq-spring-boot-starter}
 * （其自动配置尚未适配 Boot 4，RocketMQTemplate 无法注册，见 apache/rocketmq-spring#761），
 * 沿用 M1 的原生客户端薄封装。</p>
 *
 * <p>懒启动：生产者 {@code start()} 失败后实例不可复用（内部状态机进入
 * START_FAILED），因此每次重试都重建实例；启动失败返回 null，由调用方决定回落路径
 * （消息丢失无害设计：状态行/兜底轮询对账）。本类不做发送，只管实例的取用与销毁。</p>
 *
 * <p>事务链路通过四参构造函数注入 {@link TransactionListener}，此时创建
 * {@link TransactionMQProducer}；普通链路仍创建 {@link DefaultMQProducer}。事务生产者
 * 同时继承普通发送能力，因此同一实例也可发送普通处理通知，避免同组多生产者实例。</p>
 */
public final class RocketMqProducerLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RocketMqProducerLifecycle.class);

    private final String producerGroup;
    private final String namesrvAddr;
    private final String logLabel;
    /** 非 null 表示事务生产者；每次重建实例都必须重新挂载该监听器。 */
    private final TransactionListener transactionListener;

    /** 懒启动的生产者实例；null 表示当前不可用（启动失败，等待下次发送时重试）。 */
    private volatile DefaultMQProducer producer;
    private final Object lock = new Object();

    public RocketMqProducerLifecycle(String producerGroup, String namesrvAddr, String logLabel) {
        this(producerGroup, namesrvAddr, logLabel, null);
    }

    public RocketMqProducerLifecycle(String producerGroup, String namesrvAddr, String logLabel,
                                     TransactionListener transactionListener) {
        this.producerGroup = producerGroup;
        this.namesrvAddr = namesrvAddr;
        this.logLabel = logLabel;
        this.transactionListener = transactionListener;
    }

    /** 取用已启动的生产者；不可用时返回 null（启动失败，调用方按发送失败处理）。 */
    public DefaultMQProducer acquire() {
        DefaultMQProducer active = this.producer;
        if (active != null) {
            return active;
        }
        synchronized (lock) {
            if (this.producer != null) {
                return this.producer;
            }
            DefaultMQProducer fresh = createProducer();
            try {
                fresh.start();
                this.producer = fresh;
                log.info("RocketMQ {} 生产者已启动，namesrv={}", logLabel, namesrvAddr);
                return fresh;
            } catch (Exception exception) {
                fresh.shutdown();
                log.warn("RocketMQ {} 生产者启动失败，暂时回落进程内路径，namesrv={}，type={}",
                        logLabel, namesrvAddr, exception.getClass().getSimpleName());
                return null;
            }
        }
    }

    /**
     * 按是否配置事务监听器创建生产者实例。包级可见仅用于单元测试验证实例类型，
     * 生产调用统一经 {@link #acquire()}。
     */
    DefaultMQProducer createProducer() {
        if (transactionListener != null) {
            TransactionMQProducer transactional = new TransactionMQProducer(producerGroup);
            transactional.setNamesrvAddr(namesrvAddr);
            transactional.setTransactionListener(transactionListener);
            return transactional;
        }
        DefaultMQProducer normal = new DefaultMQProducer(producerGroup);
        normal.setNamesrvAddr(namesrvAddr);
        return normal;
    }

    /** 销毁生产者（Spring 容器关闭时由配置类/宿主 Bean 调用）。 */
    public void shutdown() {
        DefaultMQProducer active = this.producer;
        this.producer = null;
        if (active != null) {
            active.shutdown();
        }
    }
}
