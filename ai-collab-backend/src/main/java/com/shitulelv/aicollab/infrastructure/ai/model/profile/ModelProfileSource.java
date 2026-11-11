package com.shitulelv.aicollab.infrastructure.ai.model.profile;

/**
 * 模型档案的核实来源。
 *
 * <p>只有 {@link #UNVERIFIED} 之外的来源才可在运行时把窗口视为已确认；
 * 手工填写但未核实的值必须继续按估算处理，避免重演"配置即事实"。</p>
 */
public enum ModelProfileSource {
    UNVERIFIED,
    PROVIDER_DOCUMENT,
    ADMIN_VERIFIED;

    public boolean verified() {
        return this != UNVERIFIED;
    }
}
