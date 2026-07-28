package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.domain.*;
import com.shitulelv.aicollab.planning.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
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
        DraftDiff diff = diff(plan.projectId(), plan.id(), fromVersionId, draft);
        TaskPlanEventRecord event = new TaskPlanEventRecord(
                UUID.randomUUID(), plan.id(), fromVersionId, versionId,
                actorId, eventType, diff.changedFields(), diff.changedTargets(),
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
        return commitVersion(plan, draft, source, assessment, finalStatus, eventType,
                actorId, expectedBase, expectedBase);
    }

    @Transactional
    public TaskPlanVersionRecord commitVersion(
            TaskPlanRecord plan,
            TaskPlanDraft draft,
            TaskPlanVersionSource source,
            ValidationAssessment assessment,
            TaskPlanStatus finalStatus,
            String eventType,
            UUID actorId,
            UUID expectedBase,
            UUID basedOnVersionId) {

        // 1. Append version (interactive path — no attempt guard)
        UUID versionId = repository.appendVersion(
                plan.projectId(), plan.id(), expectedBase,
                source.name(), basedOnVersionId, draft, actorId,
                assessment != null ? assessment.toFlat() : null,
                finalStatus);
        if (versionId == null) return null;

        // 2. Replace issues for this version
        if (assessment != null && !assessment.issues().isEmpty()) {
            issueRepo.replaceForVersion(plan.id(), versionId, assessment.issues());
        }

        // 3. Write event
        DraftDiff diff = diff(plan.projectId(), plan.id(), expectedBase, draft);
        TaskPlanEventRecord event = new TaskPlanEventRecord(
                UUID.randomUUID(), plan.id(), expectedBase, versionId,
                actorId, eventType, diff.changedFields(), diff.changedTargets(),
                null, null,
                assessment != null ? assessment.codes() : List.of(),
                null);
        eventRepo.append(event);

        // 4. Return the created version
        return repository.requireVersion(plan.projectId(), plan.id(), versionId);
    }

    @Transactional
    public TaskPlanVersionRecord commitPartialRepair(
            TaskPlanRecord repairingPlan,
            TaskPlanDraft draft,
            ValidationAssessment assessment,
            TaskPlanStatus finalStatus,
            UUID actorId,
            UUID expectedBase,
            GenerationResult metrics) {
        TaskPlanVersionRecord version = commit(
                repairingPlan, draft, TaskPlanVersionSource.AI_PARTIAL_REPAIR,
                assessment, finalStatus, "PARTIAL_REPAIR", actorId, expectedBase);
        if (version != null) {
            repository.finishAttempt(repairingPlan.activeAttemptId(), "SUCCESS", null,
                    metrics.provider(), metrics.model(), metrics.latencyMs(),
                    metrics.promptTokens(), metrics.completionTokens(), null);
        }
        return version;
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

    private DraftDiff diff(UUID projectId, UUID planId, UUID fromVersionId, TaskPlanDraft after) {
        if (fromVersionId == null) return DraftDiff.empty();
        TaskPlanDraft before = repository.draft(
                repository.requireVersion(projectId, planId, fromVersionId));
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        changed(fields, "summary", before.summary(), after.summary());
        changed(fields, "assumptions", before.assumptions(), after.assumptions());
        changed(fields, "risks", before.risks(), after.risks());
        diffMilestones(before, after, fields, targets);
        diffTasks(before, after, fields, targets);
        changed(fields, "sources", before.sources(), after.sources());
        return new DraftDiff(List.copyOf(fields), List.copyOf(targets));
    }

    private static void diffMilestones(
            TaskPlanDraft before, TaskPlanDraft after,
            LinkedHashSet<String> fields, LinkedHashSet<String> targets) {
        Map<String, PlanMilestone> oldByKey = new LinkedHashMap<>();
        Map<String, PlanMilestone> newByKey = new LinkedHashMap<>();
        before.milestones().forEach(value -> oldByKey.put(value.tempKey(), value));
        after.milestones().forEach(value -> newByKey.put(value.tempKey(), value));
        LinkedHashSet<String> keys = new LinkedHashSet<>(oldByKey.keySet());
        keys.addAll(newByKey.keySet());
        for (String key : keys) {
            PlanMilestone oldValue = oldByKey.get(key);
            PlanMilestone newValue = newByKey.get(key);
            String prefix = "milestones." + key;
            if (oldValue == null || newValue == null) {
                fields.add(prefix);
                targets.add("MILESTONE:" + key);
                continue;
            }
            int beforeCount = fields.size();
            changed(fields, prefix + ".title", oldValue.title(), newValue.title());
            changed(fields, prefix + ".objective", oldValue.objective(), newValue.objective());
            changed(fields, prefix + ".description", oldValue.description(), newValue.description());
            changed(fields, prefix + ".targetDate", oldValue.targetDate(), newValue.targetDate());
            changed(fields, prefix + ".sortOrder", oldValue.sortOrder(), newValue.sortOrder());
            changed(fields, prefix + ".sourceRefs", oldValue.sourceRefs(), newValue.sourceRefs());
            if (fields.size() != beforeCount) targets.add("MILESTONE:" + key);
        }
    }

    private static void diffTasks(
            TaskPlanDraft before, TaskPlanDraft after,
            LinkedHashSet<String> fields, LinkedHashSet<String> targets) {
        Map<String, PlanTask> oldByKey = new LinkedHashMap<>();
        Map<String, PlanTask> newByKey = new LinkedHashMap<>();
        before.tasks().forEach(value -> oldByKey.put(value.tempKey(), value));
        after.tasks().forEach(value -> newByKey.put(value.tempKey(), value));
        LinkedHashSet<String> keys = new LinkedHashSet<>(oldByKey.keySet());
        keys.addAll(newByKey.keySet());
        for (String key : keys) {
            PlanTask oldValue = oldByKey.get(key);
            PlanTask newValue = newByKey.get(key);
            String prefix = "tasks." + key;
            if (oldValue == null || newValue == null) {
                fields.add(prefix);
                targets.add("TASK:" + key);
                continue;
            }
            int beforeCount = fields.size();
            changed(fields, prefix + ".milestoneTempKey", oldValue.milestoneTempKey(), newValue.milestoneTempKey());
            changed(fields, prefix + ".title", oldValue.title(), newValue.title());
            changed(fields, prefix + ".objective", oldValue.objective(), newValue.objective());
            changed(fields, prefix + ".description", oldValue.description(), newValue.description());
            changed(fields, prefix + ".priority", oldValue.priority(), newValue.priority());
            changed(fields, prefix + ".estimatedHours", oldValue.estimatedHours(), newValue.estimatedHours());
            changed(fields, prefix + ".startDate", oldValue.startDate(), newValue.startDate());
            changed(fields, prefix + ".dueDate", oldValue.dueDate(), newValue.dueDate());
            changed(fields, prefix + ".suggestedAssigneeId",
                    oldValue.suggestedAssigneeId(), newValue.suggestedAssigneeId());
            changed(fields, prefix + ".assigneeId", oldValue.assigneeId(), newValue.assigneeId());
            changed(fields, prefix + ".dependencyTempKeys",
                    oldValue.dependencyTempKeys(), newValue.dependencyTempKeys());
            changed(fields, prefix + ".sourceRefs", oldValue.sourceRefs(), newValue.sourceRefs());
            changed(fields, prefix + ".sortOrder", oldValue.sortOrder(), newValue.sortOrder());
            if (fields.size() != beforeCount) targets.add("TASK:" + key);
        }
    }

    private static void changed(LinkedHashSet<String> fields, String path, Object before, Object after) {
        if (!Objects.equals(before, after)) fields.add(path);
    }

    private record DraftDiff(List<String> changedFields, List<String> changedTargets) {
        static DraftDiff empty() {
            return new DraftDiff(List.of(), List.of());
        }
    }
}
