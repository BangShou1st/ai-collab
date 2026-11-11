package com.shitulelv.aicollab.agent.job;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * AI Job 状态机（W5 冻结 §3.2）——<b>纯函数</b>，不读库、不持时钟。
 *
 * <p>唯一权威跃迁表：</p>
 * <pre>
 * QUEUED           → LEASED, CANCELED, EXPIRED
 * LEASED           → RUNNING, FAILED_RETRYABLE, FAILED, CANCELED, EXPIRED
 * RUNNING          → SUCCEEDED, FAILED_RETRYABLE, FAILED, CANCELED, EXPIRED
 * FAILED_RETRYABLE → QUEUED, FAILED, CANCELED, EXPIRED
 * SUCCEEDED/FAILED/CANCELED/EXPIRED → ∅（终态）
 * </pre>
 *
 * <p>服务层在租约/心跳语义下允许“LEASED 直接收结果/失败”：先按本表补记
 * LEASED→RUNNING，再执行 RUNNING→终态，保证落库状态序列仍完全合法；不会出现
 * 跳跃式写入。</p>
 */
public final class AiJobStateMachine {

    private static final Map<AiJobStatus, Set<AiJobStatus>> ALLOWED =
            new EnumMap<>(AiJobStatus.class);

    static {
        ALLOWED.put(AiJobStatus.QUEUED,
                EnumSet.of(AiJobStatus.LEASED, AiJobStatus.CANCELED, AiJobStatus.EXPIRED));
        ALLOWED.put(AiJobStatus.LEASED,
                EnumSet.of(AiJobStatus.RUNNING, AiJobStatus.FAILED_RETRYABLE,
                        AiJobStatus.FAILED, AiJobStatus.CANCELED, AiJobStatus.EXPIRED));
        ALLOWED.put(AiJobStatus.RUNNING,
                EnumSet.of(AiJobStatus.SUCCEEDED, AiJobStatus.FAILED_RETRYABLE,
                        AiJobStatus.FAILED, AiJobStatus.CANCELED, AiJobStatus.EXPIRED));
        ALLOWED.put(AiJobStatus.FAILED_RETRYABLE,
                EnumSet.of(AiJobStatus.QUEUED, AiJobStatus.FAILED,
                        AiJobStatus.CANCELED, AiJobStatus.EXPIRED));
        ALLOWED.put(AiJobStatus.SUCCEEDED, EnumSet.noneOf(AiJobStatus.class));
        ALLOWED.put(AiJobStatus.FAILED, EnumSet.noneOf(AiJobStatus.class));
        ALLOWED.put(AiJobStatus.CANCELED, EnumSet.noneOf(AiJobStatus.class));
        ALLOWED.put(AiJobStatus.EXPIRED, EnumSet.noneOf(AiJobStatus.class));
    }

    private AiJobStateMachine() {
    }

    /** 是否允许 from → to。 */
    public static boolean canTransition(AiJobStatus from, AiJobStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.get(from).contains(to);
    }

    /** 允许的目标状态（只读快照）。 */
    public static Set<AiJobStatus> allowedTargets(AiJobStatus from) {
        if (from == null) {
            return Set.of();
        }
        return Set.copyOf(ALLOWED.get(from));
    }

    /**
     * 校验跃迁，非法时抛 {@link IllegalArgumentException}（带 from/to，不含业务内容）。
     */
    public static void requireTransition(AiJobStatus from, AiJobStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalArgumentException(
                    "非法 AI Job 状态跃迁: " + from + " → " + to);
        }
    }
}
