package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TaskPlanQueryService {
    private final ProjectAccessGuard access;
    private final TaskPlanRepository repository;
    public TaskPlanQueryService(ProjectAccessGuard access, TaskPlanRepository repository) {
        this.access = access; this.repository = repository;
    }
    public List<TaskPlanRecord> list(UUID projectId, String status, int page, int size, UUID actor) {
        access.requireMember(projectId, actor);
        int safeSize = Math.max(1, Math.min(size, 100));
        return repository.list(projectId, status, safeSize, Math.max(0, page) * safeSize);
    }
    public Map<String, Object> detail(UUID projectId, UUID planId, UUID actor) {
        ProjectRole role = access.requireMember(projectId, actor);
        TaskPlanRecord plan = repository.require(projectId, planId);
        boolean write = role.isAdminOrOwner();
        boolean ready = plan.status().name().equals("READY");
        return Map.of("plan", plan, "permissions", Map.of(
                "canEdit", write && ready, "canCancel", write && plan.status().isGenerating(),
                "canRetryDetail", write && plan.status().name().equals("DETAIL_GENERATION_FAILED"),
                "canRegenerate", write && List.of("READY","FAILED","DETAIL_GENERATION_FAILED","CANCELED").contains(plan.status().name()),
                "canConfirm", write && ready, "canDelete", write && List.of("READY","FAILED","DETAIL_GENERATION_FAILED","CANCELED").contains(plan.status().name()),
                "canRestore", write && ready));
    }
    public List<TaskPlanVersionRecord> versions(UUID projectId, UUID planId, UUID actor) {
        access.requireMember(projectId, actor); return repository.versions(projectId, planId);
    }
    public Map<String, Object> version(UUID projectId, UUID planId, UUID versionId, UUID actor) {
        access.requireMember(projectId, actor);
        TaskPlanVersionRecord version = repository.requireVersion(projectId, planId, versionId);
        return Map.of("version", version, "draft", repository.draft(version));
    }
}
