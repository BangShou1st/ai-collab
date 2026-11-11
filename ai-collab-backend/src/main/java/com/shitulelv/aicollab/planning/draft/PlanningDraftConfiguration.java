package com.shitulelv.aicollab.planning.draft;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

/**
 * Planning draft shadow 配置装配。
 *
 * <p>{@link PlanningDraftMode} 的字符串转换走显式 {@link Converter}（与
 * {@code DecideConfiguration} / {@code RerankConfiguration} 同一做法）：
 * {@link PlanningDraftMode#from(String)} 对未知取值与尚未实现的 {@code python}
 * 抛 {@link IllegalStateException}，让配置写错在<b>启动阶段</b>失败。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlanningDraftProperties.class)
public class PlanningDraftConfiguration {

    @Bean
    @ConfigurationPropertiesBinding
    public Converter<String, PlanningDraftMode> planningDraftModeConverter() {
        return PlanningDraftMode::from;
    }
}
