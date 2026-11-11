package com.shitulelv.aicollab.common.ai.prompt;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 冻结 key 集合（§9）：14 条、顺序稳定、scenario code 大小写不敏感。 */
class PromptKeysTest {

    @Test
    void allContainsExactlyFourteenStableKeys() {
        List<String> keys = PromptKeys.all();

        assertThat(keys).hasSize(14).doesNotHaveDuplicates();
        assertThat(keys.subList(0, 2))
                .containsExactly("knowledge.system", "knowledge.user");
        assertThat(new HashSet<>(keys)).hasSize(14);
        assertThat(keys).allSatisfy(key ->
                assertThat(key).matches("^(knowledge\\.[a-z_]+|scenario\\.[a-z_]+\\.(instruction|output_contract))$"));
    }

    @Test
    void allCoversEveryScenarioInstructionAndOutputContract() {
        for (String code : List.of("delivery_readiness", "iteration_planning", "meeting_to_tasks",
                "project_health", "project_research", "weekly_report")) {
            assertThat(PromptKeys.all())
                    .contains(PromptKeys.scenarioInstruction(code),
                            PromptKeys.scenarioOutputContract(code));
        }
    }

    @Test
    void scenarioKeysLowercaseTheCode() {
        assertThat(PromptKeys.scenarioInstruction("PROJECT_RESEARCH"))
                .isEqualTo("scenario.project_research.instruction");
        assertThat(PromptKeys.scenarioOutputContract("Weekly_Report"))
                .isEqualTo("scenario.weekly_report.output_contract");
        assertThat(PromptKeys.scenarioInstruction("project_health"))
                .isEqualTo("scenario.project_health.instruction");
    }

    @Test
    void invalidScenarioCodeIsProgrammingError() {
        assertThatThrownBy(() -> PromptKeys.scenarioInstruction(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromptKeys.scenarioOutputContract(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromptKeys.scenarioOutputContract("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allIsImmutable() {
        assertThatThrownBy(() -> PromptKeys.all().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
