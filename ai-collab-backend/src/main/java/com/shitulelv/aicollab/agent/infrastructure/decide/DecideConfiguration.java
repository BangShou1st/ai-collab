package com.shitulelv.aicollab.agent.infrastructure.decide;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

/**
 * Agent decide 配置装配。
 *
 * <p>{@link DecideMode} 的字符串转换走显式 {@link Converter}（与 {@code RerankConfiguration}
 * 同一做法）：{@link DecideMode#from(String)} 对未知取值与尚未实现的 {@code python}
 * 抛 {@link IllegalStateException}，让配置写错在<b>启动阶段</b>失败，而不是静默回退。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DecideProperties.class)
public class DecideConfiguration {

    @Bean
    @ConfigurationPropertiesBinding
    public Converter<String, DecideMode> decideModeConverter() {
        return DecideMode::from;
    }
}
