package com.shitulelv.aicollab.system.application.view;

/**
 * 匿名可访问的系统状态摘要。
 *
 * <p>仅暴露维护状态、维护公告与公开注册策略，绝不返回配置版本、运行拓扑或内部指标。</p>
 */
public record PublicSystemStatusView(
        boolean maintenanceMode,
        String maintenanceMessage,
        boolean publicRegistrationEnabled) {
}
