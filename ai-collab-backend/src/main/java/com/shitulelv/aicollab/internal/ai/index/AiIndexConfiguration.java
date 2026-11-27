package com.shitulelv.aicollab.internal.ai.index;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 Python 索引管理与 PRUNE 配置（P2 契约 §4.6、§12.6）。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiIndexProperties.class)
public class AiIndexConfiguration {
}
