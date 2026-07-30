package com.shitulelv.aicollab.planning.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.application.TaskPlanRepairPatchParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 5: RED/GREEN tests for TaskPlanRepairPatch, PatchValue, RepairScope, and Applier.
 */
class TaskPlanRepairPatchTest {

    private final com.shitulelv.aicollab.planning.application.TaskPlanRepairPatchApplier applier =
            new com.shitulelv.aicollab.planning.application.TaskPlanRepairPatchApplier();
    private final TaskPlanRepairPatchParser parser =
            new TaskPlanRepairPatchParser(new ObjectMapper().findAndRegisterModules());

    private TaskPlanDraft baseDraft() {
        PlanMilestone m1 = new PlanMilestone("M1", "Milestone 1", "obj", null,
                LocalDate.of(2026, 8, 15), 0, List.of());
        PlanTask t1 = new PlanTask("T1", "M1", "Task 1", "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                null, null, List.of(), List.of(), 0);
        PlanTask t2 = new PlanTask("T2", "M1", "Task 2", "obj", "desc", "HIGH",
                BigDecimal.valueOf(5), LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 5),
                null, null, List.of("T1"), List.of(), 1);
        return new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(m1), List.of(t1, t2), List.of());
    }

    private RepairScope dateConflictScope() {
        return new RepairScope(
                Set.of("T2"),
                Map.of("T2", Set.of("startDate", "dueDate")),
                RepairScope.ALWAYS_LOCKED);
    }

    private Set<UUID> validMembers() {
        return Set.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    }

    private Set<String> validSources() {
        return Set.of("S1", "S2", "S3");
    }

    @Test
    void repairPatchRejectsWrongFieldTypes() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                        {"milestonePatches":[],"taskPatches":[{"tempKey":"T1","dependencyTempKeys":"T1"}]}
                        """)),
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                        {"milestonePatches":[{"tempKey":"M1","sourceRefs":[1]}],"taskPatches":[]}
                        """)),
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                        {"milestonePatches":[],"taskPatches":[{"tempKey":"T1","suggestedAssigneeId":1}]}
                        """)),
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                        {"milestonePatches":[],"taskPatches":[{"tempKey":"T1","startDate":20260801}]}
                        """)),
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                        {"milestonePatches":[],"taskPatches":[{"tempKey":"T1","estimatedHours":"8"}]}
                        """)),
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                        {"milestonePatches":[],"taskPatches":[{"tempKey":1,"description":true}]}
                        """)));
    }

    @Test
    void repairPatchRejectsUnknownProperties() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                        {"milestonePatches":[],"taskPatches":[],"unexpected":true}
                        """)),
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                        {"milestonePatches":[],"taskPatches":[{"tempKey":"T1","unexpected":true}]}
                        """)),
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                        {"milestonePatches":[{"tempKey":"M1","unexpected":true}],"taskPatches":[]}
                        """)));
    }

    @Test
    void repairPatchRejectsMissingRequiredPatchArrays() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                        {"taskPatches":[]}
                        """)),
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                        {"milestonePatches":[]}
                        """)),
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                        {"milestonePatches":null,"taskPatches":[]}
                        """)),
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                        {"milestonePatches":[],"taskPatches":{}}
                        """)));
    }

    @Test
    void repairPatchRejectsNonObjectRoot() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("[]")),
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("null")),
                () -> assertThrows(IllegalArgumentException.class, () -> parser.parse("\"patch\"")));
    }

    // ── PatchValue tests ──

    @Test
    void absentPatchFieldDoesNotClearValue() {
        PatchValue<String> absent = PatchValue.absent();
        assertFalse(absent.present());
        assertNull(absent.value());
        assertEquals("default", absent.orElse("default"));
    }

    @Test
    void explicitNullClearsNullableField() {
        PatchValue<String> explicitNull = PatchValue.of(null);
        assertTrue(explicitNull.present());
        assertNull(explicitNull.value());
        assertTrue(explicitNull.isNull());
        assertNull(explicitNull.orElse("default"));
    }

    @Test
    void patchValueOfPreservesValue() {
        PatchValue<String> val = PatchValue.of("hello");
        assertTrue(val.present());
        assertEquals("hello", val.value());
        assertFalse(val.isNull());
        assertEquals("hello", val.orElse("default"));
    }

    // ── Applier: basic patch ──

    @Test
    void repairPatchChangesOnlyAllowedDateFields() {
        TaskPlanRepairPatch patch = new TaskPlanRepairPatch(
                List.of(),
                List.of(new TaskPlanRepairPatch.TaskPatch(
                        "T2",
                        PatchValue.absent(),  // description: don't touch
                        PatchValue.absent(),  // priority: don't touch
                        PatchValue.absent(),  // estimatedHours: don't touch
                        PatchValue.of(LocalDate.of(2026, 8, 22)),  // startDate: change
                        PatchValue.of(LocalDate.of(2026, 9, 10)),  // dueDate: change
                        PatchValue.absent(),  // suggestedAssigneeId: don't touch
                        PatchValue.absent(),  // dependencyTempKeys: don't touch
                        PatchValue.absent())));  // sourceRefs: don't touch

        TaskPlanDraft result = applier.apply(baseDraft(), patch, dateConflictScope(), validMembers(), validSources());

        PlanTask patched = result.tasks().stream().filter(t -> t.tempKey().equals("T2")).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 8, 22), patched.startDate());
        assertEquals(LocalDate.of(2026, 9, 10), patched.dueDate());
        // Unpatched fields remain unchanged
        assertEquals("desc", patched.description());
        assertEquals("HIGH", patched.priority());
        assertEquals(BigDecimal.valueOf(5), patched.estimatedHours());
        assertEquals(List.of("T1"), patched.dependencyTempKeys());
    }

    // ── Applier: locked field rejection ──

    @Test
    void repairPatchCannotModifyTitle() {
        TaskPlanRepairPatch patch = new TaskPlanRepairPatch(
                List.of(),
                List.of(new TaskPlanRepairPatch.TaskPatch(
                        "T2",
                        PatchValue.of("new title"),  // title is locked via description
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.absent())));

        // description is NOT locked in dateConflictScope, but let's test with a scope that locks it
        RepairScope lockedScope = new RepairScope(
                Set.of("T2"),
                Map.of("T2", Set.of("startDate")),  // only startDate allowed
                RepairScope.ALWAYS_LOCKED);

        assertThrows(Exception.class,
                () -> applier.apply(baseDraft(), patch, lockedScope, validMembers(), validSources()));
    }

    @Test
    void repairPatchCannotChangeTempKey() {
        // tempKey is always locked — cannot be changed even if patch tries
        TaskPlanRepairPatch patch = new TaskPlanRepairPatch(
                List.of(),
                List.of(new TaskPlanRepairPatch.TaskPatch(
                        "T2",
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.of(LocalDate.of(2026, 8, 22)),
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.absent())));

        // T2 is not in targetTempKeys — should fail
        RepairScope noTarget = new RepairScope(
                Set.of(), Map.of(), RepairScope.ALWAYS_LOCKED);

        assertThrows(Exception.class,
                () -> applier.apply(baseDraft(), patch, noTarget, validMembers(), validSources()));
    }

    // ── Applier: member validation ──

    @Test
    void repairPatchCannotAddUnknownMember() {
        UUID unknown = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        TaskPlanRepairPatch patch = new TaskPlanRepairPatch(
                List.of(),
                List.of(new TaskPlanRepairPatch.TaskPatch(
                        "T2",
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.absent(), PatchValue.absent(),
                        PatchValue.of(unknown),  // unknown member
                        PatchValue.absent(), PatchValue.absent())));

        assertThrows(Exception.class,
                () -> applier.apply(baseDraft(), patch, dateConflictScope(), validMembers(), validSources()));
    }

    // ── Applier: source validation ──

    @Test
    void repairPatchCannotAddUnknownSource() {
        TaskPlanRepairPatch patch = new TaskPlanRepairPatch(
                List.of(),
                List.of(new TaskPlanRepairPatch.TaskPatch(
                        "T2",
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.absent(),
                        PatchValue.of(List.of("S99")))));  // unknown source

        assertThrows(Exception.class,
                () -> applier.apply(baseDraft(), patch, dateConflictScope(), validMembers(), validSources()));
    }

    // ── Applier: duplicate patch target ──

    @Test
    void duplicatePatchTargetRejected() {
        TaskPlanRepairPatch patch = new TaskPlanRepairPatch(
                List.of(),
                List.of(
                        new TaskPlanRepairPatch.TaskPatch("T2", PatchValue.absent(), PatchValue.absent(),
                                PatchValue.absent(), PatchValue.of(LocalDate.of(2026, 8, 22)),
                                PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                                PatchValue.absent()),
                        new TaskPlanRepairPatch.TaskPatch("T2", PatchValue.absent(), PatchValue.absent(),
                                PatchValue.absent(), PatchValue.of(LocalDate.of(2026, 8, 23)),
                                PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                                PatchValue.absent())));

        assertThrows(Exception.class,
                () -> applier.apply(baseDraft(), patch, dateConflictScope(), validMembers(), validSources()));
    }

    // ── Applier: dependency validation ──

    @Test
    void repairPatchCannotAddSelfDependency() {
        TaskPlanRepairPatch patch = new TaskPlanRepairPatch(
                List.of(),
                List.of(new TaskPlanRepairPatch.TaskPatch(
                        "T2",
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.of(List.of("T1", "T2")),  // T2 depends on itself
                        PatchValue.absent())));

        assertThrows(Exception.class,
                () -> applier.apply(baseDraft(), patch, dateConflictScope(), validMembers(), validSources()));
    }

    @Test
    void repairPatchCannotAddNonexistentDependency() {
        TaskPlanRepairPatch patch = new TaskPlanRepairPatch(
                List.of(),
                List.of(new TaskPlanRepairPatch.TaskPatch(
                        "T2",
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.of(List.of("T1", "NONEXISTENT")),
                        PatchValue.absent())));

        assertThrows(Exception.class,
                () -> applier.apply(baseDraft(), patch, dateConflictScope(), validMembers(), validSources()));
    }

    // ── Applier: null clears nullable ──

    @Test
    void explicitNullClearsNullableDateFields() {
        // Clear startDate and dueDate by setting them to null
        TaskPlanRepairPatch patch = new TaskPlanRepairPatch(
                List.of(),
                List.of(new TaskPlanRepairPatch.TaskPatch(
                        "T2",
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.of(null),  // clear startDate
                        PatchValue.of(null),  // clear dueDate
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent())));

        TaskPlanDraft result = applier.apply(baseDraft(), patch, dateConflictScope(), validMembers(), validSources());
        PlanTask patched = result.tasks().stream().filter(t -> t.tempKey().equals("T2")).findFirst().orElseThrow();
        assertNull(patched.startDate());
        assertNull(patched.dueDate());
    }
}
