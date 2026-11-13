package com.shitulelv.aicollab.infrastructure.ai;

import com.shitulelv.aicollab.infrastructure.ai.shared.ProviderAdmissionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ChatModelProperties.class, ProviderAdmissionProperties.class})
public class ChatModelConfiguration {
}
