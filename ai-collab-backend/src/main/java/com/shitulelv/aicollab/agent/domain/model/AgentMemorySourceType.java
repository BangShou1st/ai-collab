package com.shitulelv.aicollab.agent.domain.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;

/**
 * agent_memory.source_type 允许来源集合(AC07-P1 M-09 来源治理)。
 * <p>兼容基线:生产库 agent_memory 存量为 0 行,允许集合由设计补充文档与既有写入方推导:
 * MANUAL(管理员手工)、USER_REQUEST(会话审批工具 create_memory_after_approval 落库值)、
 * DOCUMENT(项目文档,可通过权威服务核验归属与可访问性)。
 * 未知值在创建/更新时显式拒绝,不静默接受。</p>
 */
public enum AgentMemorySourceType {
    MANUAL,
    USER_REQUEST,
    DOCUMENT;

    /** 严格解析;未知类型拒绝并返回明确错误码,不静默接受。 */
    public static AgentMemorySourceType parse(String raw) {
        if (raw != null) {
            for (AgentMemorySourceType candidate : values()) {
                if (candidate.name().equals(raw)) return candidate;
            }
        }
        throw new BusinessException(ErrorCode.AGENT_MEMORY_SOURCE_TYPE_INVALID);
    }

    /** 来源能否由权威业务服务核验(存在、归属项目、请求人可访问)。不可核验来源允许保存但强制 UNVERIFIED。 */
    public boolean sourceVerifiable() {
        return this == DOCUMENT;
    }
}
