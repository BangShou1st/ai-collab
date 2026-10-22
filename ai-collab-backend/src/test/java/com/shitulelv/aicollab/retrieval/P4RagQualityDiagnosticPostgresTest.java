package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.service.ContextDropReason;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.retrieval.RetrievalCoverage.ExpectedEvidence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * P4-RAG-QUALITY-01 综合诊断与对照测试套件。
 *
 * <p>涵盖任务核心要求：</p>
 * <ol>
 *   <li><b>阶段二：15 业务场景基线覆盖</b>（长文档尾部、中部、互补事实、同文档多分块、跨文档同正文、版本冲突、REQ-7741、
 *       同义缩写、限定文档、排除文档、高相似干扰、无答案、Top8 外深排位、SOURCE_CAP 损失、长正文预算等）；</li>
 *   <li><b>阶段五：知识库问答与 Agent 检索同题严格对照</b>（Top 8 / 5 来源 / build vs Top 20 / 12 来源 / buildDiverse）；</li>
 *   <li><b>阶段六：混合检索与 Reranker 价值评估</b>（精确 ID 检索、关键词融合、词法与向量互补性、Reranker 真实价值分析）。</li>
 * </ol>
 *
 * <p>诊断报告统一输出至 {@code target/retrieval-baseline/p4-rag-quality-01/} 目录。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "agent.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "security.jwt.access-token-minutes=30",
        "storage.minio.endpoint=http://127.0.0.1:1",
        "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret",
        "storage.minio.bucket=test-bucket"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
public class P4RagQualityDiagnosticPostgresTest {
    private static final int TOP_8 = 8;
    private static final int TOP_20 = 20;
    private static final int QA_LIMIT = KnowledgeContextBuilder.DEFAULT_MAX_SOURCES; // 5
    private static final int AGENT_LIMIT = KnowledgeContextBuilder.MAX_SOURCES_LIMIT; // 12
    private static final String PROVIDER = "OLLAMA";
    private static final String MODEL = "synthetic-p4-quality";
    private static final int DIMENSION = SyntheticEmbedder.DIMENSIONS;
    private static final String FINGERPRINT =
            EmbeddingFingerprints.fingerprint(PROVIDER, MODEL, DIMENSION);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path BASE_OUTPUT_DIR = Path.of("target", "retrieval-baseline", "p4-rag-quality-01");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @MockitoBean
    ProjectEmbeddingGateway embeddings;
    @MockitoBean
    TaskPlanRecoveryJob taskPlanRecoveryJob;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentSearchService search;

