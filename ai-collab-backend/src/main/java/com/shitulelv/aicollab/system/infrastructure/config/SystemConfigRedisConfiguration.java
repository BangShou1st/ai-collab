package com.shitulelv.aicollab.system.infrastructure.config;

import com.shitulelv.aicollab.system.application.service.SystemConfigSync;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@ConditionalOnBean(RedisConnectionFactory.class)
public class SystemConfigRedisConfiguration {

    @Bean
    public RedisMessageListenerContainer systemConfigRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            SystemConfigSync configSync) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(configSync, new ChannelTopic("system:config:change"));
        return container;
    }
}
