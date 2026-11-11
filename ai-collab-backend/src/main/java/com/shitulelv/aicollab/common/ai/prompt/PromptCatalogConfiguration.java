package com.shitulelv.aicollab.common.ai.prompt;

import com.shitulelv.aicollab.common.net.OutboundHttpClients;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * PromptCatalog 装配：{@code prompt.registry.enabled} 决定唯一的 {@link PromptCatalog} bean。
 *
 * <ul>
 *   <li>{@code false}（默认）：{@link BundledPromptCatalog}，只读 classpath fallback，
 *       <b>不构造 RestClient、不发起任何外呼</b>；</li>
 *   <li>{@code true}：{@link RemotePromptCatalog}，访问内部 ai-worker，启动预取 + 周期刷新。</li>
 * </ul>
 *
 * <p>HTTP 超时取自配置，与 {@code OpenAiCompatibleEmbeddingGateway} 使用同一套
 * JDK HttpClient + {@link OutboundHttpClients} 写法；连接/读取超时在 RestClient 构造时固定，
 * 不在每次请求上重复设置。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PromptRegistryProperties.class)
public class PromptCatalogConfiguration {

    @Bean
    public PromptCatalog promptCatalog(PromptRegistryProperties properties,
                                       ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable(() -> Metrics.globalRegistry);
        if (!properties.enabled()) {
            return new BundledPromptCatalog(BundledPromptCatalog.class.getClassLoader(), meterRegistry);
        }
        return new RemotePromptCatalog(properties, buildRestClient(properties), meterRegistry);
    }

    private static RestClient buildRestClient(PromptRegistryProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                OutboundHttpClients.builder()
                        .connectTimeout(properties.connectTimeout())
                        .build());
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }
}
