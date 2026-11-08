package com.shitulelv.aicollab.system.infrastructure.config;

import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import com.shitulelv.aicollab.system.application.service.SystemConfigSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 系统配置同步的 Redis Stream 消费装配（Redis 深化 P2：Pub/Sub 容器 → Stream 消费者组容器）。
 *
 * <p>有意<b>不使用 {@code @ConditionalOnBean(RedisConnectionFactory.class)}</b>：
 * 用户配置类的解析顺序早于自动配置类，此时 RedisConnectionFactory 的 Bean 定义尚未注册，
 * 该条件在普通 @Configuration 上会稳定假阴（当前 starter 是编译期硬依赖，工厂 Bean 定义
 * 必然存在），改为直接构造注入 —— Bean 定义在实例化阶段前全部就绪，依赖注入天然可靠。</p>
 */
@Configuration
public class SystemConfigRedisConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigRedisConfiguration.class);

    @Bean(destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> systemConfigStreamListenerContainer(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redisTemplate,
            SystemConfigSync configSync) {

        ensureConsumerGroup(redisTemplate, configSync.groupName());

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String,
                MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(3))
                        .batchSize(10)
                        // 连接失败等轮询异常只记录告警；限频避免 Redis 不可用的测试/本地环境刷日志
                        .errorHandler(pollErrorLogger())
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);
        container.receive(
                Consumer.from(configSync.groupName(), SystemConfigSync.CONSUMER_NAME),
                StreamOffset.create(SystemConfigService.CONFIG_SYNC_STREAM_KEY, ReadOffset.lastConsumed()),
                configSync);
        container.start();
        return container;
    }

    /**
     * 幂等建组：流不存在时先写入一条占位消息（version=-1，单调防护会安全忽略）再建组；
     * BUSYGROUP 表示组已存在，属正常并发幂等结果。建组失败（Redis 启动期不可用等）仅告警：
     * 消费组缺失时轮询持续报错（限频），配置正确性由周期对账兜底，不为此堆自愈机制。
     */
    private static void ensureConsumerGroup(StringRedisTemplate redisTemplate, String groupName) {
        try {
            redisTemplate.opsForStream()
                    .createGroup(SystemConfigService.CONFIG_SYNC_STREAM_KEY, ReadOffset.from("0"), groupName);
        } catch (RedisSystemException e) {
            String rootMessage = e.getRootCause() != null ? e.getRootCause().getMessage() : e.getMessage();
            if (rootMessage != null && rootMessage.contains("BUSYGROUP")) {
                return;
            }
            if (rootMessage != null && rootMessage.contains("requires the key to exist")) {
                try {
                    MapRecord<String, String, String> placeholder = StreamRecords
                            .newRecord()
                            .in(SystemConfigService.CONFIG_SYNC_STREAM_KEY)
                            .ofMap(Map.of("version", "-1"));
                    redisTemplate.opsForStream().add(placeholder);
                    redisTemplate.opsForStream()
                            .createGroup(SystemConfigService.CONFIG_SYNC_STREAM_KEY, ReadOffset.from("0"), groupName);
                    return;
                } catch (Exception retryError) {
                    log.warn("配置同步 Stream 建组（含占位消息）失败，由周期对账兜底: {}", retryError.getMessage());
                    return;
                }
            }
            log.warn("配置同步 Stream 建组失败，由周期对账兜底: {}", rootMessage);
        } catch (Exception e) {
            log.warn("配置同步 Stream 建组失败，由周期对账兜底: {}", e.getMessage());
        }
    }

    /** 轮询异常限频日志：首次告警 + 每 100 次告警一次，避免 Redis 长时间不可用时刷屏。 */
    private static org.springframework.util.ErrorHandler pollErrorLogger() {
        AtomicLong counter = new AtomicLong();
        return error -> {
            long n = counter.incrementAndGet();
            if (n == 1 || n % 100 == 0) {
                log.warn("配置同步 Stream 轮询异常（第 {} 次，Redis 不可用期间限频告警）: {}",
                        n, error.getMessage());
            }
        };
    }
}
