package com.shitulelv.aicollab.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P10-ABSTAIN-PRECISION-01 拒答评分精度回归测试（真实评测缺陷驱动，纯确定性）。
 *
 * <p>修复的真实假阳性（2026-11-07 P10 复测捕获）：
 * ① “另一份外部摘录”中的“一份”是语法性指代，被误判为未经支持的编造数字 → CONFLICTING_FABRICATION 假阳性；
 * ② “资料中没有出现‘内部审计每年抽查合同份数’的数字”中的引述对象被误判为肯定断言 → FORBIDDEN_AFFIRMATION 假阳性；
 * ③ Markdown 有序列表序号（1. / 2. / 3.）被当作无证据数字候选。
 * 修复同时保持真实编造的拦截能力不放松。</p>
 */
class P10ScorerAbstentionPrecisionTest {

    private static final List<P10AnswerQualityScorer.Source> SOURCES = List.of(
            new P10AnswerQualityScorer.Source(1, "F2-审计证据管理规范.md", "d1", "c1",
                    "电子证据存储于审计专用的加密存储区，访问日志保留两年；关于核对平台支持数据库直连取证，其数据库类型与部署方式未提供详细信息。"),
            new P10AnswerQualityScorer.Source(2, "D4-培训服务合同管理办法.md", "d2", "c2",
                    "同一供应商的培训服务合同年度累计不得超过五份或累计金额不得超过一百万元。"));

    private static Map<String, Object> scoreAbstain(String answer) {
        Map<String, Object> rules = Map.of(
                "abstentionPhrases", List.of("资料不足", "无法确认", "不能确认", "未提供", "没有说明", "未载明"),
                "forbiddenAffirmations", List.of("每年抽查"));
        return P10AnswerQualityScorer.score("ABSTAIN", "ABSTENTION", true,
                List.of(), rules, answer, SOURCES, P10AnswerQualityScorer.QA, null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> highSeverity(Map<String, Object> score) {
        return (List<String>) score.get("highSeverity");
    }

    // ---- ① AQ11 场景：诚实拒答 + “另一份外部摘录”量词指代 → 不构成编造冲突 ----

    @Test
    void determinerClassifierPhraseIsNotFabricatedNumber() {
        Map<String, Object> score = scoreAbstain(
                "当前项目资料不足以回答该问题。资料未载明所用数据库产品 [S1]。"
                        + "资料中唯一涉及数据库的信息是一份外部审计参考摘录，其中关于数据库类型未提供详细信息 [S1]；"
                        + "另一份外部摘录提到的区块链存证是部分同行企业的做法，不能视为本公司审计核对系统的实际存储方案 [S1]。");
        assertThat(highSeverity(score)).as("“另一份”的量词短语不是编造数字").isEmpty();
        assertThat(score.get("abstentionCorrect")).isEqualTo(true);
        Map<String, Object> abstention = (Map<String, Object>) score.get("abstentionEvaluation");
        assertThat(abstention.get("verdict")).isEqualTo("HONEST_ABSTENTION");
    }

    // ---- ② AQ12 场景：引用框架 + 存在否定 → 非肯定断言 ----

    @Test
    void negatedQuotedMentionOfForbiddenMarkerIsNotAffirmation() {
        Map<String, Object> score = scoreAbstain(
                "当前项目资料不足以回答该问题。"
                        + "提供的资料中没有出现“内部审计每年抽查合同份数”的具体数字或比例。"
                        + "年度抽查样本量的最终比例尚未确定 [S1]。"
                        + "同一供应商累计不得超过五份 [S2] 属于合同签订环节限制，与审计抽查份数无关。"
                        + "\n\n1. 第一条排版要点\n\n2. 第二条排版要点\n\n3. 第三条排版要点");
        assertThat(highSeverity(score)).as("引用框架内被否定的引述不是肯定断言；列表序号不是编造数字").isEmpty();
        Map<String, Object> abstention = (Map<String, Object>) score.get("abstentionEvaluation");
        assertThat(abstention.get("verdict")).isEqualTo("HONEST_ABSTENTION");
    }

    // ---- ⑤ 真实评测变体：无方括号引用编号指代（“从 S5 的表述看”）不是业务数字 ----

    @Test
    void bareCitationReferenceIsNotBusinessNumber() {
        Map<String, Object> score = scoreAbstain(
                "当前项目资料不足以回答该问题。资料未载明所用数据库产品 [S1]。"
                        + "【推断】从 S1 的表述看，公司侧强调的是加密存储区这一存储要求，"
                        + "而非具体数据库技术选型，但资料中没有进一步说明 [S1]。");
        assertThat(highSeverity(score)).as("裸引用编号 S5 不是编造数字").isEmpty();
        assertThat(score.get("abstentionCorrect")).isEqualTo(true);
    }

    // ---- ⑥ 真实评测变体：引号框架 + 后置存在否定（“关于‘每年抽查…’，现有资料没有确定数字”）----

    @Test
    void quotedMentionWithSuffixNegatedExistenceIsNotAffirmation() {
        Map<String, Object> score = scoreAbstain(
                "当前项目资料不足以回答该问题。"
                        + "关于“每年抽查多少份合同”，现有资料没有确定的数字；抽查比例文件未载明具体份数 [S1]。");
        assertThat(highSeverity(score)).as("后置存在否定下的引述不是肯定断言").isEmpty();
        assertThat(score.get("abstentionCorrect")).isEqualTo(true);
    }

    // ---- ③ 真实编造仍被拦截：无引用框架地肯定禁断命题 + 编造数字 ----

    @Test
    void realFabricatedAssertionIsStillViolation() {
        Map<String, Object> score = scoreAbstain(
                "内部审计每年抽查合同份数为二十份 [S1]。");
        assertThat(highSeverity(score)).anyMatch(v -> v.startsWith("FORBIDDEN_AFFIRMATION"));
        assertThat((List<String>) score.get("unsupportedNumberCandidates")).contains("二十份");
    }

    // ---- ④ 无拒答措辞的无答案题编造 → MISSING_UNQUALIFIED（不放松）----

    @Test
    void fabricationWithoutRefusalStillMissingUnqualified() {
        Map<String, Object> score = scoreAbstain(
                "公司内部审计每年抽查合同份数为二十份 [S2]。");
        Map<String, Object> abstention = (Map<String, Object>) score.get("abstentionEvaluation");
        assertThat(abstention.get("verdict")).isEqualTo("MISSING_UNQUALIFIED");
        assertThat(highSeverity(score)).anyMatch(v -> v.startsWith("ABSTENTION_MISSING"));
    }
}
