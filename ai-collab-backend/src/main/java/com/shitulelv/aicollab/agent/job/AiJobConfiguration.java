package com.shitulelv.aicollab.agent.job;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

/**
 * AI 任务装配（P1 契约 §4）。
 *
 * <p><b>Redisson 只用来拿锁</b>：{@code redisson-spring-boot-starter} 的自动配置在启动类上被排除，
 * 因为它会额外注册一个 {@code RedissonConnectionFactory}，与 Spring Boot 的 Lettuce
 * {@code RedisConnectionFactory} 冲突（两个同类型 Bean，所有按类型注入的地方都会失败）。这里只建一个
 * {@link RedissonClient}，地址、认证、库号、超时全部复用 {@code spring.data.redis.*}，不引入新的连接配置。
 * 只在 {@code memory.job.enabled=true} 时装配，并开启懒初始化：启动时不连 Redis。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiJobProperties.class)
public class AiJobConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    @ConditionalOnExpression("${memory.job.enabled:false} || ${knowledge.index.jobs-enabled:false}")
    public RedissonClient aiTaskRedissonClient(DataRedisProperties redis) {
        return Redisson.create(redissonConfig(redis));
    }

    /** 由 {@code spring.data.redis.*} 推出 Redisson 单机配置；只支持单机（与现有部署一致）。 */
    static Config redissonConfig(DataRedisProperties redis) {
        RedisEndpoint endpoint = endpoint(redis);
        Config config = new Config();
        config.setLazyInitialization(true);
        SingleServerConfig single = config.useSingleServer()
                .setAddress(endpoint.address())
                .setDatabase(endpoint.database())
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4);
        if (endpoint.user() != null && !endpoint.user().isBlank()) {
            single.setUsername(endpoint.user());
        }
        if (endpoint.authSecret() != null && !endpoint.authSecret().isBlank()) {
            single.setPassword(endpoint.authSecret());
        }
        Duration timeout = redis.getTimeout();
        if (timeout != null) {
            single.setTimeout((int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        }
        Duration connectTimeout = redis.getConnectTimeout();
        if (connectTimeout != null) {
            single.setConnectTimeout((int) Math.min(Integer.MAX_VALUE, connectTimeout.toMillis()));
        }
        return config;
    }

    /** {@code spring.data.redis.url} 优先，其次 host/port；URL 里的 userinfo 覆盖单独配置的认证。 */
    static RedisEndpoint endpoint(DataRedisProperties redis) {
        String user = redis.getUsername();
        String authSecret = redis.getPassword();
        int database = redis.getDatabase();
        boolean ssl = redis.getSsl() != null && redis.getSsl().isEnabled();
        String url = redis.getUrl();
        if (url == null || url.isBlank()) {
            String address = (ssl ? "rediss://" : "redis://") + redis.getHost() + ":" + redis.getPort();
            return new RedisEndpoint(address, database, user, authSecret);
        }
        URI uri = URI.create(url);
        ssl = ssl || "rediss".equalsIgnoreCase(uri.getScheme());
        String address = (ssl ? "rediss://" : "redis://") + uri.getHost() + ":"
                + (uri.getPort() > 0 ? uri.getPort() : 6379);
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int colon = userInfo.indexOf(':');
            if (colon < 0) {
                authSecret = userInfo;
            } else {
                if (colon > 0) {
                    user = userInfo.substring(0, colon);
                }
                authSecret = userInfo.substring(colon + 1);
            }
        }
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            database = Integer.parseInt(path.substring(1));
        }
        return new RedisEndpoint(address, database, user, authSecret);
    }

    /** 解析后的连接参数；{@link #toString()} 不含认证信息。 */
    record RedisEndpoint(String address, int database, String user, String authSecret) {
        @Override
        public String toString() {
            return "RedisEndpoint[address=" + address + ", database=" + database + "]";
        }
    }
}
