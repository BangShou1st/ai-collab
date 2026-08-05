package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.api.dto.AgentMemoryRequest;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentMemoryRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AgentMemoryService {
    private final AgentMemoryRepository repository;
    private final ProjectAccessGuard access;
    private final AuditService audit;
    public AgentMemoryService(AgentMemoryRepository repository, ProjectAccessGuard access) {
        this.repository = repository; this.access = access; this.audit = null;
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentMemoryService(AgentMemoryRepository repository, ProjectAccessGuard access, AuditService audit) {
        this.repository = repository; this.access = access; this.audit = audit;
    }

    public List<AgentMemoryView> list(UUID projectId, UUID userId, boolean activeOnly) {
        access.requireMember(projectId, userId); return repository.list(projectId, activeOnly, 100);
    }

    public List<AgentMemoryView> context(UUID projectId) { return repository.list(projectId, true, 10); }

    @Transactional public AgentMemoryView create(UUID projectId, UUID userId, AgentMemoryRequest request) {
        access.requireAdmin(projectId, userId);
        AgentMemoryView created = repository.create(projectId, userId, request.type(), request.title().strip(),
                request.content().strip(), request.sourceType().strip(), request.sourceId());
        audit(projectId, userId, "AGENT_MEMORY_CREATED", created.id(), created.type());
        return created;
    }

    @Transactional public AgentMemoryView update(UUID projectId, UUID id, UUID userId, AgentMemoryRequest request) {
        access.requireAdmin(projectId, userId); require(projectId, id);
        if (!repository.update(projectId, id, userId, request.version(), request.type(),
                request.title().strip(), request.content().strip(), request.sourceType().strip(), request.sourceId()))
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        audit(projectId, userId, "AGENT_MEMORY_UPDATED", id, request.type());
        return require(projectId, id);
    }

    @Transactional public AgentMemoryView disable(UUID projectId, UUID id, UUID userId, int version) {
        access.requireAdmin(projectId, userId); require(projectId, id);
        if (!repository.disable(projectId, id, userId, version)) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        audit(projectId, userId, "AGENT_MEMORY_DISABLED", id, "DISABLED");
        return require(projectId, id);
    }

    private AgentMemoryView require(UUID projectId, UUID id) {
        return repository.find(projectId, id).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_MEMORY_NOT_FOUND));
    }
    private void audit(UUID projectId, UUID userId, String action, UUID id, String type) {
        if (audit != null) audit.write(projectId, userId, action, "AGENT_MEMORY", id,
                java.util.Map.of("type", type));
    }
}
