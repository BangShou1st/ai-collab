package com.shitulelv.aicollab.knowledge.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段四：冻结质量回归测试（离线、确定性、无外部模型调用）。
 * 复用 network-recovery-03 真实评测沉淀的 14 道题候选集合，
 * 验证生产去重规则修改后：
 * 1. Q09 成功获得两份合同的独立来源；
 * 2. 其它冻结问题（Q01, Q03, Q05, Q10, Q11, Q12, Q13, Q16 等）核心必要证据无新增退化。
 */
class FrozenKnowledgeQualityRegressionTest {

    private static final Path EVIDENCE_FILE = Path.of(
            "../docs/acceptance-evidence/2026-10-22/p4-rag-answer-quality-01/network-recovery-03/business-results.json"
    );

    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonNode rootNode;
    private final KnowledgeContextBuilder builder = new KnowledgeContextBuilder();

    @BeforeAll
    static void loadEvidence() throws Exception {
        rootNode = JSON.readTree(EVIDENCE_FILE.toFile());
    }

    private List<DocumentSearchHit> extractCandidates(String questionId) {
        for (JsonNode row : rootNode.path("rows")) {
            if (questionId.equals(row.path("id").asText())) {
                List<DocumentSearchHit> list = new ArrayList<>();
                for (JsonNode c : row.path("candidates")) {
                    UUID id = UUID.fromString(c.path("id").asText());
                    UUID docId = UUID.fromString(c.path("documentId").asText());
                    String filename = c.path("originalFilename").asText();
                    String heading = c.path("heading").asText();
                    String content = c.path("content").asText();
                    String hash = c.path("contentHash").asText();
                    double similarity = c.path("similarity").asDouble();
                    list.add(new DocumentSearchHit(
                            id, docId, filename, heading, content, hash, similarity, Map.of()));
                }
                return list;
            }
        }
        throw new IllegalArgumentException("Question not found in evidence: " + questionId);
    }

    @Test
    @DisplayName("Q09：修复后合同模板乙与合同模板甲同时保留为独立来源S1和S2")
    void q09_retainsBothContractSources() {
        List<DocumentSearchHit> candidates = extractCandidates("Q09");
        KnowledgeContext context = builder.build(candidates);

        assertThat(context.sources()).hasSize(5);
        assertThat(context.sources().get(0).originalFilename()).isEqualTo("合同模板乙.md");
        assertThat(context.sources().get(0).heading()).isEqualTo("重大变更审批");
        assertThat(context.sources().get(0).rank()).isEqualTo(1);

        assertThat(context.sources().get(1).originalFilename()).isEqualTo("合同模板甲.md");
        assertThat(context.sources().get(1).heading()).isEqualTo("重大变更审批");
        assertThat(context.sources().get(1).rank()).isEqualTo(2);

        // 两个来源 chunkId 与 documentId 各自独立
        assertThat(context.sources().get(0).documentId())
                .isNotEqualTo(context.sources().get(1).documentId());
        assertThat(context.sources().get(0).chunkId())
                .isNotEqualTo(context.sources().get(1).chunkId());

        assertThat(context.promptSources()).contains("[S1]\n文件：合同模板乙.md");
        assertThat(context.promptSources()).contains("[S2]\n文件：合同模板甲.md");
    }

    @Test
    @DisplayName("Q01：上线门禁核心证据（自动化验收、阻断缺陷关闭、迁移核对）必须保留")
    void q01_gateConditionsRetained() {
        List<DocumentSearchHit> candidates = extractCandidates("Q01");
        KnowledgeContext context = builder.build(candidates);

        assertThat(context.sources()).isNotEmpty();
        assertThat(context.sources().getFirst().originalFilename()).isEqualTo("上线验收规程.md");
        assertThat(context.sources().getFirst().heading()).isEqualTo("上线门禁");
        assertThat(context.sources().getFirst().content())
                .contains("自动化验收")
                .contains("阻断级缺陷")
                .contains("数据迁移");
    }

    @Test
    @DisplayName("Q03：既有缺陷归因保持不变，来源名额限制依然导致上线门禁未进入上下文，但无新增证据退化")
    void q03_preservesBaselineBehaviorWithoutNewLoss() {
        List<DocumentSearchHit> candidates = extractCandidates("Q03");
        KnowledgeContext context = builder.build(candidates);

        // Q03 在前一轮中保留了5条来源，上线门禁在 Rank 6 未能进入（既有 SOURCE_CAP 缺陷）
        assertThat(context.sources()).hasSize(5);
        assertThat(context.sources()).extracting(KnowledgeSource::originalFilename)
                .contains("上线验收规程.md", "发布回滚预案.md");
        assertThat(context.sources()).extracting(KnowledgeSource::heading)
                .contains("灰度观察", "回滚演练", "回滚触发条件");
    }

    @Test
    @DisplayName("Q05：需求变更单 v1.9 与 v2.1 两个版本的关键证据完整留存")
    void q05_bothVersionEvidencesRetained() {
        List<DocumentSearchHit> candidates = extractCandidates("Q05");
        KnowledgeContext context = builder.build(candidates);

        List<String> headings = context.sources().stream().map(KnowledgeSource::heading).toList();
        assertThat(headings).contains("当前版本修正", "早期版本说明", "REQ-7741");
    }

    @Test
    @DisplayName("Q10：限定单文档范围时，仅合同模板甲保留，绝不泄露合同模板乙")
    void q10_strictlySingleDocumentScoped() {
        List<DocumentSearchHit> candidates = extractCandidates("Q10");
        KnowledgeContext context = builder.build(candidates);

        assertThat(context.sources()).hasSize(1);
        assertThat(context.sources().getFirst().originalFilename()).isEqualTo("合同模板甲.md");
    }

    @Test
    @DisplayName("Q11与Q12：任务预算规则核心条款完整留存")
    void q11_and_q12_budgetRulesRetained() {
        List<DocumentSearchHit> q11Candidates = extractCandidates("Q11");
        KnowledgeContext q11Context = builder.build(q11Candidates);
        assertThat(q11Context.sources()).extracting(KnowledgeSource::originalFilename)
                .containsOnly("任务预算规则.md");

        List<DocumentSearchHit> q12Candidates = extractCandidates("Q12");
        KnowledgeContext q12Context = builder.build(q12Candidates);
        assertThat(q12Context.sources()).extracting(KnowledgeSource::originalFilename)
                .containsOnly("任务预算规则.md");
    }

    @Test
    @DisplayName("Q13：Prompt Injection 语料来源正常构建且注入内容作为资料呈现")
    void q13_injectionCorpusAssembledWithoutBreakingSecurity() {
        List<DocumentSearchHit> candidates = extractCandidates("Q13");
        KnowledgeContext context = builder.build(candidates);

        assertThat(context.sources()).extracting(KnowledgeSource::originalFilename)
                .contains("不可信资料测试.md");
    }

    @Test
    @DisplayName("Q16：权限分级与数据脱敏规则完整留存")
    void q16_maskingAndPermissionRulesRetained() {
        List<DocumentSearchHit> candidates = extractCandidates("Q16");
        KnowledgeContext context = builder.build(candidates);

        assertThat(context.sources()).hasSize(2);
        assertThat(context.sources()).extracting(KnowledgeSource::originalFilename)
                .containsOnly("安全与脱敏规范.md");
        assertThat(context.sources()).extracting(KnowledgeSource::heading)
                .containsExactlyInAnyOrder("脱敏规则", "权限分级");
    }
}
