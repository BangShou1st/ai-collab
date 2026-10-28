package com.shitulelv.aicollab.agent.infrastructure.diagnostics;

import com.shitulelv.aicollab.common.security.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentActuatorSecurityTest {
    @Configuration(proxyBeanMethods=false)
    @EnableWebSecurity
    @Import({SecurityConfig.class,RestAuthenticationEntryPoint.class,RestAccessDeniedHandler.class,AuthRequestOriginValidator.class})
    static class Config {
        @Bean org.springframework.web.servlet.DispatcherServlet dispatcherServlet() { return new org.springframework.web.servlet.DispatcherServlet(); }
        @Bean org.springframework.boot.webmvc.autoconfigure.DispatcherServletPath servletPath() { return () -> "/"; }
        @Bean tools.jackson.databind.ObjectMapper transportMapper() { return new tools.jackson.databind.ObjectMapper(); }
        @Bean CorsProperties corsProperties() { return new CorsProperties(List.of("http://localhost:5173")); }
        @Bean MeterRegistry registry() { return new SimpleMeterRegistry(); }
        @Bean SecretKeySpec jwtKey() { byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);return new SecretKeySpec(key,"HmacSHA256"); }
        @Bean JwtDecoder decoder(SecretKeySpec key) { return NimbusJwtDecoder.withSecretKey(key).build(); }
    }
    WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner().withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer()).withUserConfiguration(Config.class).withConfiguration(AutoConfigurations.of(
            org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
            org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration.class,
            org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration.class,
            org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration.class,
            org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration.class,
            org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration.class,
            org.springframework.boot.webmvc.autoconfigure.actuate.endpoint.web.WebMvcHealthEndpointExtensionAutoConfiguration.class,
            org.springframework.boot.micrometer.metrics.autoconfigure.MetricsEndpointAutoConfiguration.class,
            org.springframework.boot.webmvc.autoconfigure.actuate.web.WebMvcEndpointManagementContextConfiguration.class));
    }
    @Test void c09DefaultHealthIsAnonymousAndMetricsAreNotExposed() {
        runner().run(context->{
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
            mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.details").doesNotExist()).andExpect(jsonPath("$.components").doesNotExist());
            mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
            mvc.perform(get("/actuator/metrics").header("Authorization","Bearer "+token(context.getBean(SecretKeySpec.class)))).andExpect(status().isNotFound());
        });
    }
    @Test void c09ExplicitMetricsExposureStillRequiresValidJwt() {
        runner().withPropertyValues("management.endpoints.web.exposure.include=health,metrics").run(context->{
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
            mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
            mvc.perform(get("/actuator/metrics").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
            mvc.perform(get("/actuator/metrics").header("Authorization","Bearer "+token(context.getBean(SecretKeySpec.class)))).andExpect(status().isOk()).andExpect(jsonPath("$.names").isArray());
        });
    }
    static String token(SecretKeySpec key) {
        var encoder=new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
        var claims=JwtClaimsSet.builder().subject("diagnostics-test").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
}
