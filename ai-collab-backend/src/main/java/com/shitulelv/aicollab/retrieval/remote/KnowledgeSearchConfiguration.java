package com.shitulelv.aicollab.retrieval.remote;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 Python 检索接口配置（P2 契约 §4.4）。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeSearchProperties.class)
public class KnowledgeSearchConfiguration {
}
