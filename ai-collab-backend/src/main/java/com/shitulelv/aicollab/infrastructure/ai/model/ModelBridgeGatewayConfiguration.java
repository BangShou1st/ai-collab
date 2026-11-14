package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.net.OutboundHttpClients;
import com.shitulelv.aicollab.common.security.LocalOutboundTargetPolicy;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.security.NetworkPolicyMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.http.HttpClient;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class ModelBridgeGatewayConfiguration {
    @Bean
    public io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy
            modelBridgeEndpointPolicy(
                    LocalOutboundTargetPolicy localTargets, Environment environment) {
        Set<LocalOutboundTargetPolicy.Target> targets = localTargets.targets();
        if (!environment.acceptsProfiles(Profiles.of("local", "test")) || targets.isEmpty()) {
            return new io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy();
        }
        Set<String> hosts = targets.stream()
                .map(LocalOutboundTargetPolicy.Target::host)
                .collect(Collectors.toUnmodifiableSet());
        Set<Integer> ports = targets.stream()
                .map(LocalOutboundTargetPolicy.Target::port)
                .collect(Collectors.toUnmodifiableSet());
        return new io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy(
                NetworkPolicyMode.ALLOW_LOOPBACK, true, hosts, ports);
    }

    @Bean
    public HttpTransport modelBridgeHttpTransport(
            ObjectMapper mapper,
            io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy policy) {
        HttpClient client = OutboundHttpClients.builder()
                .connectTimeout(ModelBridgeProviderAdapter.CONNECT_TIMEOUT)
                .build();
        return new HttpTransport(mapper, policy,
                ModelBridgeProviderAdapter.CONNECT_TIMEOUT,
                ModelBridgeProviderAdapter.REQUEST_TIMEOUT,
                client);
    }

    @Bean
    public ModelBridgeClient modelBridgeClient(ObjectMapper mapper, HttpTransport transport) {
        return new ModelBridgeClient(AdapterRegistry.withBuiltIns(mapper, transport));
    }
}
