package com.shitulelv.aicollab.internal.ai.job;

import java.util.Map;
import java.util.UUID;

/**
 * Job token claims 的只读解析（W5 冻结 §3.3）。
 *
 * <p>claims 只用于 <b>lease 绑定</b>：{@code jobId/attempt/leaseEpoch} 必须与路径/请求一致，
 * 不一致即 403。它不是业务权限——Python 由此获得的唯一能力是访问这一个 Job 的有界快照/
 * 心跳/结果端点；真正的状态机、重试、落库仍在 Java。</p>
 */
public record AiJobTokenClaims(UUID jobId, Integer attempt, Long leaseEpoch) {

    /** 从 request attribute 的 claims Map 解析；缺失/非法字段保持 null。 */
    public static AiJobTokenClaims from(Map<String, Object> claims) {
        if (claims == null || claims.isEmpty()) {
            return null;
        }
        return new AiJobTokenClaims(uuid(claims.get("jobId")), integer(claims.get("attempt")),
                longValue(claims.get("leaseEpoch")));
    }

    /** 路径 jobId 是否绑定（snapshot 端点只需这一条 + attempt/epoch 非空）。 */
    public boolean boundToPath(UUID pathJobId) {
        return jobId != null && jobId.equals(pathJobId);
    }

    /** 路径 + 请求体三段是否全部一致。 */
    public boolean boundTo(UUID pathJobId, Integer requestAttempt, Long requestLeaseEpoch) {
        return boundToPath(pathJobId)
                && attempt != null && requestAttempt != null && attempt.equals(requestAttempt)
                && leaseEpoch != null && requestLeaseEpoch != null
                && leaseEpoch.equals(requestLeaseEpoch);
    }

    public boolean complete() {
        return jobId != null && attempt != null && leaseEpoch != null;
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
