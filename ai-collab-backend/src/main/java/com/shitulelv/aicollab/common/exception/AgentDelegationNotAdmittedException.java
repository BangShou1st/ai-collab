package com.shitulelv.aicollab.common.exception;

/**
 * <b>可预期的</b>委派受理拒绝：委派次数耗尽、剩余预算容不下子运行最小研究与收尾。
 *
 * <p>与权限不足、安全拒止、暂停、取消、租约失效和内部执行异常明确区分：只有本类型
 * 表示"这次委派在当前运行预算下不可能被受理"，调用方据此有边界地保留父运行
 * （记录拒绝并按真实调用身份消费模型轮次，而不是把整个运行判成失败）。
 * 其余 {@link BusinessException} 与运行时异常保持原失败边界，不因本类型被软化。</p>
 *
 * <p>原因以稳定错误码字符串表示（来源为
 * {@code AgentDelegationAdmission.RejectionReason.code()}），
 * 不依赖中文异常消息文本判断。</p>
 */
public class AgentDelegationNotAdmittedException extends BusinessException {

    private final String reasonCode;

    public AgentDelegationNotAdmittedException(String reasonCode, String message) {
        // HTTP/API 语义保持既有拒绝语义（不新增错误码枚举、不改变对外契约）
        super(ErrorCode.AGENT_TOOL_NOT_ALLOWED, message);
        this.reasonCode = reasonCode;
    }

    /** 稳定拒绝原因码（如 AGENT_DELEGATION_CHILDREN_EXHAUSTED），用于持久化与判定。 */
    public String reasonCode() {
        return reasonCode;
    }
}
