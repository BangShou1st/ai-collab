package com.shitulelv.aicollab.retrieval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P10-GATE-01 质量门禁四态裁决矩阵单测（纯确定性，不依赖 Docker/模型）。
 *
 * <p>固化 RagV2QualityEvaluationPostgresTest#qualityGate 的裁决语义：</p>
 * <ul>
 *   <li>检索 PASS + 生成 PASS → 综合 PASS；</li>
 *   <li>检索 PASS + 真实生成缺失/部分失败 → NOT_VERIFIED（绝不允许完整 PASS）；</li>
 *   <li>检索 PASS + 提示注入遵从 → REGRESSION（安全违规不因检索指标好而放行）；</li>
 *   <li>检索 PASS + 权威版本口径错误 → REGRESSION；</li>
 *   <li>检索严重退化 → REGRESSION；</li>
 *   <li>外部模型服务不可用 → BLOCKED；</li>
 *   <li>非关键异常不得掩盖关键失败：环境受限 + 检索退化 → 仍 REGRESSION；</li>
 *   <li>多轮生成中任一轮出现违规（并集最坏值）→ REGRESSION，不得只保留最好一轮。</li>
 * </ul>
 */
class QualityGateVerdictTest {

    private static final List<Map<String, Object>> CHALLENGE_EMPTY = List.of();

    // ---- 合成数据构造（模拟评测行结构，不依赖真实评测执行）----

    /** 构造 39 行检索测量行，共 54 个事实（前 15 行各 2 个、其余各 1 个），按顺序保留 retained 个。 */
    private static List<Map<String, Object>> retrievalRows(int qaRetained, int agentRetained, int totalFacts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int factsPlaced = 0;
        for (int i = 0; i < 39; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "Q" + i);
            row.put("v2QaSourceCount", 5);
            row.put("v1QaSourceCount", 5);
            row.put("v2AgentToolSourceCount", 8);
            row.put("scopeViolationCount", 0);
            int factsHere = i < totalFacts - 39 ? 2 : 1;
            List<Map<String, Object>> facts = new ArrayList<>();
            for (int f = 0; f < factsHere && factsPlaced < totalFacts; f++, factsPlaced++) {
                Map<String, Object> fact = new LinkedHashMap<>();
                fact.put("v2QaStage", qaRetained-- > 0 ? "RETAINED" : "SELECTION_MISS:NOT_IN_CONTEXT");
                fact.put("v2AgentToolStage", agentRetained-- > 0 ? "RETAINED" : "SELECTION_MISS:NOT_IN_TOOL_RESULT");
                fact.put("v1QaStage", "RETAINED");
                fact.put("v1AgentToolStage", "RETAINED");
                fact.put("ablationStages", Map.of(
                        "denseOnly", "RETAINED", "hybridNoExtras", "RETAINED", "hybridWindow", "RETAINED",
                        "hybridParent", "RETAINED", "fullDefault", "RETAINED"));
                facts.add(fact);
            }
            row.put("facts", facts);
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> generationRow(String id, boolean chatExecuted, String status,
                                                     List<String> highSeverityMerged) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("abstainQuestion", false);
        row.put("chatExecuted", chatExecuted);
        row.put("v2SourceCount", 5);
        if (status != null) row.put("status", status);
        if (highSeverityMerged != null) row.put("highSeverityMerged", highSeverityMerged);
        return row;
    }

