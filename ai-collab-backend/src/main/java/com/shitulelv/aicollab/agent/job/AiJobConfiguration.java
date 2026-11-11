package com.shitulelv.aicollab.agent.job;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Memory Job 配置装配（默认关闭；不引入调度器/第二运行时）。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiJobProperties.class)
public class AiJobConfiguration {
}
