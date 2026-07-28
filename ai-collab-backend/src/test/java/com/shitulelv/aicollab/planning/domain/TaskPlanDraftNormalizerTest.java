package com.shitulelv.aicollab.planning.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 3: RED/GREEN tests for TaskPlanDraftNormalizer.
 *
 * Verifies that normalization is deterministic and safe:
 * - trims text without changing meaning
 * - deduplicates preserving order
 * - sorts stably
 * - does NOT invent assignees, sources, dates, or hours
 */
class TaskPlanDraftNormalizerTest {

    private final TaskPlanDraftNormalizer normalizer = new TaskPlanDraftNormalizer();

    @Test
    void trimsTextWithoutChangingMeaning() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "  summary  ", List.of("  assumption  "), List.of("  risk  "),
                List.of(new PlanMilestone("M1", "  title  ", "  objective  ", "  desc  ",
                        LocalDate.of(2026, 8, 15), 0, List.of())),
                List.of(new PlanTask("T1", "M1", "  title  ", "  objective  ", "  desc  ",
                        "medium", BigDecimal.TEN,
                        LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                        null, null, List.of(), List.of(), 0)),
                List.of());

        TaskPlanDraft result = normalizer.normalize(draft);

        assertEquals("summary", result.summary());
        assertEquals("assumption", result.assumptions().getFirst());
        assertEquals("risk", result.risks().getFirst());
        assertEquals("title", result.milestones().getFirst().title());
        assertEquals("objective", result.milestones().getFirst().objective());
        assertEquals("desc", result.milestones().getFirst().description());
        assertEquals("title", result.tasks().getFirst().title());
        assertEquals("objective", result.tasks().getFirst().objective());
        assertEquals("desc", result.tasks().getFirst().description());
    }

    @Test
    void deduplicatesDependenciesPreservingOrder() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", List.of(), List.of(),
                List.of(new PlanMilestone("M1", "M1", "obj", null, LocalDate.of(2026, 8, 15), 0, List.of())),
                List.of(new PlanTask("T1", "M1", "T1", "obj", "desc", "HIGH",
                        null, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                        null, null, List.of("T2", "T3", "T2"), List.of(), 0)),
                List.of());

        TaskPlanDraft result = normalizer.normalize(draft);

        assertEquals(List.of("T2", "T3"), result.tasks().getFirst().dependencyTempKeys());
    }

    @Test
    void deduplicatesSourcesPreservingOrder() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", List.of(), List.of(),
                List.of(new PlanMilestone("M1", "M1", "obj", null, LocalDate.of(2026, 8, 15), 0,
                        List.of("S1", "S2", "S1"))),
                List.of(new PlanTask("T1", "M1", "T1", "obj", "desc", "HIGH",
                        null, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                        null, null, List.of(), List.of("S1", "S2", "S2"), 0)),
                List.of());

        TaskPlanDraft result = normalizer.normalize(draft);

        assertEquals(List.of("S1", "S2"), result.milestones().getFirst().sourceRefs());
        assertEquals(List.of("S1", "S2"), result.tasks().getFirst().sourceRefs());
    }

    @Test
    void sortsStably() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", List.of(), List.of(),
                List.of(
                        new PlanMilestone("M2", "M2", "obj", null, null, 2, List.of()),
                        new PlanMilestone("M1", "M1", "obj", null, null, 1, List.of()),
                        new PlanMilestone("M3", "M3", "obj", null, null, 1, List.of())),  // same sortOrder as M1
                List.of(
                        new PlanTask("T2", "M1", "T2", "obj", "desc", "HIGH", null, null, null, null, null, List.of(), List.of(), 2),
                        new PlanTask("T1", "M1", "T1", "obj", "desc", "HIGH", null, null, null, null, null, List.of(), List.of(), 1)),
                List.of());

        TaskPlanDraft result = normalizer.normalize(draft);

        // M1 (sortOrder=1) before M3 (sortOrder=1, but tempKey M1 < M3)
        assertEquals("M1", result.milestones().get(0).tempKey());
        assertEquals("M3", result.milestones().get(1).tempKey());
        assertEquals("M2", result.milestones().get(2).tempKey());

        assertEquals("T1", result.tasks().get(0).tempKey());
        assertEquals("T2", result.tasks().get(1).tempKey());
    }

    @Test
    void doesNotMoveDates() {
        LocalDate start = LocalDate.of(2026, 8, 10);
        LocalDate due = LocalDate.of(2026, 8, 20);
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", List.of(), List.of(),
                List.of(new PlanMilestone("M1", "M1", "obj", null, LocalDate.of(2026, 8, 15), 0, List.of())),
                List.of(new PlanTask("T1", "M1", "T1", "obj", "desc", "HIGH",
                        null, start, due, null, null, List.of(), List.of(), 0)),
                List.of());

        TaskPlanDraft result = normalizer.normalize(draft);

        assertEquals(start, result.tasks().getFirst().startDate());
        assertEquals(due, result.tasks().getFirst().dueDate());
        assertEquals(LocalDate.of(2026, 8, 15), result.milestones().getFirst().targetDate());
    }

    @Test
    void doesNotInventAssignee() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", List.of(), List.of(),
                List.of(new PlanMilestone("M1", "M1", "obj", null, LocalDate.of(2026, 8, 15), 0, List.of())),
                List.of(new PlanTask("T1", "M1", "T1", "obj", "desc", "HIGH",
                        null, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                        null, null, List.of(), List.of(), 0)),
                List.of());

        TaskPlanDraft result = normalizer.normalize(draft);

        assertNull(result.tasks().getFirst().suggestedAssigneeId());
        assertNull(result.tasks().getFirst().assigneeId());
    }

    @Test
    void doesNotInventSources() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", List.of(), List.of(),
                List.of(new PlanMilestone("M1", "M1", "obj", null, LocalDate.of(2026, 8, 15), 0, List.of())),
                List.of(new PlanTask("T1", "M1", "T1", "obj", "desc", "HIGH",
                        null, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                        null, null, List.of(), List.of(), 0)),
                List.of());

        TaskPlanDraft result = normalizer.normalize(draft);

        assertTrue(result.tasks().getFirst().sourceRefs().isEmpty());
        assertTrue(result.milestones().getFirst().sourceRefs().isEmpty());
    }

    @Test
    void doesNotFillEstimatedHours() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", List.of(), List.of(),
                List.of(new PlanMilestone("M1", "M1", "obj", null, LocalDate.of(2026, 8, 15), 0, List.of())),
                List.of(new PlanTask("T1", "M1", "T1", "obj", "desc", "HIGH",
                        null, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                        null, null, List.of(), List.of(), 0)),
                List.of());

        TaskPlanDraft result = normalizer.normalize(draft);

        assertNull(result.tasks().getFirst().estimatedHours());
    }

    @Test
    void nullCollectionsBecomeEmptyLists() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", null, null, List.of(), List.of(), null);

        TaskPlanDraft result = normalizer.normalize(draft);

        assertNotNull(result.assumptions());
        assertNotNull(result.risks());
        assertNotNull(result.sources());
        assertTrue(result.assumptions().isEmpty());
        assertTrue(result.risks().isEmpty());
        assertTrue(result.sources().isEmpty());
    }

    @Test
    void blankStringBecomeNull() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "  ", List.of(), List.of(),
                List.of(new PlanMilestone("M1", " ", " ", "  ",
                        LocalDate.of(2026, 8, 15), 0, List.of())),
                List.of(new PlanTask("T1", "M1", "  ", "  ", "  ",
                        "HIGH", null, null, null, null, null, List.of(), List.of(), 0)),
                List.of());

        TaskPlanDraft result = normalizer.normalize(draft);

        assertNull(result.summary());
        assertNull(result.milestones().getFirst().title());
        assertNull(result.milestones().getFirst().objective());
        assertNull(result.milestones().getFirst().description());
        assertNull(result.tasks().getFirst().title());
        assertNull(result.tasks().getFirst().objective());
        assertNull(result.tasks().getFirst().description());
    }

    @Test
    void priorityIsNormalized() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", List.of(), List.of(),
                List.of(new PlanMilestone("M1", "M1", "obj", null, LocalDate.of(2026, 8, 15), 0, List.of())),
                List.of(new PlanTask("T1", "M1", "T1", "obj", "desc", "  high  ",
                        null, null, null, null, null, List.of(), List.of(), 0)),
                List.of());

        TaskPlanDraft result = normalizer.normalize(draft);

        assertEquals("HIGH", result.tasks().getFirst().priority());
    }

    @Test
    void invalidPriorityThrows() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", List.of(), List.of(),
                List.of(new PlanMilestone("M1", "M1", "obj", null, LocalDate.of(2026, 8, 15), 0, List.of())),
                List.of(new PlanTask("T1", "M1", "T1", "obj", "desc", "CRITICAL",
                        null, null, null, null, null, List.of(), List.of(), 0)),
                List.of());

        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(draft));
    }

    @Test
    void nullDraftReturnsNull() {
        assertNull(normalizer.normalize(null));
    }

    @Test
    void nullMilestonesAndTasksAreSkipped() {
        @SuppressWarnings("unchecked")
        List<PlanMilestone> milestones = java.util.Arrays.asList(
                new PlanMilestone("M1", "M1", "obj", null, null, 0, List.of()),
                null,
                new PlanMilestone("M2", "M2", "obj", null, null, 1, List.of()));
        @SuppressWarnings("unchecked")
        List<PlanTask> tasks = java.util.Arrays.asList(
                new PlanTask("T1", "M1", "T1", "obj", "desc", "HIGH", null, null, null, null, null, List.of(), List.of(), 0),
                null);

        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(), milestones, tasks, List.of());
        TaskPlanDraft result = normalizer.normalize(draft);

        assertEquals(2, result.milestones().size());
        assertEquals(1, result.tasks().size());
    }
}
