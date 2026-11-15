package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.domain.PlanMilestone;
import com.shitulelv.aicollab.planning.domain.PlanTask;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftValidator;
import com.shitulelv.aicollab.planning.domain.ValidationContext;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskPlanPartialRepairValidationContextTest {

    private final UUID projectId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    private TaskPlanRecord plan() {
        return new TaskPlanRecord(planId, projectId, "title", "goal", "constraints",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), 20, "[]",
                com.shitulelv.aicollab.planning.domain.TaskPlanStatus.READY_WITH_ISSUES,
                1, UUID.randomUUID(), 1L, null, actorId, null, null, null, null);
    }

    private TaskPlanDraft draft(String taskTitle) {
        PlanMilestone milestone = new PlanMilestone("M1", "M1", "obj", null,
                LocalDate.of(2026, 8, 15), 0, List.of());
        PlanTask task = new PlanTask("T1", "M1", taskTitle, "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                null, null, List.of(), List.of(), 0);
        return new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(milestone), List.of(task), List.of());
    }

    private TaskPlanPartialRepairService service(TaskPlanRepository repository) {
        return new TaskPlanPartialRepairService(null, repository, null, new TaskPlanDraftValidator(),
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void repairedDraftIsCheckedAgainstProjectExistingTitles() {
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        TaskPlanRecord plan = plan();
        when(repository.validationContext(plan)).thenReturn(new ValidationContext(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                plan.planStartDate(), plan.planDueDate(), plan.maxTaskCount(),
                Set.of(), Set.of("需求调研")));

        var assessment = service(repository).assessRepair(plan, draft("需求调研"));

        assertTrue(assessment.warningCodes().contains("EXISTING_TITLE_SIMILAR"));
    }

    @Test
    void repairedDraftWithDifferentTitleIsNotFlagged() {
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        TaskPlanRecord plan = plan();
        when(repository.validationContext(plan)).thenReturn(new ValidationContext(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                plan.planStartDate(), plan.planDueDate(), plan.maxTaskCount(),
                Set.of(), Set.of("需求调研")));

        var assessment = service(repository).assessRepair(plan, draft("需求分析"));

        assertFalse(assessment.warningCodes().contains("EXISTING_TITLE_SIMILAR"));
    }
}
