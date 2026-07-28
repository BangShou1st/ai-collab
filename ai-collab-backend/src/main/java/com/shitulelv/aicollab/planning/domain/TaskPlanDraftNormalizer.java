package com.shitulelv.aicollab.planning.domain;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Task 3: Deterministic safe draft normalization.
 *
 * Allowed operations:
 * - String trim on all text fields
 * - Deduplicate assumptions, risks, sourceRefs, dependencyTempKeys preserving first-occurrence order
 * - Null collections → List.of()
 * - Stable sort milestones by sortOrder then tempKey
 * - Stable sort tasks by sortOrder then tempKey
 * - Priority case normalization (LOW/MEDIUM/HIGH/URGENT)
 * - Blank strings → null only on nullable fields
 * - ISO date parsing and standard serialization (no date movement)
 *
 * FORBIDDEN operations:
 * - Fill default estimatedHours
 * - Fill default dates
 * - Generate assigneeId
 * - Generate sourceRefs
 * - Delete dependency conflicts
 * - Fix dependency cycles
 * - Modify title/objective business meaning
 */
@Component
public class TaskPlanDraftNormalizer {

    public TaskPlanDraft normalize(TaskPlanDraft draft) {
        if (draft == null) return null;

        String summary = trimToNull(draft.summary());
        List<String> assumptions = deduplicate(draft.assumptions());
        List<String> risks = deduplicate(draft.risks());

        List<PlanMilestone> milestones = draft.milestones().stream()
                .filter(m -> m != null)
                .map(this::normalizeMilestone)
                .sorted((a, b) -> {
                    int cmp = Integer.compare(a.sortOrder(), b.sortOrder());
                    return cmp != 0 ? cmp : a.tempKey().compareTo(b.tempKey());
                })
                .toList();

        List<PlanTask> tasks = draft.tasks().stream()
                .filter(t -> t != null)
                .map(this::normalizeTask)
                .sorted((a, b) -> {
                    int cmp = Integer.compare(a.sortOrder(), b.sortOrder());
                    return cmp != 0 ? cmp : a.tempKey().compareTo(b.tempKey());
                })
                .toList();

        List<PlanSource> sources = draft.sources().stream()
                .filter(s -> s != null)
                .map(this::normalizeSource)
                .toList();

        return new TaskPlanDraft(summary, assumptions, risks, milestones, tasks, sources);
    }

    private PlanMilestone normalizeMilestone(PlanMilestone m) {
        return new PlanMilestone(
                m.tempKey(),
                trimToNull(m.title()),
                trimToNull(m.objective()),
                trimToNull(m.description()),
                m.targetDate(),  // no date movement
                m.sortOrder(),
                deduplicate(m.sourceRefs()));
    }

    private PlanTask normalizeTask(PlanTask t) {
        return new PlanTask(
                t.tempKey(),
                t.milestoneTempKey(),
                trimToNull(t.title()),
                trimToNull(t.objective()),
                trimToNull(t.description()),
                normalizePriority(t.priority()),
                t.estimatedHours(),  // no default filling
                t.startDate(),       // no date movement
                t.dueDate(),         // no date movement
                t.suggestedAssigneeId(),  // no generation
                t.assigneeId(),           // no generation
                deduplicate(t.dependencyTempKeys()),
                deduplicate(t.sourceRefs()),
                t.sortOrder());
    }

    private PlanSource normalizeSource(PlanSource s) {
        return new PlanSource(
                s.ref(),
                s.documentId(),
                trimToNull(s.documentName()),
                s.chunkId(),
                trimToNull(s.heading()),
                s.similarity(),
                trimToNull(s.quoteText()),
                s.contentHash());
    }

    /**
     * Priority normalization: only accept valid values, case-insensitive input.
     * Returns null if input is null/blank, throws if invalid.
     */
    static String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) return null;
        String upper = priority.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "LOW", "MEDIUM", "HIGH", "URGENT" -> upper;
            default -> throw new IllegalArgumentException("Invalid priority: " + priority);
        };
    }

    /** Trim string, return null if blank. */
    static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    /** Deduplicate list preserving first-occurrence order. Null and blank entries are removed. */
    static List<String> deduplicate(List<String> list) {
        if (list == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        for (String item : list) {
            if (item == null) continue;
            String trimmed = item.trim();
            if (!trimmed.isBlank()) seen.add(trimmed);
        }
        return List.copyOf(seen);
    }
}
