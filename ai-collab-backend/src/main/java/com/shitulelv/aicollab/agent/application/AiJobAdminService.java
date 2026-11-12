package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.application.view.AiJobView;
import com.shitulelv.aicollab.agent.job.AiJobProperties;
import com.shitulelv.aicollab.agent.job.AiJobRecord;
import com.shitulelv.aicollab.agent.job.AiJobRepository;
import com.shitulelv.aicollab.agent.job.AiJobService;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Memory Job 的项目管理员入口；默认开关关闭时不接受新 Job。 */
@Service
public class AiJobAdminService {

    private final AiJobService jobs;
    private final AiJobRepository repository;
    private final AiJobProperties properties;
    private final ProjectAccessGuard access;

    public AiJobAdminService(AiJobService jobs, AiJobRepository repository,
            AiJobProperties properties, ProjectAccessGuard access) {
        this.jobs = jobs;
        this.repository = repository;
        this.properties = properties;
        this.access = access;
    }

    /** 管理员显式创建一次 Memory 提取 Job；实际派发由定时队列扫描完成。 */
    public AiJobView create(UUID projectId, UUID userId) {
        access.requireAdmin(projectId, userId);
        if (!properties.enabled()) {
            throw new BusinessException(ErrorCode.AGENT_MEMORY_JOB_DISABLED);
        }
        return AiJobView.from(jobs.create(projectId, "{}", userId));
    }

    /** 项目管理员查看本项目 Job 状态；跨项目统一返回 NOT_FOUND。 */
    public AiJobView get(UUID projectId, UUID jobId, UUID userId) {
        access.requireAdmin(projectId, userId);
        AiJobRecord job = repository.find(jobId)
                .filter(candidate -> projectId.equals(candidate.projectId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_MEMORY_JOB_NOT_FOUND));
        return AiJobView.from(job);
    }
}
