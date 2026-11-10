package com.shitulelv.aicollab.planning.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 任务规划运行时开关（设计文档 §5.3）。
 * 前缀跟随 planning 模块现有惯例（{@code PlanningModelProperties} 用 "planning"）。
 */
@ConfigurationProperties("planning.runtime")
public record TaskPlanRuntimeProperties(
        /** REFLECT 反思阶段开关，默认开启；关闭时详情生成成功后直接 READY/READY_WITH_ISSUES。 */
        @DefaultValue("true") boolean reflectEnabled) {
}
