package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.domain.model.AgentMemorySourceType;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentMemorySourceRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 记忆来源校验最小实现(设计文档 MemoryEvidenceResolver 的 P1 子集,M-09)。
 * <p>sourceId 提供且来源类型可核验(DOCUMENT)时,必须通过权威服务核验:存在、归属同一项目、
 * 请求人可访问;跨项目/不存在/无权访问一律拒绝(AGENT_MEMORY_SOURCE_INVALID)。
 * 不可核验来源(MANUAL/USER_REQUEST)允许保存但强制 UNVERIFIED,绝不伪造 VERIFIED——
 * 该保证由创建路径恒置 UNVERIFIED、仅管理员可显式转 VERIFIED 实现。</p>
 */
@Service
public class AgentMemorySourceVerifier {
    private final AgentMemorySourceRepository sources;
    public AgentMemorySourceVerifier(AgentMemorySourceRepository sources) { this.sources = sources; }

    /** 校验失败抛 BusinessException;通过则静默返回。 */
    public void verify(UUID projectId, String sourceType, UUID sourceId) {
        AgentMemorySourceType type = AgentMemorySourceType.parse(sourceType);
        if (sourceId == null || !type.sourceVerifiable()) return;
        if (!sources.documentReadableInProject(projectId, sourceId))
            throw new BusinessException(ErrorCode.AGENT_MEMORY_SOURCE_INVALID);
    }
}
