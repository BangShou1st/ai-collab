package com.shitulelv.aicollab.retrieval.rerank;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

/**
 * Rerank 配置装配。
 *
 * <p>{@link RerankMode} 的字符串转换刻意走显式 {@link Converter}（而不是依赖 Spring Boot
 * 宽松枚举转换的默认行为）：{@link RerankMode#from(String)} 对未知取值抛
 * {@link IllegalStateException}，从而让配置写错在<b>启动阶段</b>失败，而不是静默回退。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RerankProperties.class)
public class RerankConfiguration {

    @Bean
    @ConfigurationPropertiesBinding
    public Converter<String, RerankMode> rerankModeConverter() {
        return RerankMode::from;
    }
}
