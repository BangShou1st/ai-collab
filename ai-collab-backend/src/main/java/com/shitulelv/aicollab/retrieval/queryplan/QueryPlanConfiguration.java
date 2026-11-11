package com.shitulelv.aicollab.retrieval.queryplan;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * QueryPlan 配置装配（W4 冻结契约 §4）。
 *
 * <p>本类只注册配置属性；本波<b>不</b>新增任何检索管线接线或调用方。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QueryPlanProperties.class)
public class QueryPlanConfiguration {
}
