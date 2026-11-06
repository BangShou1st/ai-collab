package com.shitulelv.aicollab.retrieval.evidence;

public enum EvidenceCoverageStatus {
    /** 完整覆盖/全文已读 */
    FULL,
    /** 相关片段摘录/部分读取 */
    PARTIAL,
    /** 未知覆盖状态 */
    UNKNOWN,
    /** 尚未阅读/未覆盖 */
    NOT_READ
}