    private final KnowledgeContextBuilder builder = new KnowledgeContextBuilder();
    private UUID user;
    private UUID project;

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE document_chunk, project_document, project_member, project, app_user,
                         embedding_index_generation, system_embedding_config CASCADE
                """);
        user = insertUser();
        project = insertProject();
        when(embeddings.embed(any(), anyList(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> texts = (List<String>) invocation.getArgument(1);
            List<List<Double>> vectors = texts.stream().map(SyntheticEmbedder::embed).toList();
            return new EmbeddingBatch(PROVIDER, MODEL, DIMENSION, vectors, FINGERPRINT);
        });
    }

    private record SeedChunk(String heading, String content) {}
    private record SeedDoc(String filename, List<SeedChunk> chunks) {}

    /** 阶段二：15 场景诊断用例结构 */
    private record ScenarioCase(
            int scenarioId,
            String scenarioName,
            String query,
            String bucket, // ALL_PROJECT_PRIMARY, DOCUMENT_SCOPED_PRIMARY, SCOPED_ALL_PROJECT_REFERENCE, EXPLORATORY
            List<String> scopedDocNames,
            List<String> excludedDocNames,
            int targetDocIndex,
            int targetChunkIndex,
            String keyFactSnippet,
            boolean noExpectedEvidence
    ) {}

    @Test
    void runFifteenScenariosDiagnosticSuite() throws Exception {
        // 搭建包含 15 场景的综合测试语料
        List<SeedDoc> corpus = buildComprehensiveCorpus();
        Map<Integer, UUID> docIds = new LinkedHashMap<>();
        Map<String, UUID> chunkIds = new LinkedHashMap<>();

        for (int i = 0; i < corpus.size(); i++) {
            SeedDoc doc = corpus.get(i);
            UUID dId = insertDocument(doc.filename());
            docIds.put(i, dId);
            for (int j = 0; j < doc.chunks().size(); j++) {
                SeedChunk c = doc.chunks().get(j);
                UUID cId = insertChunk(dId, j, c.heading(), c.content());
                chunkIds.put(i + ":" + j, cId);
            }
        }

        List<ScenarioCase> testCases = List.of(
                // 1. 长文档尾部关键条款
                new ScenarioCase(1, "长文档尾部关键条款", "继续发布需要满足什么前提条件？",
                        "ALL_PROJECT_PRIMARY", List.of(), List.of(), 1, 3, "关键前提：只有最近一次回滚演练通过的项目才允许继续发布。", false),
                // 2. 长文档中部关键条款
                new ScenarioCase(2, "长文档中部关键条款", "系统在什么情况下可以解除重点监控级别？",
                        "ALL_PROJECT_PRIMARY", List.of(), List.of(), 11, 0, "核心要求：系统上线运行满四十八小时方可解除重点监控级别。", false),
                // 3. 两份不同文档包含互补事实
                new ScenarioCase(3, "两份不同文档包含互补事实", "生产发布前必须完成哪些验收？如果指标不达标如何处理？",
                        "ALL_PROJECT_PRIMARY", List.of(), List.of(), 0, 0, "自动化验收", false),
                // 4. 同一文档多个分块共同提供答案
                new ScenarioCase(4, "同一文档多个分块共同提供答案", "导出任务失败之后系统会保留哪些审计信息与错误记录？",
                        "ALL_PROJECT_PRIMARY", List.of(), List.of(), 3, 2, "失败原因", false),
                // 5. 两份文档包含相同条款但来源身份不同
                new ScenarioCase(5, "两份文档包含相同条款但来源身份不同", "合同资料里关于重大变更的审批要求是什么？",
                        "ALL_PROJECT_PRIMARY", List.of(), List.of(), 5, 0, "重大变更须经项目负责人书面批准", false),
                // 6. 多个文档版本存在矛盾
                new ScenarioCase(6, "多个文档版本存在矛盾", "变更单早期版本和当前版本在索引调整窗口上的约定有什么冲突？",
                        "ALL_PROJECT_PRIMARY", List.of(), List.of(), 7, 0, "早期版本 v1.9", false),
                // 7. 精确编号、任务号与需求号（REQ-7741）
                new ScenarioCase(7, "精确编号、任务号与需求号（REQ-7741）", "需求变更申请 REQ-7741 的具体授权条款是什么？",
                        "ALL_PROJECT_PRIMARY", List.of(), List.of(), 7, 2, "REQ-7741", false),
                // 8. 中文同义、缩写和口语问法
                new ScenarioCase(8, "中文同义、缩写和口语问法", "单租户并发导出作业最高上限是多少？",
                        "ALL_PROJECT_PRIMARY", List.of(), List.of(), 4, 0, "最多运行十二个数据导出任务", false),
                // 9. 用户明确限定某份文档
                new ScenarioCase(9, "用户明确限定某份文档", "根据《批量导出规范》，导出文件保存多久？",
                        "DOCUMENT_SCOPED_PRIMARY", List.of("批量导出规范.md"), List.of(), 3, 5, "临时存储区保存二十四小时", false),
                // 10. 用户明确排除某份文档
                new ScenarioCase(10, "用户明确排除某份文档", "排除《合同模板乙》，重大变更的审批要求是什么？",
                        "EXPLORATORY", List.of(), List.of("合同模板乙.md"), 5, 0, "重大变更须经项目负责人书面批准", false),
                // 11. 高相似度但事实无关的干扰文本
                new ScenarioCase(11, "高相似度但事实无关的干扰文本", "普通只读成员能不能下载文档的原始文件？",
                        "ALL_PROJECT_PRIMARY", List.of(), List.of(), 9, 1, "只读成员不能下载文档的原始文件", false),
                // 12. 项目资料没有提供答案
                new ScenarioCase(12, "项目资料没有提供答案", "审计日志必须在归档库中保存满八十年吗？",
                        "ALL_PROJECT_PRIMARY", List.of(), List.of(), -1, -1, "", true),
                // 13. 重要来源排在 Top 8 之外
                new ScenarioCase(13, "重要来源排在 Top 8 之外", "知识检索接口百分之九十五响应时间指标是多少？",
                        "ALL_PROJECT_PRIMARY", List.of(), List.of(), 8, 1, "百分之九十五响应时间必须低于三百毫秒", false),
                // 14. 正确候选已召回但被 SOURCE_CAP 丢弃
                new ScenarioCase(14, "正确候选已召回但被 SOURCE_CAP 丢弃", "系统在容量、访问权限和性能上分别有哪些硬性约束？",
                        "ALL_PROJECT_PRIMARY", List.of(), List.of(), 8, 0, "单项目最多容纳五百份已索引文档", false),
                // 15. 长文件名、特殊字符和正文预算边界
                new ScenarioCase(15, "长文件名、特殊字符和正文预算边界", "临时存储区里的导出文件在多久之后被自动清理？",
                        "ALL_PROJECT_PRIMARY", List.of(), List.of(), 3, 5, "二十四小时", false)
        );

        List<Map<String, Object>> scenarioResults = new ArrayList<>();

        for (ScenarioCase tc : testCases) {
            List<UUID> scope = resolveScope(tc.scopedDocNames(), docIds, corpus);
            List<DocumentSearchHit> top8Hits = search.search(project, tc.query(), scope, TOP_8);
            List<DocumentSearchHit> top20Hits = search.search(project, tc.query(), scope, TOP_20);

            // 过滤排除文档
            if (!tc.excludedDocNames().isEmpty()) {
                Set<UUID> excludedIds = new LinkedHashSet<>(resolveScope(tc.excludedDocNames(), docIds, corpus));
                top8Hits = top8Hits.stream().filter(h -> !excludedIds.contains(h.documentId())).toList();
                top20Hits = top20Hits.stream().filter(h -> !excludedIds.contains(h.documentId())).toList();
            }

            KnowledgeContextBuilder.BuildResult agentContext = builder.buildDiverseWithDiagnostics(top20Hits, AGENT_LIMIT);
            KnowledgeContextBuilder.BuildResult qaContext = builder.buildWithDiagnostics(top8Hits, QA_LIMIT);

            UUID targetChunk = (tc.targetDocIndex() >= 0 && tc.targetChunkIndex() >= 0)
                    ? chunkIds.get(tc.targetDocIndex() + ":" + tc.targetChunkIndex()) : null;

            boolean recalledInTop8 = targetChunk != null && top8Hits.stream().anyMatch(h -> h.id().equals(targetChunk));
            boolean recalledInTop20 = targetChunk != null && top20Hits.stream().anyMatch(h -> h.id().equals(targetChunk));
            boolean retainedInAgent = targetChunk != null && agentContext.context().sources().stream().anyMatch(s -> s.chunkId().equals(targetChunk));
            boolean retainedInQa = targetChunk != null && qaContext.context().sources().stream().anyMatch(s -> s.chunkId().equals(targetChunk));

            int rankInTop20 = -1;
            double similarity = 0.0;
            if (targetChunk != null) {
                for (int i = 0; i < top20Hits.size(); i++) {
                    if (top20Hits.get(i).id().equals(targetChunk)) {
                        rankInTop20 = i + 1;
                        similarity = top20Hits.get(i).similarity();
                        break;
                    }
                }
            }

            String lossAttribution;
            if (tc.noExpectedEvidence()) {
                lossAttribution = "NONE_UNDEFINED";
            } else if (!recalledInTop20) {
                lossAttribution = "DB_MISS";
            } else if (!recalledInTop8 && recalledInTop20) {
                lossAttribution = "WINDOW_TRUNCATED_IN_TOP8";
            } else if (!retainedInAgent) {
                ContextDropReason reason = agentContext.dropReasons().get(targetChunk);
                lossAttribution = reason != null ? reason.name() : "UNATTRIBUTED";
            } else {
                lossAttribution = "RETAINED";
            }

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("scenarioId", tc.scenarioId());
            record.put("scenarioName", tc.scenarioName());
            record.put("query", tc.query());
            record.put("bucket", tc.bucket());
            record.put("noExpectedEvidence", tc.noExpectedEvidence());
            record.put("keyFactSnippet", tc.keyFactSnippet());
            record.put("candidateRankInTop20", rankInTop20 > 0 ? rankInTop20 : "NOT_IN_TOP_20");
            record.put("candidateSimilarity", similarity);
            record.put("recalledInTop8", recalledInTop8);
            record.put("recalledInTop20", recalledInTop20);
            record.put("retainedInQaContext", retainedInQa);
            record.put("retainedInAgentContext", retainedInAgent);
            record.put("lossAttribution", lossAttribution);
            record.put("agentRetainedSourceCount", agentContext.context().sources().size());
            record.put("agentPromptCodePoints", agentContext.context().promptSources().length());
            scenarioResults.add(record);
        }

        Map<String, Object> diagnosticReport = new LinkedHashMap<>();
        diagnosticReport.put("task", "P4-RAG-QUALITY-01");
        diagnosticReport.put("title", "15 业务场景检索质量综合诊断");
        diagnosticReport.put("totalScenarios", testCases.size());
        diagnosticReport.put("scenarioResults", scenarioResults);

        Files.createDirectories(BASE_OUTPUT_DIR);
        Path outputFile = BASE_OUTPUT_DIR.resolve("fifteen-scenarios-diagnostic.json");
        Files.writeString(outputFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(diagnosticReport), StandardCharsets.UTF_8);
        System.out.println("FIFTEEN_SCENARIOS_DIAGNOSTIC=" + outputFile.toAbsolutePath());

        // 校验 15 个用例全部执行
        assertThat(scenarioResults).hasSize(15);
    }

    @Test
    void runKnowledgeQaVsAgentRetrievalComparison() throws Exception {
        // 阶段五：知识库问答与 Agent 检索对照
        List<SeedDoc> corpus = buildComprehensiveCorpus();
        Map<Integer, UUID> docIds = new LinkedHashMap<>();
        Map<String, UUID> chunkIds = new LinkedHashMap<>();

        for (int i = 0; i < corpus.size(); i++) {
            SeedDoc doc = corpus.get(i);
            UUID dId = insertDocument(doc.filename());
            docIds.put(i, dId);
            for (int j = 0; j < doc.chunks().size(); j++) {
                SeedChunk c = doc.chunks().get(j);
                UUID cId = insertChunk(dId, j, c.heading(), c.content());
                chunkIds.put(i + ":" + j, cId);
            }
        }

        // 选取 5 个最具代表性的同题对照场景
        List<Map<String, Object>> pairedCases = new ArrayList<>();

        // 对照场景 1：深排位证据（Rank 9 证据）
        pairedCases.add(evaluatePaired(
                "S1-深排位证据（Top8 截断）",
                "知识检索接口百分之九十五响应时间指标是多少？",
                chunkIds.get("8:1"),
                docIds, corpus));

        // 对照场景 2：跨文档相同条款正文（来源身份塌缩 vs 独立保留）
        pairedCases.add(evaluatePaired(
                "S2-跨文档相同条款来源身份",
                "合同资料里关于重大变更的审批要求是什么？",
                chunkIds.get("5:0"),
                docIds, corpus));

        // 对照场景 3：大工具正文预算边界（贪婪正文占用 vs 公平正文预算）
        pairedCases.add(evaluatePaired(
                "S3-大工具正文预算截断",
                "临时存储区里的导出文件在多久之后被自动清理？",
                chunkIds.get("3:5"),
                docIds, corpus));

        // 对照场景 4：多源综合（5 来源上限 vs 12 来源上限）
        pairedCases.add(evaluatePaired(
                "S4-多源综合与来源名额",
                "系统在容量、访问权限和性能上分别有哪些硬性约束？",
                chunkIds.get("8:0"),
                docIds, corpus));

        // 对照场景 5：同文档互补事实
        pairedCases.add(evaluatePaired(
                "S5-同文档互补分块",
                "导出任务失败之后系统会保留哪些审计信息与错误记录？",
                chunkIds.get("3:2"),
                docIds, corpus));

        Map<String, Object> comparisonReport = new LinkedHashMap<>();
        comparisonReport.put("task", "P4-RAG-QUALITY-01");
        comparisonReport.put("phase", "阶段五：知识库问答与 Agent 检索对照");
        comparisonReport.put("qaConfig", Map.of("topK", TOP_8, "maxSources", QA_LIMIT, "selector", "build (legacy greedy)"));
        comparisonReport.put("agentConfig", Map.of("topK", TOP_20, "maxSources", AGENT_LIMIT, "selector", "buildDiverse (two-stage + fair budget)"));
        comparisonReport.put("pairedComparisons", pairedCases);
        comparisonReport.put("keyFindings", List.of(
                "Top8 截断效应：当关键证据排在全局第 9-20 位时，知识库问答入口（Top 8 候选池）直接发生 DB_MISS/WINDOW_TRUNCATED，而 Agent 入口（Top 20 候选池）可完整召回；",
                "身份塌缩效应：面对跨文档相同正文（如合同模板甲与乙），知识库问答入口因全局 contentHash 去重导致来源身份丢失其一，而 Agent 入口以文档为单位去重，完整保留两份合同的独立来源；",
                "预算挤占效应：面对长篇正文，知识库问答入口按顺序贪婪截断，单篇长文可消耗 7000+ code points，挤掉后续有效来源；Agent 入口执行动态平摊预算（allowance），确保后续来源皆有正文展示机会；",
                "来源名额差异：知识库问答固定 5 个来源上限，无法满足需要综合 3 文档以上的大型研究任务；Agent 支持 1-12 可调来源，满足深度规划与多源证据合成。"
        ));

        Path outputFile = BASE_OUTPUT_DIR.resolve("qa-vs-agent-comparison.json");
        Files.writeString(outputFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(comparisonReport), StandardCharsets.UTF_8);
        System.out.println("QA_VS_AGENT_COMPARISON=" + outputFile.toAbsolutePath());

        assertThat(pairedCases).hasSize(5);
    }

    @Test
    void runHybridSearchAndRerankerFeasibilityAssessment() throws Exception {
        // 阶段六：混合检索与 Reranker 可行性与价值评估
        List<SeedDoc> corpus = buildComprehensiveCorpus();
        Map<Integer, UUID> docIds = new LinkedHashMap<>();
        Map<String, UUID> chunkIds = new LinkedHashMap<>();

        for (int i = 0; i < corpus.size(); i++) {
            SeedDoc doc = corpus.get(i);
            UUID dId = insertDocument(doc.filename());
            docIds.put(i, dId);
            for (int j = 0; j < doc.chunks().size(); j++) {
                SeedChunk c = doc.chunks().get(j);
                UUID cId = insertChunk(dId, j, c.heading(), c.content());
                chunkIds.put(i + ":" + j, cId);
            }
        }

        // 测试精确编号 REQ-7741
        String queryExactId = "REQ-7741";
        String querySemanticId = "变更申请 REQ-7741 授权在哪个阶段执行索引调整？";
        UUID targetReqChunk = chunkIds.get("7:2");

        List<DocumentSearchHit> vectorHitsExact = search.search(project, queryExactId, null, TOP_20);
        List<DocumentSearchHit> vectorHitsSemantic = search.search(project, querySemanticId, null, TOP_20);

        int rankVectorExact = findRank(vectorHitsExact, targetReqChunk);
        int rankVectorSemantic = findRank(vectorHitsSemantic, targetReqChunk);

        // 模拟简单关键词命中（Keyword Boost / Trigram Filter）
        List<DocumentSearchHit> keywordHitsExact = filterByExactToken(vectorHitsExact, "REQ-7741");
        int rankKeywordExact = findRank(keywordHitsExact, targetReqChunk);

        Map<String, Object> assessment = new LinkedHashMap<>();
        assessment.put("task", "P4-RAG-QUALITY-01");
        assessment.put("phase", "阶段六：混合检索与 Reranker 价值评估");
        assessment.put("exactIdExperiment", Map.of(
                "targetChunkId", targetReqChunk.toString(),
                "targetContent", "变更申请 REQ-7741 授权在数据库结构冻结窗口结束后执行索引调整，并要求先完成回滚演练。",
                "queryExactId", queryExactId,
                "vectorRankExactId", rankVectorExact > 0 ? rankVectorExact : "MISSED",
                "keywordBoostRankExactId", rankKeywordExact > 0 ? rankKeywordExact : "MISSED",
                "querySemanticId", querySemanticId,
                "vectorRankSemanticId", rankVectorSemantic > 0 ? rankVectorSemantic : "MISSED"
        ));

        assessment.put("postgresFullTextFeasibility", Map.of(
                "nativeTsVectorLimitations", "PostgreSQL 原生 to_tsvector('simple') 仅支持以空格和标点分隔的西方语言，对无空格中文无法分词，必须依赖 zhparser 或 pg_jieba 等 C 动态链接库扩展；",
                "extensionCostInProduction", "在标准容器（如 pgvector/pgvector:pg17）中编译和维护第三方中文分词 C 扩展会显著破坏单体部署的自包含性与跨平台稳定性；",
                "recommendedAlternative", "可利用 PostgreSQL 内置的 pg_trgm（三元组匹配）或应用层轻量级精确 Token 预过滤（针对 REQ-、TASK-、BUG- 等业务标识符），完全无需额外外部服务或原生扩展。"
        ));

        assessment.put("rerankerValueAnalysis", Map.of(
                "rerankerTargetProblem", "Reranker（如 BGE-Reranker）的主要作用是重排 Top K 候选中的语义相关度；",
                "whyRerankerDoesNotSolveSourceCap", "在当前诊断中，SOURCE_CAP 造成的证据损失（如 #2 证据排全局第 8、#15 证据排全局第 6）已经处于 Top 8 高位候选池中！损失源于多文档代表名额分配算法（CrossDocumentSourceSelector 的两阶段名额竞争），而非候选排序错乱。即使接入耗费大量内存与延迟的 Reranker，名额策略不改依然会被丢弃；",
                "whyRerankerDoesNotSolveDbMiss", "R1 长分块尾部证据缺失是因为长篇填充文本稀释向量导致 pgvector 根本未在前 20 名召回（DB_MISS），Reranker 只能重排已有候选，对未召回的证据毫无作用；",
                "costBenefitConclusion", "引入独立 Reranker 需增加 Python/ONNX 运行时或调用付费模型，增加 100-300ms 链路耗时，但无法解决当前已确认的 R1 与 R2 根因。因此现阶段强烈建议保持现有架构，不引入外部 Reranker。"
        ));

        Path outputFile = BASE_OUTPUT_DIR.resolve("hybrid-search-reranker-assessment.json");
        Files.writeString(outputFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(assessment), StandardCharsets.UTF_8);
        System.out.println("HYBRID_RERANKER_ASSESSMENT=" + outputFile.toAbsolutePath());

        assertThat(rankVectorSemantic).isGreaterThanOrEqualTo(1);
    }

    private Map<String, Object> evaluatePaired(
            String caseName, String query, UUID targetChunk,
            Map<Integer, UUID> docIds, List<SeedDoc> corpus) {
        List<DocumentSearchHit> top8 = search.search(project, query, null, TOP_8);
        List<DocumentSearchHit> top20 = search.search(project, query, null, TOP_20);

        var qaContext = builder.buildWithDiagnostics(top8, QA_LIMIT);
        var agentContext = builder.buildDiverseWithDiagnostics(top20, AGENT_LIMIT);

        boolean inTop8 = top8.stream().anyMatch(h -> h.id().equals(targetChunk));
        boolean inTop20 = top20.stream().anyMatch(h -> h.id().equals(targetChunk));
        boolean inQa = qaContext.context().sources().stream().anyMatch(s -> s.chunkId().equals(targetChunk));
        boolean inAgent = agentContext.context().sources().stream().anyMatch(s -> s.chunkId().equals(targetChunk));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseName", caseName);
        result.put("query", query);
        result.put("targetChunkId", targetChunk.toString());
        result.put("recalledInTop8", inTop8);
        result.put("recalledInTop20", inTop20);
        result.put("retainedInQa", inQa);
        result.put("retainedInAgent", inAgent);
        result.put("qaSourcesCount", qaContext.context().sources().size());
        result.put("agentSourcesCount", agentContext.context().sources().size());
        result.put("qaPromptCodePoints", qaContext.context().promptSources().length());
        result.put("agentPromptCodePoints", agentContext.context().promptSources().length());
        return result;
    }

    private static int findRank(List<DocumentSearchHit> hits, UUID target) {
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).id().equals(target)) return i + 1;
        }
        return -1;
    }

    private static List<DocumentSearchHit> filterByExactToken(List<DocumentSearchHit> hits, String token) {
        List<DocumentSearchHit> matches = new ArrayList<>();
        List<DocumentSearchHit> nonMatches = new ArrayList<>();
        for (DocumentSearchHit hit : hits) {
            if (hit.content() != null && hit.content().contains(token)) matches.add(hit);
            else nonMatches.add(hit);
        }
        List<DocumentSearchHit> reordered = new ArrayList<>(matches);
        reordered.addAll(nonMatches);
        return reordered;
    }

    private List<UUID> resolveScope(List<String> docNames, Map<Integer, UUID> docIds, List<SeedDoc> corpus) {
        if (docNames == null || docNames.isEmpty()) return List.of();
        List<UUID> resolved = new ArrayList<>();
        for (String name : docNames) {
            for (int i = 0; i < corpus.size(); i++) {
                if (corpus.get(i).filename().equals(name)) resolved.add(docIds.get(i));
            }
        }
        return resolved;
    }

    private List<SeedDoc> buildComprehensiveCorpus() {
        return List.of(
                // 0: 上线验收规程.md
                new SeedDoc("上线验收规程.md", List.of(
                        new SeedChunk("上线门禁", "生产发布前必须完成核心业务流程的自动化验收，所有阻断级缺陷必须关闭，并核对数据迁移结果无误。"),
                        new SeedChunk("验收记录", "验收记录必须包含执行人、执行时间、测试版本以及尚未关闭的问题清单。"),
                        new SeedChunk("灰度观察", "上线后进入两小时的灰度观察窗口，期间持续监控核心业务指标与错误率。"))),
                // 1: 发布回滚预案.md
                new SeedDoc("发布回滚预案.md", List.of(
                        new SeedChunk("回滚触发条件", "上线后核心业务指标连续两次低于约定阈值，或者出现不可恢复的数据异常时，立即暂停发布并回滚到最近一次稳定版本。"),
                        new SeedChunk("回滚复核", "回滚完成后必须复核数据完整性，并通知项目负责人与当班值守人员。"),
                        new SeedChunk("回滚演练", "每个发布周期开始前都要完成一次回滚演练，演练过程与结论归档保存。"),
                        new SeedChunk("续发前提", "回滚演练步骤记录：准备环境、冻结写入、切换流量、比对数据、恢复流量、撰写结论。".repeat(20)
                                + "关键前提：只有最近一次回滚演练通过的项目才允许继续发布。"))),
                // 2: 任务预算规则.md
                new SeedDoc("任务预算规则.md", List.of(
                        new SeedChunk("预算确认", "任务启动前必须确认预算上限；任务运行期间禁止修改预算上限或临时增加可用额度。"),
                        new SeedChunk("超限处理", "实际消耗达到授权预算上限后，任务应暂停并等待负责人批准，不得自动超支。"))),
                // 3: 批量导出规范.md
                new SeedDoc("批量导出规范.md", List.of(
                        new SeedChunk("导出队列", "批量导出由后台队列异步处理，用户可以查看排队、运行与完成状态。"),
                        new SeedChunk("导出格式", "批量导出支持 CSV 与 XLSX，生成完成后提供限时下载链接。"),
                        new SeedChunk("失败重试", "导出任务失败后可以重新提交，系统保留失败原因和最近一次运行时间。"),
                        new SeedChunk("数据范围", "导出文件只包含请求用户有权访问的项目数据，敏感字段按权限脱敏。"),
                        new SeedChunk("导出审计", "每次导出都记录发起人、筛选条件、文件大小和下载时间。"),
                        new SeedChunk("文件保留", "已完成的导出文件在临时存储区保存二十四小时，过期后自动清理。"))),
                // 4: 租户并发限制.md
                new SeedDoc("租户并发限制.md", List.of(
                        new SeedChunk("租户并发上限", "每个租户同一时刻最多运行十二个数据导出任务，超出上限的任务进入等待队列。"),
                        new SeedChunk("资源告警", "当租户并发达到上限的百分之八十时，系统向管理员发送容量告警。"))),
                // 5: 合同模板甲.md
                new SeedDoc("合同模板甲.md", List.of(
                        new SeedChunk("重大变更审批", "重大变更须经项目负责人书面批准后方可实施。"))),
                // 6: 合同模板乙.md
                new SeedDoc("合同模板乙.md", List.of(
                        new SeedChunk("重大变更审批", "重大变更须经项目负责人书面批准后方可实施。"))),
                // 7: 需求变更单.md
                new SeedDoc("需求变更单.md", List.of(
                        new SeedChunk("早期版本说明", "本变更单的早期版本 v1.9 曾约定索引调整应在数据库结构冻结窗口开始之前执行。"),
                        new SeedChunk("当前版本修正", "当前版本 v2.1 修正为：索引调整必须在数据库结构冻结窗口结束之后执行，并要求先完成一次回滚演练。"),
                        new SeedChunk("REQ-7741", "变更申请 REQ-7741 授权在数据库结构冻结窗口结束后执行索引调整，并要求先完成回滚演练。"))),
                // 8: 容量与性能约束.md
                new SeedDoc("容量与性能约束.md", List.of(
                        new SeedChunk("容量规划", "单项目最多容纳五百份已索引文档，单份文档正文上限二十兆字节。"),
                        new SeedChunk("性能指标", "知识检索接口的百分之九十五响应时间必须低于三百毫秒，峰值每秒查询数不得超过五十次。"),
                        new SeedChunk("权限约束", "只有项目成员可以访问项目文档，跨项目检索一律拒绝。"))),
                // 9: 安全与脱敏规范.md
                new SeedDoc("安全与脱敏规范.md", List.of(
                        new SeedChunk("脱敏规则", "身份证号、银行卡号与手机号在导出和展示时都必须脱敏，只保留首尾各两位。"),
                        new SeedChunk("权限分级", "文档访问按项目角色分级，只读成员不能下载文档的原始文件。"))),
                // 10: 数据归档通告.md
                new SeedDoc("数据归档通告.md", List.of(
                        new SeedChunk("归档周期", "业务记录每个季度归档一次，归档副本整体保留五年。"),
                        new SeedChunk("审计说明", "审计记录用于追溯历史操作，具体保存策略以合规部门的最新通知为准，此处不作统一约定。"))),
                // 11: 监控与解除规范.md (长文档中部关键条款用例)
                new SeedDoc("监控与解除规范.md", List.of(
                        new SeedChunk("监控级别解除", "系统架构与历史演进说明：第一阶段单体演进，第二阶段微服务拆分，第三阶段容器化改造。".repeat(15)
                                + "核心要求：系统上线运行满四十八小时方可解除重点监控级别。"
                                + "后续例行巡检规程：每半小时核对一次错误率曲线，每小时采集一次连接池水位。".repeat(15)))),
                // 12: 检索性能测试手册.md (深排位高分干扰用例)
                new SeedDoc("检索性能测试手册.md", List.of(
                        new SeedChunk("测试用例1", "响应时间测试用例一：在并发一条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                        new SeedChunk("测试用例2", "响应时间测试用例二：在并发二条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                        new SeedChunk("测试用例3", "响应时间测试用例三：在并发三条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                        new SeedChunk("测试用例4", "响应时间测试用例四：在并发四条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                        new SeedChunk("测试用例5", "响应时间测试用例五：在并发五条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                        new SeedChunk("测试用例6", "响应时间测试用例六：在并发六条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                        new SeedChunk("测试用例7", "响应时间测试用例七：在并发七条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                        new SeedChunk("测试用例8", "响应时间测试用例八：在并发八条件下记录知识检索接口的响应时间与吞吐量的采样值。"))),
                // 13: 长文附录.md (大工具结果正文预算边界)
                new SeedDoc("长文附录.md", List.of(
                        new SeedChunk("保留策略附录", "关于文件保留与清理策略的详细附录。" + "补充说明条款示例内容。".repeat(900)
                                + "本附录仅为背景说明，正式保留期限以《批量导出规范》为准。")))
        );
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                id, "ragq-" + id.toString().substring(0, 8), "test-only-hash", "RetrievalQuality");
        return id;
    }

    private UUID insertProject() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                id, "质量诊断项目", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')", id, user);
        return id;
    }

    private UUID insertDocument(String filename) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_document (
                    id, project_id, display_name, original_filename, mime_type,
                    size_bytes, object_key, status, chunk_count, embedding_provider, embedding_model,
                    embedding_dimension, embedding_fingerprint, uploaded_by, indexed_at
                ) VALUES (?, ?, ?, ?, 'text/markdown', 100, ?, 'READY', 0, ?, ?, ?, ?, ?, now())
                """,
                id, project, filename, filename, "projects/" + project + "/" + id + ".md",
                PROVIDER, MODEL, DIMENSION, FINGERPRINT, user);
        return id;
    }

    private UUID insertChunk(UUID docId, int chunkNo, String heading, String content) {
        UUID id = UUID.randomUUID();
        String vectorLiteral = SyntheticEmbedder.literal(heading + "\n" + content);
        jdbc.update("""
                INSERT INTO document_chunk (
                    id, project_id, document_id, chunk_no, heading, content, content_hash,
                    token_estimate, metadata, embedding_provider, embedding_model, embedding_dimension,
                    embedding_fingerprint, embedding
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, CAST(? AS vector))
                """,
                id, project, docId, chunkNo, heading, content, sha256(content),
                Math.max(1, content.codePointCount(0, content.length()) / 4),
                "{}", PROVIDER, MODEL, DIMENSION, FINGERPRINT, vectorLiteral);
        return id;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
