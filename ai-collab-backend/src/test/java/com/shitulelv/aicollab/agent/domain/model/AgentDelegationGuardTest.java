package com.shitulelv.aicollab.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 委派防重策略（纯函数）测试：归一化把大小写/标点/空白变体折叠为同一键，
 * 不同 objective 不误判，空 objective 有确定行为。
 */
class AgentDelegationGuardTest {

    // ===== normalizeObjective =====

    @Test
    void nullAndBlankNormalizeToEmpty() {
        assertThat(AgentDelegationGuard.normalizeObjective(null)).isEmpty();
        assertThat(AgentDelegationGuard.normalizeObjective("")).isEmpty();
        assertThat(AgentDelegationGuard.normalizeObjective("   \t\n ")).isEmpty();
    }

    @Test
    void casePunctuationAndWhitespaceVariantsCollapseToSameKey() {
        String base = AgentDelegationGuard.normalizeObjective(
                "Research the acceptance criteria in project documents");
        // 大小写变体
        assertThat(AgentDelegationGuard.normalizeObjective(
                "RESEARCH THE ACCEPTANCE CRITERIA IN PROJECT DOCUMENTS")).isEqualTo(base);
        assertThat(AgentDelegationGuard.normalizeObjective(
                "Research The Acceptance Criteria In Project Documents")).isEqualTo(base);
        // 标点变体（连续标点压成单个空格）
        assertThat(AgentDelegationGuard.normalizeObjective(
                "Research, the acceptance criteria!! in... project documents???")).isEqualTo(base);
        // 空白变体（连续空白折叠）
        assertThat(AgentDelegationGuard.normalizeObjective(
                "Research   the\tacceptance\n criteria  in \t project \t documents")).isEqualTo(base);
        // 组合变体
        assertThat(AgentDelegationGuard.normalizeObjective(
                "  Research—the;acceptance:criteria, in (project) documents! ")).isEqualTo(base);
    }

    @Test
    void cjkTextCollapsesPunctuationButKeepsChineseCharacters() {
        // 中文标点变体折叠为同一键（标点压成单个空格）
        assertThat(AgentDelegationGuard.normalizeObjective("研究，项目、文档！中的验收标准。"))
                .isEqualTo("研究 项目 文档 中的验收标准");
        // 空白分隔的中文同样折叠（连续空白压成单个空格）
        assertThat(AgentDelegationGuard.normalizeObjective("研究 项目 文档 中的 验收标准"))
                .isEqualTo("研究 项目 文档 中的 验收标准");
        // 中文不是标点，不被剔除
        assertThat(AgentDelegationGuard.normalizeObjective("研究项目文档中的验收标准"))
                .isEqualTo("研究项目文档中的验收标准");
    }

    @Test
    void differentObjectivesDoNotCollapse() {
        String a = AgentDelegationGuard.objectiveHash("研究项目文档中的验收标准");
        String b = AgentDelegationGuard.objectiveHash("研究项目文档中的依赖关系");
        assertThat(a).isNotEqualTo(b);
        assertThat(AgentDelegationGuard.normalizeObjective("研究验收标准"))
                .isNotEqualTo(AgentDelegationGuard.normalizeObjective("研究依赖关系"));
    }

    @Test
    void normalizationTruncatesToMaxLength() {
        // 每个词后接标点/空白，归一化后总长超过 512
        String longObjective = ("word ").repeat(120).trim();
        String normalized = AgentDelegationGuard.normalizeObjective(longObjective);
        assertThat(normalized.length()).isEqualTo(AgentDelegationGuard.MAX_NORMALIZED_LENGTH);
        // 差异只出现在截断点之后的变体仍命中同一哈希（截断保证判重键稳定）
        String tailVariant = longObjective + " completely different tail content";
        assertThat(AgentDelegationGuard.objectiveHash(tailVariant))
                .isEqualTo(AgentDelegationGuard.objectiveHash(longObjective));
    }

    @Test
    void leadingAndTrailingPunctuationDoNotProduceOuterSpaces() {
        assertThat(AgentDelegationGuard.normalizeObjective("...研究验收标准...")).isEqualTo("研究验收标准");
        assertThat(AgentDelegationGuard.normalizeObjective("  hello  ")).isEqualTo("hello");
    }

    // ===== objectiveHash =====

    @Test
    void hashIsDeterministicSha256HexOfNormalizedObjective() {
        String hash = AgentDelegationGuard.objectiveHash("Research the acceptance criteria");
        // sha256 十六进制固定 64 个小写字符
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        // 变体命中同一哈希
        assertThat(AgentDelegationGuard.objectiveHash(
                "research, the acceptance criteria!!")).isEqualTo(hash);
        assertThat(AgentDelegationGuard.objectiveHash(
                "  RESEARCH   the acceptance criteria  ")).isEqualTo(hash);
        // 重复调用稳定
        assertThat(AgentDelegationGuard.objectiveHash("Research the acceptance criteria")).isEqualTo(hash);
    }

    @Test
    void emptyObjectiveStillProducesDeterministicHash() {
        String emptyHash = AgentDelegationGuard.objectiveHash("");
        assertThat(emptyHash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(AgentDelegationGuard.objectiveHash(null)).isEqualTo(emptyHash);
        assertThat(AgentDelegationGuard.objectiveHash("   ")).isEqualTo(emptyHash);
        // 空 objective 与非空 objective 不误判为相同
        assertThat(emptyHash).isNotEqualTo(AgentDelegationGuard.objectiveHash("研究验收标准"));
    }

    @Test
    void hashIsCaseInsensitiveToLocaleDefaults() {
        // 带土耳其等敏感字符的输入也不因默认 Locale 产生漂移（实现固定 Locale.ROOT）
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            String expected = AgentDelegationGuard.objectiveHash("RESEARCH THE CRITERIA");
            String actual = AgentDelegationGuard.objectiveHash("research the criteria");
            assertThat(actual).isEqualTo(expected);
        } finally {
            Locale.setDefault(original);
        }
    }

    // ===== isDuplicateObjective =====

    @Test
    void zeroActiveRecordsIsNotDuplicate() {
        assertThat(AgentDelegationGuard.isDuplicateObjective(0)).isFalse();
    }

    @Test
    void anyActiveRecordIncludingCompletedOrFailedIsDuplicate() {
        assertThat(AgentDelegationGuard.isDuplicateObjective(1)).isTrue();
        assertThat(AgentDelegationGuard.isDuplicateObjective(3)).isTrue();
    }

    @Test
    void negativeCountIsNotDuplicate() {
        // 防御性：计数不应为负，负数视为无记录
        assertThat(AgentDelegationGuard.isDuplicateObjective(-1)).isFalse();
    }
}
