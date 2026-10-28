package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;

/**
 * 能力探针专用执行边界：允许探针在“专门上下文”中测试尚未声明的能力。
 *
 * <p>历史问题：Provider 只声明 CHAT 时，原生工具探针在网关声明门控处短路，
 * 用户被错误引导为“模型不支持工具调用”——“尚未声明”被当成了“实际不支持”。
 * 本上下文让探针真正向 Provider 发送合成测试请求，再依据实际响应下结论。</p>
 *
 * <p>安全边界（与 {@link AiConfigurationContext} 同一信任模型）：</p>
 * <ul>
 *   <li>仅由 {@code AiCapabilityProbeService} 在探针调用期间设置，绝不来自
 *       模型输出、HTTP 请求或任何客户端参数；</li>
 *   <li>只对匹配的 Provider 快照生效（按 id 与 userId 双重校验），跨 Provider
 *       调用不享受豁免；</li>
 *   <li>仅豁免“能力声明门控”这一项：enabled 检查、凭据授权、出站端点安全、
 *       结构化输出校验、审批与 UNKNOWN 恢复逻辑全部保持不变；</li>
 *   <li>生产 Agent / 规划 / 问答路径永不设置本上下文，声明门控不受影响；
 *       探针也不执行任何真实业务工具（固定合成工具 capability_probe）。</li>
 * </ul>
 */
public final class CapabilityProbeContext implements AutoCloseable {
    private static final ThreadLocal<UserAiProvider> CURRENT = new ThreadLocal<>();
    private final UserAiProvider previous;

    public CapabilityProbeContext(UserAiProvider snapshot) {
        previous = CURRENT.get();
        CURRENT.set(snapshot);
    }

    /** 仅当当前探针上下文针对同一 Provider（同 id 同归属用户）时返回 true。 */
    public static boolean activeFor(UserAiProvider provider) {
        UserAiProvider current = CURRENT.get();
        return current != null && provider != null
                && current.id().equals(provider.id())
                && current.userId().equals(provider.userId());
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
