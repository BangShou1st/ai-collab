package com.shitulelv.aicollab.planning.api;

import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.domain.TaskPlanVersionSource;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningOpenApiContractTest {

    @Test
    @SuppressWarnings("unchecked")
    void planningContractParsesWithoutDuplicatePlanPathAndMatchesStatusEnum() throws Exception {
        Path contract = Path.of("..", "docs", "api", "openapi.yaml");
        String source = Files.readString(contract);
        assertThat(source.split("(?m)^  /projects/\\{projectId}/ai/task-plans/\\{planId}:$", -1))
                .hasSize(2);

        Map<String, Object> root = new Yaml().load(source);
        Map<String, Object> paths = (Map<String, Object>) root.get("paths");
        Map<String, Object> planPath = (Map<String, Object>) paths.get(
                "/projects/{projectId}/ai/task-plans/{planId}");
        assertThat(planPath).containsKeys("get", "patch", "delete");

        Map<String, Object> components = (Map<String, Object>) root.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> status = (Map<String, Object>) schemas.get("TaskPlanStatus");
        assertThat((List<String>) status.get("enum"))
                .containsExactlyInAnyOrder(
                        java.util.Arrays.stream(TaskPlanStatus.values()).map(Enum::name).toArray(String[]::new));

        Map<String, Object> partialPath = (Map<String, Object>) paths.get(
                "/projects/{projectId}/ai/task-plans/{planId}/partial-regenerate");
        Map<String, Object> post = (Map<String, Object>) partialPath.get("post");
        assertThat((Map<String, Object>) post.get("responses")).containsKey("202");

        Map<String, Object> delete = (Map<String, Object>) planPath.get("delete");
        assertThat((Map<String, Object>) delete.get("responses")).containsKey("204");

        Map<String, Object> versionView = (Map<String, Object>) schemas.get("TaskPlanVersionView");
        Map<String, Object> versionProperties = (Map<String, Object>) versionView.get("properties");
        Map<String, Object> sourceType = (Map<String, Object>) versionProperties.get("sourceType");
        assertThat((List<String>) sourceType.get("enum"))
                .containsExactlyInAnyOrder(
                        java.util.Arrays.stream(TaskPlanVersionSource.values()).map(Enum::name).toArray(String[]::new));

        Map<String, Object> updateRequest = (Map<String, Object>) schemas.get("UpdateTaskPlanRequest");
        assertThat((List<String>) updateRequest.get("required"))
                .contains("baseVersionId", "expectedVersionNo");

        Map<String, Object> partialRequest = (Map<String, Object>) schemas.get("PartialRegenerateRequest");
        assertThat((List<String>) partialRequest.get("required"))
                .contains("baseVersionId", "expectedVersionNo", "mode");
        Map<String, Object> partialProperties =
                (Map<String, Object>) partialRequest.get("properties");
        Map<String, Object> partialMode = (Map<String, Object>) partialProperties.get("mode");
        assertThat((List<String>) partialMode.get("enum"))
                .contains("REPAIR_DATES_AND_DEPENDENCIES",
                        "REGENERATE_SELECTED_TASK_DETAILS",
                        "REPAIR_ASSIGNMENTS_AND_SOURCES",
                        "REPAIR_ALL_ISSUES");

        Map<String, Object> eventView = (Map<String, Object>) schemas.get("TaskPlanEventView");
        Map<String, Object> eventProperties = (Map<String, Object>) eventView.get("properties");
        assertThat(eventProperties)
                .containsKeys("id", "fromVersionId", "toVersionId", "eventType",
                        "changedFields", "changedTargets", "issueCodes", "createdAt")
                .doesNotContainKeys("actorId", "beforeHash", "afterHash");
    }
}
