package com.shitulelv.aicollab.common.ai.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 版本选择规则（§3.2）：按 (日期, 序号) 数值比较，不得退化为字符串序。 */
class PromptVersionTest {

    @Test
    void sequenceIsComparedNumerically() {
        assertThat(PromptVersion.compare("2026-11-11.10", "2026-11-11.2")).isPositive();
        assertThat(PromptVersion.compare("2026-11-11.2", "2026-11-11.1")).isPositive();
        assertThat(PromptVersion.compare("2026-11-11.1", "2026-11-11.1")).isZero();
        assertThat(PromptVersion.compare("2026-11-11.1", "2026-11-11.2")).isNegative();
    }

    @Test
    void dateDominatesSequence() {
        assertThat(PromptVersion.compare("2026-11-12.1", "2026-11-11.999")).isPositive();
        assertThat(PromptVersion.compare("2025-12-31.1", "2026-01-01.0")).isNegative();
    }

    @Test
    void sortsByDateThenNumericSequence() {
        List<String> sorted = List.of("2026-11-11.1", "2026-11-11.2", "2026-11-11.10",
                        "2026-11-12.1")
                .stream()
                .sorted(PromptVersion.COMPARATOR)
                .toList();

        assertThat(sorted).containsExactly(
                "2026-11-11.1", "2026-11-11.2", "2026-11-11.10", "2026-11-12.1");
    }

    @Test
    void maxPicksNumericMaximum() {
        assertThat(PromptVersion.max(List.of("2026-11-11.2", "2026-11-11.10", "2026-11-11.1")))
                .isEqualTo("2026-11-11.10");
    }

    @Test
    void acceptsFrozenFormat() {
        assertThat(PromptVersion.isValid("2026-11-11.1")).isTrue();
        assertThat(PromptVersion.isValid("2026-11-11.10")).isTrue();
        assertThat(PromptVersion.isValid("2026-02-28.1")).isTrue();
        assertThat(PromptVersion.isValid("2024-02-29.1")).isTrue();
    }

    @Test
    void rejectsMalformedVersions() {
        assertThat(PromptVersion.isValid(null)).isFalse();
        assertThat(PromptVersion.isValid("")).isFalse();
        assertThat(PromptVersion.isValid("2026-11-11")).isFalse();
        assertThat(PromptVersion.isValid("2026-11-11.")).isFalse();
        assertThat(PromptVersion.isValid("26-11-11.1")).isFalse();
        assertThat(PromptVersion.isValid("2026-1-1.1")).isFalse();
        assertThat(PromptVersion.isValid("2026-11-11.1 ")).isFalse();
        assertThat(PromptVersion.isValid("2026-11-11.-1")).isFalse();
        assertThat(PromptVersion.isValid("2026-13-01.1")).isFalse();
        assertThat(PromptVersion.isValid("2026-02-30.1")).isFalse();
    }

    @Test
    void compareRejectsMalformedVersion() {
        assertThatThrownBy(() -> PromptVersion.compare("2026-11-11", "2026-11-11.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式非法");
    }

    @Test
    void maxRejectsEmptyAndMalformedInput() {
        assertThatThrownBy(() -> PromptVersion.max(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromptVersion.max(List.of("not-a-version")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
