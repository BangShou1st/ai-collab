package com.sharedlibs.opencodezen.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Auto-configures shared OpenCode Zen request support when the starter is present. */
@AutoConfiguration
@EnableConfigurationProperties(OpenCodeZenProperties.class)
public class OpenCodeZenAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OpenCodeZenRequestFactory openCodeZenRequestFactory(OpenCodeZenProperties properties) {
        return new OpenCodeZenRequestFactory(properties.getUserAgent());
    }
}
