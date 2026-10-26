package com.shitulelv.aicollab.infrastructure.ai.model;

import com.sharedlibs.kilogateway.KiloGatewayClient;
import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayAutoConfiguration;
import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 共享库 Starter 必须在当前 Spring Boot 版本下提供客户端 Bean，否则预设无法注入。 */
class KiloGatewayStarterWiringTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ModelTransportConfiguration.class)
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
            .withConfiguration(AutoConfigurations.of(KiloGatewayAutoConfiguration.class));

    @Test void applicationSuppliesScopedTransportWithoutChangingLibraryDefaults() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(KiloGatewayClient.class);
            var http = (java.net.http.HttpClient) org.springframework.test.util.ReflectionTestUtils
                    .getField(context.getBean(KiloGatewayClient.class), "http");
            assertThat(http).isNotNull();
            assertThat(http.proxy().orElseThrow()).isInstanceOf(com.shitulelv.aicollab.common.net.EnvironmentProxySelector.class);
            assertThat(http.followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
            KiloGatewayProperties properties = context.getBean(KiloGatewayProperties.class);
            assertThat(properties.getBaseUrl()).isEqualTo("https://ai-gateway.kilo.ai/api/v1");
            assertThat(properties.isFreeOnly()).isTrue();
            // 默认匿名：没有凭证就不会发送 Authorization。
            assertThat(properties.getApiKey()).isEmpty();
            // 默认不配置备用模型：不会用切换模型绕过 5xx/限流。
            assertThat(properties.getFallbackModels()).isEmpty();
            // 库不新增专用代理默认值；实际选路由应用的系统/JVM/进程策略决定。
            assertThat(properties.getProxyHost()).isEmpty();
        });
    }

    @Test void disablingTheLibraryRemovesTheClient() {
        runner.withPropertyValues("kilo.gateway.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(KiloGatewayClient.class));
    }

    @Test void disablingTheLibraryDegradesToClearBusinessErrorInsteadOfStartupFailure() {
        // 部署把共享库关掉时，本项目的 Kilo 组件仍应可启动；只在真正使用预设时报明确错误。
        @SuppressWarnings("unchecked")
        ObjectProvider<KiloGatewayClient> absent = mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);
        var transport = new KiloGatewayClientTransport(absent);
        assertThatThrownBy(transport::admittedModels)
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    @Test void registryKiloBaseMatchesLibraryDefault() {
        assertThat(ProviderPresetRegistry.KILO_GATEWAY_BASE)
                .isEqualTo(new KiloGatewayProperties().getBaseUrl());
    }
}
