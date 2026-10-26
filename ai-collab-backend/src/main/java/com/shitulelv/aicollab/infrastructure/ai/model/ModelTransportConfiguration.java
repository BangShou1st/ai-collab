package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedlibs.kilogateway.KiloGatewayClient;
import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties;
import com.shitulelv.aicollab.common.net.EnvironmentProxySelector;
import com.shitulelv.aicollab.common.net.OutboundHttpClients;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.ProxySelector;
import java.util.HashMap;

/** Supply the shared transport to the library rather than implement a second HTTP stack. */
@Configuration
@EnableConfigurationProperties(KiloGatewayProperties.class)
public class ModelTransportConfiguration {
    @Bean
    @ConditionalOnMissingBean(KiloGatewayClient.class)
    @ConditionalOnProperty(prefix = "kilo.gateway", name = "enabled", havingValue = "true", matchIfMissing = true)
    KiloGatewayClient kiloGatewayClient(KiloGatewayProperties properties, ObjectMapper mapper) {
        return configuredKiloClient(properties, mapper);
    }

    static KiloGatewayClient configuredKiloClient(KiloGatewayProperties properties, ObjectMapper mapper) {
        var environment = new HashMap<>(System.getenv());
        // Retain legacy Kilo-specific configuration as a last explicit fallback; never guess a port.
        if (properties.getProxyHost() != null && !properties.getProxyHost().isBlank()) {
            String legacy = "http://" + properties.getProxyHost() + ":" + properties.getProxyPort();
            environment.putIfAbsent("HTTPS_PROXY", environment.getOrDefault("https_proxy", legacy));
            environment.putIfAbsent("HTTP_PROXY", environment.getOrDefault("http_proxy", legacy));
        }
        var selector = new EnvironmentProxySelector(ProxySelector.getDefault(), System::getProperty, environment);
        return new KiloGatewayClient(properties, mapper, OutboundHttpClients.builder().proxy(selector)
                .connectTimeout(properties.getConnectTimeout()).build());
    }
}
