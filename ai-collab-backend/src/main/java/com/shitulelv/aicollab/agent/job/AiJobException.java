package com.shitulelv.aicollab.agent.job;

/**
 * AI Job 业务异常（不依赖 Web 层错误码）。
 *
 * <p>内部端点适配器把它映射成扁平错误信封；{@link Kind#STALE_LEASE} 必须映射为
 * HTTP 409 + {@code STALE_LEASE}，其余按 {@code INVALID_REQUEST}/404 处理。</p>
 */
public class AiJobException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        /** P1 claim 条件不满足（已被认领 / epoch 不符 / 状态不对）：映射 409 TASK_CLAIM_CONFLICT。 */
        CLAIM_CONFLICT,
        STALE_LEASE,
        INVALID_STATE,
        INVALID_REQUEST,
        /** P2 §4.2：INDEX_DOCUMENT 关联的正文不存在或不可读，映射 404 NOT_FOUND。 */
        BODY_NOT_FOUND,
        /** P2 §4.2：INDEX_DOCUMENT 提交时正文快照已变化，映射 409 INDEX_SNAPSHOT_STALE。 */
        INDEX_SNAPSHOT_STALE,
        /** P2 / P1：result payload 不符合契约结构，映射 422 SCHEMA_VIOLATION。 */
        SCHEMA_VIOLATION
    }

    private final Kind kind;

    public AiJobException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** 用于在 {@code @Transactional(noRollbackFor = ...)} 中保留任务 FAILED 状态的同时向 worker 返回 409。 */
    public static final class IndexSnapshotStaleException extends AiJobException {
        public IndexSnapshotStaleException(String message) {
            super(Kind.INDEX_SNAPSHOT_STALE, message);
        }
    }
}
