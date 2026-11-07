package com.shitulelv.aicollab.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P10-VER-01 版本口径评分专项测试（任务 B 组专项，纯确定性）。
 *
 * <p>固化 VERSION_VIOLATION 判定的双向语义：
 * ① 把历史/废止条款表述为现行规定 → 高严重度违规（不放松）；
 * ② 带明确历史状态限定的引用（旧版/已废止/被取代等客观历史表述，含冻结种子词表之外的通用表述）→ 合法。
 * 冻结题集 legacyContextWords 保持不变，内置补充词表只收录任意语境下都表达“历史/失效”状态的通用词。</p>
 */
class P10VersionAccuracyScorerTest {

    /** 冻结题集 AQ02 的规则原样引用（legacyContextWords 不做任何修改）。 */
    private static final Map<String, Object> AQ02_RULES = Map.of(
            "requiredMarkers", List.of("按当晚标准报销", "加班记录"),
            "legacyMarkers", List.of("不予报销"),
            "legacyContextWords", List.of("已废止", "原", "此前", "V2.0 办法第六章", "不再", "曾经"),
            "forbiddenAffirmations", List.of("目前加班次日住宿费不能报销", "现在仍然不予报销", "仍不能报销", "目前不可以报销"));

    private static final List<P10AnswerQualityScorer.Source> SOURCES = List.of(
            new P10AnswerQualityScorer.Source(1, "D3-补充通知.md", "d3", "c3",
                    "自2026年7月1日起，废止V2.0办法第六章中“加班次日的住宿费不予报销”条款；因项目需要加班次日返程的，住宿费按当晚标准报销。"),
            new P10AnswerQualityScorer.Source(2, "D2-办法V2.md", "d2", "c2",
                    "出差期间因加班需要次日返程的，加班次日的住宿费不予报销，当天返程的按正常标准执行。"));

    private static Map<String, Object> score(String answer) {
        return P10AnswerQualityScorer.score("AQ02-REPEALED", "VERSION_REPEALED", false,
                List.of(), AQ02_RULES, answer, SOURCES, P10AnswerQualityScorer.QA, null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> highSeverity(Map<String, Object> score) {
        return (List<String>) score.get("highSeverity");
    }

    @SuppressWarnings("unchecked")
    private static List<String> versionVerdicts(Map<String, Object> score) {
        return ((List<Map<String, Object>>) score.get("versionAccuracy")).stream()
                .map(item -> String.valueOf(item.get("verdict"))).toList();
    }

    // ---- B1 新版有效、旧版废止：现行口径 + 带限定的历史引用 → 合法 ----

    @Test
    void b1_currentAnswerWithQualifiedHistoricalMentionIsLegal() {
        Map<String, Object> score = score(
                "现行规定：因项目需要加班次日返程的，住宿费按当晚标准报销 [S1]。"
                        + "旧版办法中“加班次日的住宿费不予报销”条款已废止 [S2]。");
        assertThat(highSeverity(score)).isEmpty();
        assertThat(versionVerdicts(score)).containsExactly("LEGACY_QUALIFIED");
    }

    // ---- B2 复现修复前假阳性：限定词跨子句（“原 V2.0 办法第六章……已自 2026年7月1日起废止”）→ 合法 ----

    @Test
    void b2_repealStatementAcrossClausesIsQualified() {
        Map<String, Object> score = score(
                "原 V2.0 办法第六章中“出差期间因加班需要次日返程的，加班次日的住宿费不予报销”条款"
                        + "已自 2026年7月1日起废止 [S1]，因此现行为按当晚标准报销 [S1]。");
        assertThat(highSeverity(score)).isEmpty();
    }

    // ---- B10 把历史规定当作现行规定 → 高严重度（不放松）----

    @Test
    void b10_bareCurrentAssertionOfRepealedClauseIsViolation() {
        Map<String, Object> score = score(
                "出差加班后次日返程的住宿费不予报销 [S2]，这是当前有效规定。");
        assertThat(highSeverity(score)).anyMatch(v -> v.startsWith("VERSION_VIOLATION"));
    }

    // ---- 现行主张词紧邻旧标准（RISK-AUDIT2-006 语义保持）----

    @Test
    void currentClaimAdjacentToLegacyMarkerIsViolation() {
        Map<String, Object> score = score(
                "现在出差加班次日返程的，加班次日的住宿费不予报销 [S2]。");
        assertThat(highSeverity(score)).anyMatch(v -> v.startsWith("VERSION_VIOLATION"));
    }

    // ---- 历史限定 + 持续有效性主张（“旧规定仍然适用”）→ 违规 ----

    @Test
    void legacyQualifiedButPersistentClaimIsViolation() {
        Map<String, Object> score = score(
                "该旧规定仍然适用，即出差加班次日返程的住宿费不予报销 [S2]。");
        assertThat(highSeverity(score)).anyMatch(v -> v.startsWith("VERSION_VIOLATION"));
    }

    // ---- B3 新旧对照：两版均引用且分别标注状态 → 合法 ----

    @Test
    void b3_comparisonWithBothVersionsStatusQualifiedIsLegal() {
        Map<String, Object> score = score(
                "旧版规定出差加班次日返程的住宿费不予报销 [S2]，新版规定住宿费按当晚标准报销 [S1]。");
        assertThat(highSeverity(score)).isEmpty();
        assertThat(versionVerdicts(score)).containsExactly("LEGACY_QUALIFIED");
    }

    // ---- B4 冻结种子词表命中仍然有效（回归保护：未削弱原判定）----

    @Test
    void frozenSeedWordStillQualifies() {
        Map<String, Object> score = score(
                "此前规定为加班次日的住宿费不予报销 [S2]，自 2026年7月1日起不再适用。");
        assertThat(highSeverity(score)).isEmpty();
    }

    // ---- 禁断表述判定不受限定词补充影响 ----

    @Test
    void forbiddenAffirmationsUnaffectedByQualifierSupplement() {
        Map<String, Object> score = score(
                "目前加班次日住宿费不能报销 [S2]。");
        assertThat(highSeverity(score)).anyMatch(v -> v.startsWith("FORBIDDEN_AFFIRMATION"));
    }
}
