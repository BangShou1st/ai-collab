package com.shitulelv.aicollab.common.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsEndpointAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.actuate.web.WebMvcEndpointManagementContextConfiguration;
import org.springframework.boot.webmvc.autoconfigure.actuate.endpoint.web.WebMvcHealthEndpointExtensionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可观测性 O1 暴露白名单契约：prometheus 端点仅在 exposure 白名单显式包含时可达
 * （对应 compose 层 MANAGEMENT_ENDPOINTS_EXPOSURE:health,info,prometheus），
 * 默认值 health,info 下端点 404、health 保持匿名可达。
 */
class PrometheusExposureContractTest {

    @Configuration(proxyBeanMethods = false)
    static class Config {
        @Bean org.springframework.web.servlet.DispatcherServlet dispatcherServlet() {
            return new org.springframework.web.servlet.DispatcherServlet();
        }

        @Bean org.springframework.boot.webmvc.autoconfigure.DispatcherServletPath servletPath() {
            return () -> "/";
        }

        @Bean tools.jackson.databind.ObjectMapper transportMapper() {
            return new tools.jackson.databind.ObjectMapper();
        }
    }

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Config.class)
                .withConfiguration(AutoConfigurations.of(
                        WebMvcAutoConfiguration.class,
                        EndpointAutoConfiguration.class,
                        WebEndpointAutoConfiguration.class,
                        ManagementContextAutoConfiguration.class,
                        MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class,
                        PrometheusMetricsExportAutoConfiguration.class,
                        MetricsEndpointAutoConfiguration.class,
                        HealthContributorRegistryAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class,
                        WebMvcHealthEndpointExtensionAutoConfiguration.class,
                        WebMvcEndpointManagementContextConfiguration.class));
    }

    @Test
    void prometheusEndpointIsServedWhenExplicitlyIncludedInExposure() throws Exception {
        runner().withPropertyValues("management.endpoints.web.exposure.include=health,info,prometheus")
                .run(context -> {
                    assertThat(context).hasSingleBean(io.micrometer.prometheusmetrics.PrometheusMeterRegistry.class);
                    // 写入一个确定性指标，抓取体必须能读到它（不依赖 Binder 自动装配的细节）
                    context.getBean(io.micrometer.prometheusmetrics.PrometheusMeterRegistry.class)
                            .counter("observability_contract_probe").increment();
                    var mvc = MockMvcBuilders.webAppContextSetup(context).build();
                    mvc.perform(get("/actuator/prometheus"))
                            .andExpect(status().isOk())
                            .andExpect(content().string(org.hamcrest.Matchers.containsString("observability_contract_probe")));
                });
    }

    @Test
    void prometheusEndpointIsAbsentUnderDefaultExposureWhileHealthStaysReachable() throws Exception {
        runner().withPropertyValues("management.endpoints.web.exposure.include=health,info")
                .run(context -> {
                    var mvc = MockMvcBuilders.webAppContextSetup(context).build();
                    mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
                    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
                });
    }
}