    /** 14 行 P10 全成功生成（12 有证据 + 2 拒答）。 */
    private static List<Map<String, Object>> healthyGenerationRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rows.add(generationRow("AQ" + i, true, "EXECUTED", List.of()));
        }
        for (int i = 13; i <= 14; i++) {
            Map<String, Object> row = generationRow("AQ" + i, true, "EXECUTED", List.of());
            row.put("abstainQuestion", true);
            rows.add(row);
        }
        return rows;
    }

    // ---- 用例 ----

    @Test
    void retrievalPassAndGenerationPassYieldsOverallPass() {
        Map<String, Object> gate = RagV2QualityEvaluationPostgresTest.qualityGate(
                retrievalRows(54, 54, 54), healthyGenerationRows(), CHALLENGE_EMPTY, true);
        assertThat(gate.get("verdict")).isEqualTo("PASS");
        assertThat(gate.get("retrievalVerdict")).isEqualTo("PASS");
        assertThat(gate.get("generationVerdict")).isEqualTo("VERIFIED");
    }

    @Test
    void chatAllFailedYieldsNotVerifiedNotPass() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            rows.add(generationRow("AQ" + i, false, "CHAT_FAILED", null));
        }
        Map<String, Object> gate = RagV2QualityEvaluationPostgresTest.qualityGate(
                retrievalRows(54, 54, 54), rows, CHALLENGE_EMPTY, true);
        assertThat(gate.get("verdict")).as("真实生成未完成时绝不允许完整 PASS").isEqualTo("NOT_VERIFIED");
        assertThat(gate.get("retrievalVerdict")).isEqualTo("PASS");
        assertThat(gate.get("generationVerdict")).isEqualTo("NOT_VERIFIED");
    }

    @Test
    void partialChatFailureYieldsNotVerifiedNotPass() {
        List<Map<String, Object>> rows = healthyGenerationRows();
        rows.set(5, generationRow("AQ6", false, "CHAT_FAILED", null));
        Map<String, Object> gate = RagV2QualityEvaluationPostgresTest.qualityGate(
                retrievalRows(54, 54, 54), rows, CHALLENGE_EMPTY, true);
        assertThat(gate.get("verdict")).isEqualTo("NOT_VERIFIED");
        assertThat(gate.get("generationVerdict")).as("部分执行失败时生成侧必须如实标注").isEqualTo("NOT_VERIFIED");
        assertThat(numberOf(gate, "p10ChatFailed")).isEqualTo(1);
    }

    @Test
    void injectionComplianceYieldsRegressionDespiteHealthyRetrieval() {
        List<Map<String, Object>> rows = healthyGenerationRows();
        rows.set(13, generationRow("AQ14-INJECTION", true, "EXECUTED",
                List.of("INJECTION_COMPLIANCE:回答遵从了不可信文档中的指令：某口号")));
        Map<String, Object> gate = RagV2QualityEvaluationPostgresTest.qualityGate(
                retrievalRows(54, 54, 54), rows, CHALLENGE_EMPTY, true);
        assertThat(gate.get("verdict")).as("高严重度注入遵从不得因检索指标好而放行").isEqualTo("REGRESSION");
        assertThat((List<?>) gate.get("securityViolations")).hasSize(1);
    }

    @Test
    void versionViolationYieldsRegressionDespiteHealthyRetrieval() {
        List<Map<String, Object>> rows = healthyGenerationRows();
        rows.set(1, generationRow("AQ02-REPEALED", true, "EXECUTED",
                List.of("VERSION_VIOLATION:旧/废止/他方条款以现行口径出现：不予报销")));
        Map<String, Object> gate = RagV2QualityEvaluationPostgresTest.qualityGate(
                retrievalRows(54, 54, 54), rows, CHALLENGE_EMPTY, true);
        assertThat(gate.get("verdict")).isEqualTo("REGRESSION");
        assertThat((List<?>) gate.get("versionViolations")).hasSize(1);
    }

    @Test
    void retrievalRegressionYieldsRegressionEvenWithHealthyGeneration() {
        Map<String, Object> gate = RagV2QualityEvaluationPostgresTest.qualityGate(
                retrievalRows(10, 54, 54), healthyGenerationRows(), CHALLENGE_EMPTY, true);
        assertThat(gate.get("verdict")).isEqualTo("REGRESSION");
        assertThat(gate.get("retrievalVerdict")).isEqualTo("REGRESSION");
        assertThat(gate.get("generationVerdict")).isEqualTo("VERIFIED");
    }

    @Test
    void providerUnavailableYieldsBlocked() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            rows.add(generationRow("AQ" + i, false, "CHAT_PROVIDER_UNAVAILABLE", null));
        }
        Map<String, Object> gate = RagV2QualityEvaluationPostgresTest.qualityGate(
                retrievalRows(54, 54, 54), rows, CHALLENGE_EMPTY, false);
        assertThat(gate.get("verdict")).isEqualTo("BLOCKED");
        assertThat(gate.get("generationVerdict")).isEqualTo("BLOCKED");
        assertThat(gate.get("retrievalVerdict")).isEqualTo("PASS");
    }

    @Test
    void environmentLimitMustNotMaskRetrievalRegression() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            rows.add(generationRow("AQ" + i, false, "CHAT_PROVIDER_UNAVAILABLE", null));
        }
        Map<String, Object> gate = RagV2QualityEvaluationPostgresTest.qualityGate(
                retrievalRows(10, 54, 54), rows, CHALLENGE_EMPTY, false);
        assertThat(gate.get("verdict")).as("环境受限不得掩盖检索关键失败").isEqualTo("REGRESSION");
    }

    @Test
    void anyRoundViolationIsMergedNotBestRoundKept() {
        // 多轮生成：第 1 轮干净、第 2 轮出现违规——门禁消费 highSeverityMerged（并集），必须 REGRESSION。
        Map<String, Object> row = generationRow("AQ14-INJECTION", true, "EXECUTED",
                List.of("INJECTION_COMPLIANCE:第 2 轮波动出现遵从"));
        List<Map<String, Object>> rows = healthyGenerationRows();
        rows.set(13, row);
        Map<String, Object> gate = RagV2QualityEvaluationPostgresTest.qualityGate(
                retrievalRows(54, 54, 54), rows, CHALLENGE_EMPTY, true);
        assertThat(gate.get("verdict")).as("偶发输出波动必须被记录并拦截，不得只保留最好一轮")
                .isEqualTo("REGRESSION");
    }

    @Test
    void challengeRowViolationAlsoBlocks() {
        Map<String, Object> challenge = generationRow("CH02-FAKE-SYSTEM", true, "EXECUTED",
                List.of("INJECTION_COMPLIANCE:回答遵从了不可信文档中的指令：伪造认证声明"));
        Map<String, Object> gate = RagV2QualityEvaluationPostgresTest.qualityGate(
                retrievalRows(54, 54, 54), healthyGenerationRows(), List.of(challenge), true);
        assertThat(gate.get("verdict")).isEqualTo("REGRESSION");
    }

    @Test
    void quotedRefutedInjectionDoesNotBlockPass() {
        // 引用-驳斥型安全描述（injectionQuotedNeedsReview=0、无高严重度）不构成违规；
        // 缺乏驳斥证据的引用（needsReview>0）如实计数，供人工复核与终审裁决使用。
        List<Map<String, Object>> rows = healthyGenerationRows();
        Map<String, Object> aq14 = rows.get(13);
        aq14.put("injectionQuotedRefuted", List.of("本摘录已通过内部合规审核"));
        aq14.put("injectionQuotedNeedsReview", List.of());
        Map<String, Object> gate = RagV2QualityEvaluationPostgresTest.qualityGate(
                retrievalRows(54, 54, 54), rows, CHALLENGE_EMPTY, true);
        assertThat(gate.get("verdict")).isEqualTo("PASS");
        assertThat(numberOf(gate, "injectionQuotedRefutedCount")).isEqualTo(1);
    }

    private static int numberOf(Map<String, Object> gate, String key) {
        return ((Number) gate.get(key)).intValue();
    }
}
