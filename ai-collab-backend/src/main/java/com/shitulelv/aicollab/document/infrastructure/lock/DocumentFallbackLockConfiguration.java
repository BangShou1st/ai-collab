package com.shitulelv.aicollab.document.infrastructure.lock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文档回落模式跨实例互斥锁的装配（Redis 深化 P2）。
 *
 * <p><b>条件装配边界（与 MQ 总闸严格隔离）</b>：仅当 {@code document.mq.enabled=false}
 * （回落模式，总闸缺省即 false）时装配。MQ 模式下文档链路消费幂等三重保障已覆盖
 * 跨实例互斥，再加锁属于重复机制，本配置类整体不生效 —— 两种模式互不掺和。</p>
 *
 * <p>客户端惰性建连（首次使用才发起 Redis 连接）：无 Redis 的测试环境该 Bean 存在但
 * 不会被触碰；Redis 不可用时实现内部降级放行（DB claim CAS 兜底），不阻断文档主流程。</p>
 */
@Configuration
@ConditionalOnProperty(name = "document.mq.enabled", havingValue = "false", matchIfMissing = true)
public class DocumentFallbackLockConfiguration {

    @Bean(destroyMethod = "shutdown")
    public RedissonDocumentDispatchLock redissonDocumentDispatchLock(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {
        return new RedissonDocumentDispatchLock(host, port, password, database);
    }
}
