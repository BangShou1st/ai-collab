package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.domain.*;
import com.shitulelv.aicollab.planning.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Task 7: Atomic commit service for version + issues + events.
 *
 * Single transaction:
 * 1. Insert version
 * 2. Replace issues
 * 3. Update plan (latestVersion, status, activeAttemptId, error)
 * 4. Write event
 * 5. Update attempt
 *
 * Any step failure rolls back everything.
 */
@Service
public class TaskPlanVersionCommitService {
    private final TaskPlanRepository repository;
    private final TaskPlanIssueRepository issueRepo;
    private final TaskPlanEventRepository eventRepo;

    public TaskPlanVersionCommitService(TaskPlanRepository repository,
                                         TaskPlanIssueRepository issueRepo,
                                         TaskPlanEventRepository eventRepo) {
        this.repository = repository;
        this.issueRepo = issueRepo;
        this.eventRepo = eventRepo;
    }

    /**
     * Atomic commit: version + issues + event + plan status update.
     */
    @Transactional
    public TaskPlanVersionRecord commit(
            TaskPlanRecord plan,
            TaskPlanDraft draft,
            TaskPlanVersionSource source,
            ValidationAssessment assessment,
            TaskPlanStatus finalStatus,
            String eventType,
            UUID actorId,
            UUID fromVersionId) {

        // 1. Append version
        UUID versionId = repository.appendGeneratedVersion(
                plan.projectId(), plan.id(), plan.generationSeq(),
                plan.activeAttemptId(), plan.status(),
                source.name(), fromVersionId, draft, actorId,
                assessment.toFlat(), finalStatus);
        if (versionId == null) return null;

        // 2. Replace issues for this version
        if (assessment != null && !assessment.issues().isEmpty()) {
            issueRepo.replaceForVersion(plan.id(), versionId, assessment.issues());
        }

        // 3. Write event
        TaskPlanEventRecord event = new TaskPlanEventRecord(
                UUID.randomUUID(), plan.id(), fromVersionId, versionId,
                actorId, eventType, List.of(),
                null, null,
                assessment.codes(),
                null);
        eventRepo.append(event);

        // 4. Return the created version
        return repository.requireVersion(plan.projectId(), plan.id(), versionId);
    }

    /**
     * Atomic commit for interactive operations (edit, restore, partial repair).
     * Unlike {@link #commit}, this does NOT require an active generation attempt.
     * Uses repository.appendVersion() directly with optimistic locking via expectedBase.
     */
    @Transactional
    public TaskPlanVersionRecord commitVersion(
            TaskPlanRecord plan,
            TaskPlanDraft draft,
            TaskPlanVersionSource source,
            ValidationAssessment assessment,
            TaskPlanStatus finalStatus,
            String eventType,
            UUID actorId,
            UUID expectedBase) {

        // 1. Append version (interactive path — no attempt guard)
        UUID versionId = repository.appendVersion(
                plan.projectId(), plan.id(), expectedBase,
                source.name(), expectedBase, draft, actorId,
                assessment != null ? assessment.toFlat() : null,
                finalStatus);
        if (versionId == null) return null;

        // 2. Replace issues for this version
        if (assessment != null && !assessment.issues().isEmpty()) {
            issueRepo.replaceForVersion(plan.id(), versionId, assessment.issues());
        }

        // 3. Write event
        TaskPlanEventRecord event = new TaskPlanEventRecord(
                UUID.randomUUID(), plan.id(), expectedBase, versionId,
                actorId, eventType, List.of(),
                null, null,
                assessment != null ? assessment.codes() : List.of(),
                null);
        eventRepo.append(event);

        // 4. Return the created version
        return repository.requireVersion(plan.projectId(), plan.id(), versionId);
    }

    /**
     * Append issues for an already-saved version (e.g., after re-validation).
     */
    @Transactional
    public void appendIssues(UUID planId, UUID versionId, List<StructuredValidationIssue> issues) {
        issueRepo.replaceForVersion(planId, versionId, issues);
    }

    /**
     * Find all issues for a version.
     */
    public List<StructuredValidationIssue> findByVersion(UUID planId, UUID versionId) {
        return issueRepo.findByVersion(planId, versionId);
    }

    /**
     * Count unresolved blocking issues.
     */
    public int countUnresolvedBlocking(UUID planId, UUID versionId) {
        return issueRepo.countUnresolvedBlocking(planId, versionId);
    }

    /**
     * Mark issues as resolved.
     */
    @Transactional
    public void resolveIssues(UUID planId, UUID versionId, List<UUID> issueIds, UUID actorId) {
        issueRepo.markResolved(planId, versionId, issueIds, actorId);
    }
}
