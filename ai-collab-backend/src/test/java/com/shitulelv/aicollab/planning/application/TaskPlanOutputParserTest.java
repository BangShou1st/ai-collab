package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.domain.DetailModelOutput;
import com.shitulelv.aicollab.planning.domain.SkeletonModelOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RED tests for structured output contract enforcement.
 * S2: Skeleton must NOT contain sources/sourceRefs.
 * S3: Detail must require all business fields, not just tempKey.
 */
class TaskPlanOutputParserTest {

    private TaskPlanOutputParser parser;

    @BeforeEach
    void setUp() {
        parser = new TaskPlanOutputParser(new ObjectMapper().findAndRegisterModules());
    }

    // ══════════════════════════════════════════════════════════════════
    // S2 RED: Skeleton Schema must not have sources/sourceRefs
    // ══════════════════════════════════════════════════════════════════

    @Test
    void skeletonWithoutSourcesCanBeParsed() {
        String json = """
                {"summary":"s","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"T","objective":"O","sortOrder":0}]}
                """;
        SkeletonModelOutput result = parser.parseSkeleton(json);
        assertThat(result.summary()).isEqualTo("s");
        assertThat(result.milestones()).hasSize(1);
        assertThat(result.tasks()).hasSize(1);
    }

    @Test
    void skeletonWithTopLevelSourcesIsRejected() {
        // S2: Skeleton must NOT have sources — design says identity-only
        String json = """
                {"summary":"s","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"T","objective":"O","sortOrder":0}],
                "sources":[{"ref":"S1"}]}
                """;
        assertThatThrownBy(() -> parser.parseSkeleton(json))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void skeletonMilestoneWithSourceRefsIsRejected() {
        // S2: Skeleton milestone must NOT have sourceRefs
        String json = """
                {"summary":"s","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0,"sourceRefs":["S1"]}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"T","objective":"O","sortOrder":0}]}
                """;
        assertThatThrownBy(() -> parser.parseSkeleton(json))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void skeletonWithDetailFieldsIsRejected() {
        // Skeleton must not contain description, priority, etc.
        String json = """
                {"summary":"s","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"T","objective":"O","sortOrder":0,
                "description":"detail","priority":"HIGH"}]}
                """;
        assertThatThrownBy(() -> parser.parseSkeleton(json))
                .isInstanceOf(ModelOutputContractException.class);
    }

    // ══════════════════════════════════════════════════════════════════
    // S3 RED: Detail Schema must require all business fields
    // ══════════════════════════════════════════════════════════════════

    @Test
    void detailWithOnlyTempKeyForMilestoneIsRejected() {
        // S3: Milestone must require description and sourceRefs, not just tempKey
        String json = """
                {"milestones":[{"tempKey":"m1"}],
                "tasks":[{"tempKey":"t1","description":"d","priority":"HIGH",
                "estimatedHours":null,"startDate":null,"dueDate":null,
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """;
        assertThatThrownBy(() -> parser.parseDetail(json))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void detailWithOnlyTempKeyForTaskIsRejected() {
        // S3: Task must require description, priority, dependencyTempKeys, sourceRefs
        String json = """
                {"milestones":[{"tempKey":"m1","description":"d","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1"}]}
                """;
        assertThatThrownBy(() -> parser.parseDetail(json))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void detailMissingDescriptionIsRejected() {
        String json = """
                {"milestones":[{"tempKey":"m1","description":"d","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","priority":"HIGH","estimatedHours":null,
                "startDate":null,"dueDate":null,"suggestedAssigneeId":null,
                "dependencyTempKeys":[],"sourceRefs":[]}]}
                """;
        assertThatThrownBy(() -> parser.parseDetail(json))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void detailMissingPriorityIsRejected() {
        String json = """
                {"milestones":[{"tempKey":"m1","description":"d","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"d","estimatedHours":null,
                "startDate":null,"dueDate":null,"suggestedAssigneeId":null,
                "dependencyTempKeys":[],"sourceRefs":[]}]}
                """;
        assertThatThrownBy(() -> parser.parseDetail(json))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void detailMissingDependencyTempKeysIsRejected() {
        String json = """
                {"milestones":[{"tempKey":"m1","description":"d","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"d","priority":"HIGH","estimatedHours":null,
                "startDate":null,"dueDate":null,"suggestedAssigneeId":null,"sourceRefs":[]}]}
                """;
        assertThatThrownBy(() -> parser.parseDetail(json))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void detailMissingSourceRefsIsRejected() {
        String json = """
                {"milestones":[{"tempKey":"m1","description":"d","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"d","priority":"HIGH","estimatedHours":null,
                "startDate":null,"dueDate":null,"suggestedAssigneeId":null,
                "dependencyTempKeys":[]}]}
                """;
        assertThatThrownBy(() -> parser.parseDetail(json))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void detailWithSkeletonIdentityFieldsIsRejected() {
        // Detail must not contain title, objective, sortOrder (skeleton identity)
        String json = """
                {"milestones":[{"tempKey":"m1","description":"d","sourceRefs":[],"title":"X"}],
                "tasks":[{"tempKey":"t1","description":"d","priority":"HIGH","estimatedHours":null,
                "startDate":null,"dueDate":null,"suggestedAssigneeId":null,
                "dependencyTempKeys":[],"sourceRefs":[]}]}
                """;
        assertThatThrownBy(() -> parser.parseDetail(json))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void completeDetailCanBeParsedAndMerged() {
        // Minimal valid detail — all required fields present (including nullable)
        String json = """
                {"milestones":[{"tempKey":"m1","description":"desc","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"desc","priority":"HIGH",
                "estimatedHours":null,"startDate":null,"dueDate":null,
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """;
        DetailModelOutput result = parser.parseDetail(json);
        assertThat(result.milestones()).hasSize(1);
        assertThat(result.milestones().getFirst().tempKey()).isEqualTo("m1");
        assertThat(result.milestones().getFirst().description()).isEqualTo("desc");
        assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().getFirst().description()).isEqualTo("desc");
        assertThat(result.tasks().getFirst().priority()).isEqualTo("HIGH");
        assertThat(result.tasks().getFirst().estimatedHours()).isNull();
        assertThat(result.tasks().getFirst().startDate()).isNull();
        assertThat(result.tasks().getFirst().suggestedAssigneeId()).isNull();
    }

    // ════════════════════════════════════════════════════════════════
    // S6: Omitted vs explicit null — nullable fields must appear
    // ════════════════════════════════════════════════════════════════

    @Test
    void omittedEstimatedHoursIsRejected() {
        String json = """
                {"milestones":[{"tempKey":"m1","description":"desc","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"d","priority":"HIGH",
                "startDate":null,"dueDate":null,"suggestedAssigneeId":null,
                "dependencyTempKeys":[],"sourceRefs":[]}]}
                """;
        assertThatThrownBy(() -> parser.parseDetail(json))
                .isInstanceOf(ModelOutputContractException.class)
                .hasMessageContaining("estimatedHours");
    }

    @Test
    void explicitNullEstimatedHoursIsAccepted() {
        String json = """
                {"milestones":[{"tempKey":"m1","description":"desc","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"d","priority":"HIGH",
                "estimatedHours":null,"startDate":null,"dueDate":null,
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """;
        DetailModelOutput result = parser.parseDetail(json);
        assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().getFirst().estimatedHours()).isNull();
    }

    @Test
    void omittedSuggestedAssigneeIdIsRejected() {
        String json = """
                {"milestones":[{"tempKey":"m1","description":"desc","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"d","priority":"HIGH",
                "estimatedHours":null,"startDate":null,"dueDate":null,
                "dependencyTempKeys":[],"sourceRefs":[]}]}
                """;
        assertThatThrownBy(() -> parser.parseDetail(json))
                .isInstanceOf(ModelOutputContractException.class)
                .hasMessageContaining("suggestedAssigneeId");
    }

    @Test
    void explicitNullSuggestedAssigneeIdIsAccepted() {
        String json = """
                {"milestones":[{"tempKey":"m1","description":"desc","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"d","priority":"HIGH",
                "estimatedHours":null,"startDate":null,"dueDate":null,
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """;
        DetailModelOutput result = parser.parseDetail(json);
        assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().getFirst().suggestedAssigneeId()).isNull();
    }
}
