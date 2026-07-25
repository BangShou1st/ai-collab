package com.shitulelv.aicollab.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "auth.public-registration")
public record PublicRegistrationProperties(@DefaultValue("false") boolean enabled) {
}
