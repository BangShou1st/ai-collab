package com.shitulelv.aicollab;

import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.redisson.spring.starter.RedissonAutoConfigurationV4;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 *
 * <p>排除 Redisson starter 的自动配置（P1 契约 §4.4）：它会注册 {@code RedissonConnectionFactory}、
 * 覆盖 {@code RedisTemplate/StringRedisTemplate}，与 Spring Boot 的 Lettuce 连接工厂冲突。
 * 补偿扫描需要的 {@code RedissonClient} 由 {@code AiJobConfiguration} 按 {@code spring.data.redis.*} 单独构建。</p>
 */
@SpringBootApplication(exclude = {RedissonAutoConfigurationV2.class, RedissonAutoConfigurationV4.class})
@EnableScheduling
public class AiCollabBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCollabBackendApplication.class, args);
    }
}
