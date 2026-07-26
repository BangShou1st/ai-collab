package com.shitulelv.aicollab.infrastructure.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatModelProperties.class)
public class ChatModelConfiguration {
}
