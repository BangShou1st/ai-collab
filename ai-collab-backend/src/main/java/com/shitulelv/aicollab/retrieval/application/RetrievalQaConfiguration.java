package com.shitulelv.aicollab.retrieval.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 检索问答配置装配：属性记录不参与组件扫描，由配置绑定创建。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetrievalQaProperties.class)
public class RetrievalQaConfiguration {
}
