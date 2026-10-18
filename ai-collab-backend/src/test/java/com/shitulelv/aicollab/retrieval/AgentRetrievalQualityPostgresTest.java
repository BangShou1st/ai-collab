package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfig;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfigRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.retrieval.RetrievalCoverage.ExpectedEvidence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-RAG-01C 阶段 A/B：<b>真实语义检索质量基线</b>（本地 Ollama 向量 + 一次性 Testcontainers pgvector）。
 *
 * <p>与既有 {@link RetrievalCoveragePostgresTest} 的分工：那一份用 {@link SyntheticEmbedder}
 * 做<b>确定性机械回归</b>（管线、指纹/代际、丢弃归因、预算），本份用生产
 * {@link ProjectEmbeddingGateway} 连本地 Ollama 取<b>真实语义向量</b>，回答
 * “真实模型下全项目范围证据召回、跨文档覆盖、来源身份保留、尾部截断、无答案行为如何”。</p>
 *
 * <p>合成数字与真实数字<b>分开统计</b>，绝不混算；本份一切召回率只代表这 16 条手写中文
 * 查询与小型合成语料，不代表生产语义质量，更不代表生成答案质量。</p>
 *
 * <p>整类由 {@code AI_REAL_OLLAMA_RETRIEVAL=true} 门控：未启用时整类跳过（不启动容器、
 * 不访问 Ollama）；启用时逐用例测量并写入 {@code target/retrieval-baseline/2026-10-18/}。
 * 不读写生产数据库，不覆盖仓库里既有的验收快照。</p>
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
@EnabledIfEnvironmentVariable(named = "AI_REAL_OLLAMA_RETRIEVAL", matches = "true")
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class AgentRetrievalQualityPostgresTest {
    private static final int TOP_8 = 8;
    private static final int TOP_20 = 20;
    private static final int AGENT_LIMIT = KnowledgeContextBuilder.MAX_SOURCES_LIMIT;
    private static final String PROVIDER = "OLLAMA";
    private static final String DEFAULT_MODEL = "qwen3-embedding:0.6b";

    /** 诊断 JSON 只落 Maven target/，绝不写回仓库里既有的验收快照。 */
    static final Path EVIDENCE_PATH = Path.of("target", "retrieval-baseline", "2026-10-18",
            "real-retrieval-quality.json");

    private record ChunkSeed(String heading, String content) {}

    private record DocSeed(String filename, List<ChunkSeed> chunks) {}

    /** 一条期望证据：docIndex/chunkNo 指向下面 CORPUS 的位置，label 仅供人读。 */
    private record Evidence(int docIndex, int chunkNo, String label) {}

    /**
     * 一个固定用例：覆盖 D-RAG-01C 要求的 16 类场景之一（或组合）。
     *
     * @param scope     指定检索文档范围（docIndex）；为空表示“全项目检索”
     * @param tailMarker 只对“关键条件位于长分块尾部”用例设置：断言该尾标记是否随正文保留
     * @param noEvidence true 表示语料中本就没有可回答该问题的证据（应如实判定未定义，不补造）
     */
    private record Case(
            String name, String scenario, String query,
            List<Integer> scope, List<Evidence> expected,
            String tailMarker, boolean noEvidence) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Map<String, Object>> EVIDENCE = new LinkedHashMap<>();

    /** 合成语料：小型中文资料，覆盖上线/回滚、预算、导出、并发、合同、版本、容量权限性能、脱敏、归档。 */
    private static final List<DocSeed> CORPUS = List.of(
            new DocSeed("上线验收规程.md", List.of(
                    new ChunkSeed("上线门禁",
                            "生产发布前必须完成核心业务流程的自动化验收，所有阻断级缺陷必须关闭，并核对数据迁移结果无误。"),
                    new ChunkSeed("验收记录",
                            "验收记录必须包含执行人、执行时间、测试版本以及尚未关闭的问题清单。"),
                    new ChunkSeed("灰度观察",
                            "上线后进入两小时的灰度观察窗口，期间持续监控核心业务指标与错误率。"))),
            new DocSeed("发布回滚预案.md", List.of(
                    new ChunkSeed("回滚触发条件",
                            "上线后核心业务指标连续两次低于约定阈值，或者出现不可恢复的数据异常时，立即暂停发布并回滚到最近一次稳定版本。"),
                    new ChunkSeed("回滚复核",
                            "回滚完成后必须复核数据完整性，并通知项目负责人与当班值守人员。"),
                    new ChunkSeed("回滚演练",
                            "每个发布周期开始前都要完成一次回滚演练，演练过程与结论归档保存。"),
                    new ChunkSeed("续发前提", longTail()))),
            new DocSeed("任务预算规则.md", List.of(
                    new ChunkSeed("预算确认",
                            "任务启动前必须确认预算上限；任务运行期间禁止修改预算上限或临时增加可用额度。"),
                    new ChunkSeed("超限处理",
                            "实际消耗达到授权预算上限后，任务应暂停并等待负责人批准，不得自动超支。"))),
            new DocSeed("批量导出规范.md", List.of(
                    new ChunkSeed("导出队列",
                            "批量导出由后台队列异步处理，用户可以查看排队、运行与完成状态。"),
                    new ChunkSeed("导出格式",
                            "批量导出支持 CSV 与 XLSX，生成完成后提供限时下载链接。"),
                    new ChunkSeed("失败重试",
                            "导出任务失败后可以重新提交，系统保留失败原因和最近一次运行时间。"),
                    new ChunkSeed("数据范围",
                            "导出文件只包含请求用户有权访问的项目数据，敏感字段按权限脱敏。"),
                    new ChunkSeed("导出审计",
                            "每次导出都记录发起人、筛选条件、文件大小和下载时间。"),
                    new ChunkSeed("文件保留",
                            "已完成的导出文件在临时存储区保存二十四小时，过期后自动清理。"))),
            new DocSeed("租户并发限制.md", List.of(
                    new ChunkSeed("租户并发上限",
                            "每个租户同一时刻最多运行十二个数据导出任务，超出上限的任务进入等待队列。"),
                    new ChunkSeed("资源告警",
                            "当租户并发达到上限的百分之八十时，系统向管理员发送容量告警。"))),
            new DocSeed("合同模板甲.md", List.of(
                    new ChunkSeed("重大变更审批", "重大变更须经项目负责人书面批准后方可实施。"))),
            new DocSeed("合同模板乙.md", List.of(
                    new ChunkSeed("重大变更审批", "重大变更须经项目负责人书面批准后方可实施。"))),
            new DocSeed("需求变更单.md", List.of(
                    new ChunkSeed("早期版本说明",
                            "本变更单的早期版本 v1.9 曾约定索引调整应在数据库结构冻结窗口开始之前执行。"),
                    new ChunkSeed("当前版本修正",
                            "当前版本 v2.1 修正为：索引调整必须在数据库结构冻结窗口结束之后执行，并要求先完成一次回滚演练。"),
                    new ChunkSeed("REQ-7741",
                            "变更申请 REQ-7741 授权在数据库结构冻结窗口结束后执行索引调整，并要求先完成回滚演练。"))),
            new DocSeed("容量与性能约束.md", List.of(
                    new ChunkSeed("容量规划",
                            "单项目最多容纳五百份已索引文档，单份文档正文上限二十兆字节。"),
                    new ChunkSeed("性能指标",
                            "知识检索接口的百分之九十五响应时间必须低于三百毫秒，峰值每秒查询数不得超过五十次。"),
                    new ChunkSeed("权限约束",
                            "只有项目成员可以访问项目文档，跨项目检索一律拒绝。"))),
            new DocSeed("安全与脱敏规范.md", List.of(
                    new ChunkSeed("脱敏规则",
                            "身份证号、银行卡号与手机号在导出和展示时都必须脱敏，只保留首尾各两位。"),
                    new ChunkSeed("权限分级",
                            "文档访问按项目角色分级，只读成员不能下载文档的原始文件。"))),
            new DocSeed("数据归档通告.md", List.of(
                    new ChunkSeed("归档周期",
                            "业务记录每个季度归档一次，归档副本整体保留五年。"),
                    new ChunkSeed("审计说明",
                            "审计记录用于追溯历史操作，具体保存策略以合规部门的最新通知为准，此处不作统一约定。"))),
            // 深排位用例的“高分噪声”：多条与查询高度相似但不含真正要求的候选。
            new DocSeed("检索性能测试手册.md", List.of(
                    new ChunkSeed("测试用例1", "响应时间测试用例一：在并发一条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                    new ChunkSeed("测试用例2", "响应时间测试用例二：在并发二条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                    new ChunkSeed("测试用例3", "响应时间测试用例三：在并发三条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                    new ChunkSeed("测试用例4", "响应时间测试用例四：在并发四条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                    new ChunkSeed("测试用例5", "响应时间测试用例五：在并发五条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                    new ChunkSeed("测试用例6", "响应时间测试用例六：在并发六条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                    new ChunkSeed("测试用例7", "响应时间测试用例七：在并发七条件下记录知识检索接口的响应时间与吞吐量的采样值。"),
                    new ChunkSeed("测试用例8", "响应时间测试用例八：在并发八条件下记录知识检索接口的响应时间与吞吐量的采样值。"))),
            // 大工具结果用例：一条超长正文，用于观察正文预算截断。
            new DocSeed("长文附录.md", List.of(
                    new ChunkSeed("保留策略附录",
                            "关于文件保留与清理策略的详细附录。" + "补充说明条款示例内容。".repeat(900)
                                    + "本附录仅为背景说明，正式保留期限以《批量导出规范》为准。"))));

    private static String longTail() {
        String padding = "回滚演练步骤记录：准备环境、冻结写入、切换流量、比对数据、恢复流量、撰写结论。".repeat(20);
        return padding + "关键前提：只有最近一次回滚演练通过的项目才允许继续发布。";
    }

    /** 16 个固定用例，覆盖任务要求的语义场景。 */
    private static final List<Case> CASES = List.of(
            new Case("上线与回退条件", "两份文档分别提供上线和回退条件",
                    "正式发布前上线验收需要满足哪些条件？上线后指标不达标该如何处理？",
                    null, List.of(new Evidence(0, 0, "上线门禁"), new Evidence(1, 0, "回滚触发条件")), null, false),
            new Case("容量权限性能约束", "多文档分别提供容量、权限与性能约束",
                    "系统在容量、访问权限和性能上分别有哪些硬性约束？",
                    null, List.of(new Evidence(8, 0, "容量规划"), new Evidence(8, 1, "性能指标"),
                            new Evidence(3, 3, "数据范围权限")), null, false),
            new Case("相同审批条款来源身份", "相同审批条款出现在不同合同，来源不能混淆",
                    "合同资料里关于重大变更的审批要求是什么？",
                    null, List.of(new Evidence(5, 0, "合同模板甲"), new Evidence(6, 0, "合同模板乙")), null, false),
            new Case("相互矛盾的版本说明", "多文档存在相互矛盾的版本说明",
                    "索引调整应该在哪一个阶段执行？不同版本的说法是否有冲突？",
                    null, List.of(new Evidence(7, 0, "早期版本 v1.9"), new Evidence(7, 1, "当前版本 v2.1")), null, false),
            new Case("深排位证据", "正确证据位于 Top8 之后、Top20 以内",
                    "知识检索接口对响应时间和吞吐量有什么具体指标要求？",
                    null, List.of(new Evidence(8, 1, "性能指标")), null, false),
            new Case("同文档互补事实", "同一文档不同 chunk 的互补事实",
                    "导出任务失败之后应该如何处理？系统会保留哪些信息？",
                    null, List.of(new Evidence(3, 2, "失败重试"), new Evidence(3, 4, "导出审计")), null, false),
            new Case("长分块尾部条件", "关键条件位于长分块的尾部",
                    "继续发布需要满足什么前提条件？",
                    null, List.of(new Evidence(1, 3, "续发前提")), "回滚演练通过", false),
            new Case("中文同义与口语描述", "中文别名、缩写及同义词",
                    "一个租户同时最多能并行跑多少个数据导出作业？",
                    null, List.of(new Evidence(4, 0, "租户并发上限")), null, false),
            new Case("指定文档范围检索", "用户只询问指定文档",
                    "已完成的导出文件会保留多久？",
                    List.of(3), List.of(new Evidence(3, 5, "文件保留")), null, false),
            new Case("全项目范围检索", "用户未指定文档，需全项目搜索",
                    "已完成的导出文件会保留多久？",
                    null, List.of(new Evidence(3, 5, "文件保留")), null, false),
            new Case("不存在的事实", "明确不存在的事实，应拒绝补造",
                    "审计日志必须保留多少年？",
                    null, List.of(), null, true),
            new Case("高相似无关片段", "与当前问题无关的高相似片段",
                    "普通成员能不能直接下载文档的原始文件？",
                    null, List.of(new Evidence(9, 1, "权限分级")), null, false),
            new Case("多轮追问沿用来源", "多轮追问，沿用已确认的来源与用户约束",
                    "两份合同模板中关于重大变更的审批要求是否完全一致？",
                    null, List.of(new Evidence(5, 0, "合同模板甲"), new Evidence(6, 0, "合同模板乙")), null, false),
            new Case("大工具结果预算", "大型工具结果可能消耗上下文预算",
                    "临时存储区里的导出文件在多久之后被自动清理？",
                    null, List.of(new Evidence(3, 5, "文件保留")), null, false),
            new Case("多源综合", "父子研究完成后综合多个独立来源",
                    "上线、灰度观察和回滚三个环节分别由哪份规程约束？",
                    null, List.of(new Evidence(0, 0, "上线门禁"), new Evidence(0, 2, "灰度观察"),
                            new Evidence(1, 0, "回滚触发条件")), null, false));

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
    }

    @Test
    void realEmbeddingQualityAcrossTheFixedCaseSet() throws Exception {
        String model = System.getenv().getOrDefault("AI_OLLAMA_SMOKE_MODEL", DEFAULT_MODEL);
        OffsetDateTime now = OffsetDateTime.now();
        String probeFingerprint = EmbeddingFingerprints.fingerprint(PROVIDER, model, 0);
        ProjectEmbeddingGateway probeGateway = ollamaGateway(model, 0, probeFingerprint, null, now);
        long probeStarted = System.nanoTime();
        EmbeddingBatch probe = probeGateway.embed(project, List.of("本地向量维度检查"),
                EmbeddingProgressListener.NONE);
        long probeLatencyMs = elapsedMillis(probeStarted);
        int dimension = probe.dimension();

        String fingerprint = EmbeddingFingerprints.fingerprint(PROVIDER, model, dimension);
        UUID generationId = UUID.randomUUID();
        ProjectEmbeddingGateway gateway = ollamaGateway(model, dimension, fingerprint, generationId, now);

        List<ChunkSeed> flatChunks = new ArrayList<>();
        for (DocSeed doc : CORPUS) flatChunks.addAll(doc.chunks());
        List<String> inputs = new ArrayList<>(flatChunks.stream().map(ChunkSeed::content).toList());
        inputs.addAll(CASES.stream().map(Case::query).toList());
        long embeddingStarted = System.nanoTime();
        EmbeddingBatch allVectors = gateway.embed(project, inputs, EmbeddingProgressListener.NONE);
        long embeddingLatencyMs = elapsedMillis(embeddingStarted);

        assertThat(allVectors.provider()).isEqualTo(PROVIDER);
        assertThat(allVectors.model()).isEqualTo(model);
        assertThat(allVectors.dimension()).isEqualTo(dimension);
        assertThat(allVectors.fingerprint()).isEqualTo(fingerprint);

        insertOllamaGeneration(model, dimension, fingerprint, generationId);

        Map<Integer, UUID> documentIds = new LinkedHashMap<>();
        for (int i = 0; i < CORPUS.size(); i++) {
            documentIds.put(i, insertOllamaDocument(CORPUS.get(i).filename(), model, dimension, fingerprint));
        }
        Map<String, UUID> chunkIds = new LinkedHashMap<>();
        int cursor = 0;
        for (int docIndex = 0; docIndex < CORPUS.size(); docIndex++) {
            List<ChunkSeed> chunks = CORPUS.get(docIndex).chunks();
            for (int chunkNo = 0; chunkNo < chunks.size(); chunkNo++) {
                ChunkSeed chunk = chunks.get(chunkNo);
                UUID chunkId = insertOllamaChunk(documentIds.get(docIndex), chunkNo, chunk.heading(),
                        chunk.content(), model, dimension, fingerprint, generationId,
                        allVectors.vectors().get(cursor++));
                chunkIds.put(key(docIndex, chunkNo), chunkId);
            }
        }

        Map<String, List<Double>> queryVectors = new LinkedHashMap<>();
        for (int index = 0; index < CASES.size(); index++) {
            queryVectors.put(CASES.get(index).query(), allVectors.vectors().get(flatChunks.size() + index));
        }
        when(embeddings.embed(any(), anyList(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> input = (List<String>) invocation.getArgument(1);
            List<List<Double>> vectors = input.stream().map(queryVectors::get).toList();
            return new EmbeddingBatch(PROVIDER, model, dimension, vectors, fingerprint, generationId);
        });

        List<Map<String, Object>> measuredCases = new ArrayList<>();
        for (Case testCase : CASES) {
            measuredCases.add(runCase(testCase, documentIds, chunkIds));
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evaluation", "real local Ollama embeddings on disposable Testcontainers pgvector database");
        evidence.put("baseline", "D-RAG-01C agent retrieval quality, dev @ 6386c411");
        evidence.put("model", model);
        evidence.put("dimension", dimension);
        evidence.put("fingerprint", fingerprint);
        evidence.put("generationId", generationId.toString());
        evidence.put("probeLatencyMs", probeLatencyMs);
        evidence.put("corpusAndQueryEmbeddingLatencyMs", embeddingLatencyMs);
        evidence.put("documentCount", CORPUS.size());
        evidence.put("indexingChunkCount", flatChunks.size());
        evidence.put("caseCount", measuredCases.size());
        evidence.put("cases", measuredCases);
        Map<String, Object> summary = summarize(measuredCases);
        evidence.put("summary", summary);
        Files.createDirectories(EVIDENCE_PATH.getParent());
        Files.writeString(EVIDENCE_PATH,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence), StandardCharsets.UTF_8);
        System.out.println("AGENT_RETRIEVAL_QUALITY_EVIDENCE=" + EVIDENCE_PATH.toAbsolutePath());

        assertThat(measuredCases).hasSize(CASES.size());
    }

    // ---- 单用例测量 ----

    private Map<String, Object> runCase(Case testCase, Map<Integer, UUID> documentIds,
                                        Map<String, UUID> chunkIds) {
        List<UUID> scopeIds = testCase.scope() == null ? null
                : testCase.scope().stream().map(documentIds::get).toList();
        List<ExpectedEvidence> expected = testCase.expected().stream()
                .map(evidence -> new ExpectedEvidence(
                        documentIds.get(evidence.docIndex()), chunkIds.get(key(
                        evidence.docIndex(), evidence.chunkNo())), evidence.label()))
                .toList();

        // 指定范围/全项目 × Top8/Top20 四次候选检索，分别计时。
        Timed scopedTop8 = searchWithScope(testCase.query(), scopeIds, TOP_8);
        Timed scopedTop20 = searchWithScope(testCase.query(), scopeIds, TOP_20);
        Timed allTop8 = searchWithScope(testCase.query(), null, TOP_8);
        Timed allTop20 = searchWithScope(testCase.query(), null, TOP_20);

        var legacyResult = builder.buildWithDiagnostics(scopedTop8.hits(), AGENT_LIMIT);
        var diverseResult = builder.buildDiverseWithDiagnostics(scopedTop20.hits(), AGENT_LIMIT);

        Map<String, Object> scopedLegacy = RetrievalCoverage.measure(
                TOP_8, AGENT_LIMIT, expected, scopedTop8.hits(), legacyResult);
        Map<String, Object> scopedDiverse = RetrievalCoverage.measure(
                TOP_20, AGENT_LIMIT, expected, scopedTop20.hits(), diverseResult);
        Map<String, Object> allLegacy = RetrievalCoverage.measure(
                TOP_8, AGENT_LIMIT, expected, allTop8.hits(), builder.buildWithDiagnostics(allTop8.hits(), AGENT_LIMIT));
        Map<String, Object> allDiverse = RetrievalCoverage.measure(
                TOP_20, AGENT_LIMIT, expected, allTop20.hits(),
                builder.buildDiverseWithDiagnostics(allTop20.hits(), AGENT_LIMIT));

        KnowledgeContext diverseContext = diverseResult.context();

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("case", testCase.name());
        item.put("scenario", testCase.scenario());
        item.put("query", testCase.query());
        item.put("scope", testCase.scope() == null ? "ALL_PROJECT" : "DOCUMENT_SCOPED");
        item.put("expectedEvidence", testCase.expected().stream().map(evidence -> Map.of(
                "label", evidence.label(),
                "documentId", documentIds.get(evidence.docIndex()).toString(),
                "chunkId", chunkIds.get(key(evidence.docIndex(), evidence.chunkNo())).toString()))
                .toList());
        item.put("noExpectedEvidence", testCase.noEvidence());
        item.put("expectedEvidenceCount", scopedDiverse.get("expectedEvidenceCount"));
        item.put("retrievalStatus", scopedDiverse.get("retrievalStatus"));
        item.put("notRecalledInTopK", scopedDiverse.get("notRecalledInTopK"));
        item.put("recallAt8Scoped", scopedLegacy.get("retrievalRecall"));
        item.put("recallAt20Scoped", scopedDiverse.get("retrievalRecall"));
        item.put("recallAt8AllProject", allLegacy.get("retrievalRecall"));
        item.put("recallAt20AllProject", allDiverse.get("retrievalRecall"));
        item.put("retentionLegacyScoped", scopedLegacy.get("contextEvidenceRetention"));
        item.put("retentionDiverseScoped", scopedDiverse.get("contextEvidenceRetention"));
        item.put("retentionLegacyAllProject", allLegacy.get("contextEvidenceRetention"));
        item.put("retentionDiverseAllProject", allDiverse.get("contextEvidenceRetention"));
        item.put("documentCoverageDiverse", scopedDiverse.get("documentCoverage"));
        item.put("candidateRankOfExpected", rankOfExpected(scopedTop20.hits(), expected));
        item.put("unexpectedSourcesRetained", scopedDiverse.get("unexpectedSourcesRetained"));
        item.put("droppedByContext", scopedDiverse.get("droppedByContext"));
        item.put("dropReasons", dropReasons(scopedDiverse));
        item.put("retainedSourceCount", diverseContext.sources().size());
        item.put("contextPromptCodePoints", promptCodePoints(diverseContext));
        item.put("latencyMsTop8", scopedTop8.latencyMs());
        item.put("latencyMsTop20", scopedTop20.latencyMs());
        item.put("tailMarker", testCase.tailMarker());
        item.put("tailPreserved", tailPreserved(diverseContext, testCase, chunkIds));
        item.put("retainedSources", retainedSources(diverseContext, expected));
        EVIDENCE.put(testCase.name(), item);

        // ---- L1 硬性不变量（必须通过） ----
        // 1. 丢弃归因互斥且合计恒等于 droppedByContext：不得出现无归因的静默丢弃。
        assertThat(sumDropReasons(item))
                .as("%s：丢弃归因之和必须等于 droppedByContext", testCase.name())
                .isEqualTo(scopedDiverse.get("droppedByContext"));
        // 2. 正文预算硬上限 8000 code point：公平预算不得超限。
        assertThat(promptCodePoints(diverseContext))
                .as("%s：上下文正文预算不得超过 8000 code point", testCase.name())
                .isLessThanOrEqualTo(8000);
        // 3. 指定文档范围时，召回候选不得越界（ACL/范围隔离）。
        if (scopeIds != null) {
            assertThat(scopedTop20.hits()).extracting(DocumentSearchHit::documentId)
                    .as("%s：指定范围检索不得返回范围外文档", testCase.name())
                    .allMatch(scopeIds::contains);
        }
        // 4. 保留来源的身份必须与证据一致：不得张冠李戴。
        for (KnowledgeSource source : diverseContext.sources()) {
            expected.stream()
                    .filter(evidence -> evidence.chunkId().equals(source.chunkId()))
                    .forEach(evidence -> assertThat(source.documentId())
                            .as("%s：保留来源 %s 的 documentId 必须与其 chunkId 归属一致", testCase.name(), evidence.label())
                            .isEqualTo(evidence.documentId()));
        }
        // 5. 无期望证据时必须判定为未定义并如实留空，不得伪装成召回失败或补造来源。
        if (testCase.noEvidence()) {
            assertThat(scopedDiverse.get("retrievalStatus"))
                    .as("%s：没有可回答证据时必须判为未定义", testCase.name())
                    .isEqualTo(RetrievalCoverage.UNDEFINED_NO_EXPECTED_EVIDENCE);
            assertThat(RetrievalCoverage.classify(testCase.name(), scopedLegacy, scopedDiverse).get("verdict"))
                    .isEqualTo(RetrievalCoverage.UNDEFINED_NO_EXPECTED_EVIDENCE);
        }
        return item;
    }

    /**
     * 相同审批条款用例的专门断言：两个不同文档的相同正文必须保留为<b>两个独立来源身份</b>，
     * 不得被全局内容哈希合并成一条。证人：旧入口（全局去重）会塌缩，跨文档入口不会。
     */
    @Test
    void identicalApprovalClauseKeepsDistinctCrossDocumentIdentity() throws Exception {
        String model = System.getenv().getOrDefault("AI_OLLAMA_SMOKE_MODEL", DEFAULT_MODEL);
        OffsetDateTime now = OffsetDateTime.now();
        EmbeddingBatch probe = ollamaGateway(model, 0,
                EmbeddingFingerprints.fingerprint(PROVIDER, model, 0), null, now)
                .embed(project, List.of("维度检查"), EmbeddingProgressListener.NONE);
        int dimension = probe.dimension();
        String fingerprint = EmbeddingFingerprints.fingerprint(PROVIDER, model, dimension);
        UUID generationId = UUID.randomUUID();
        ProjectEmbeddingGateway gateway = ollamaGateway(model, dimension, fingerprint, generationId, now);

        String query = "合同资料里关于重大变更的审批要求是什么？";
        EmbeddingBatch batch = gateway.embed(project,
                List.of(CORPUS.get(5).chunks().get(0).content(),
                        CORPUS.get(6).chunks().get(0).content(), query),
                EmbeddingProgressListener.NONE);
        insertOllamaGeneration(model, dimension, fingerprint, generationId);
        UUID docA = insertOllamaDocument("合同模板甲.md", model, dimension, fingerprint);
        UUID docB = insertOllamaDocument("合同模板乙.md", model, dimension, fingerprint);
        UUID chunkA = insertOllamaChunk(docA, 0, "重大变更审批",
                CORPUS.get(5).chunks().get(0).content(), model, dimension, fingerprint, generationId, batch.vectors().get(0));
        UUID chunkB = insertOllamaChunk(docB, 0, "重大变更审批",
                CORPUS.get(6).chunks().get(0).content(), model, dimension, fingerprint, generationId, batch.vectors().get(1));
        when(embeddings.embed(any(), anyList(), any()))
                .thenReturn(new EmbeddingBatch(PROVIDER, model, dimension,
                        List.of(batch.vectors().get(2)), fingerprint, generationId));

        List<DocumentSearchHit> candidates = search.search(project, query, null, TOP_20);
        var legacy = builder.buildWithDiagnostics(candidates, AGENT_LIMIT);
        var diverse = builder.buildDiverseWithDiagnostics(candidates, AGENT_LIMIT);

        // 跨文档入口：两个不同 documentId/chunkId 都在，来源身份不被合并。
        assertThat(diverse.context().sources()).extracting(KnowledgeSource::documentId)
                .contains(docA, docB);
        assertThat(diverse.context().sources()).extracting(KnowledgeSource::chunkId)
                .contains(chunkA, chunkB);
        // 身份不混淆：每个来源的 documentId 与其 chunkId 归属一致。
        for (KnowledgeSource source : diverse.context().sources()) {
            if (source.chunkId().equals(chunkA)) assertThat(source.documentId()).isEqualTo(docA);
            if (source.chunkId().equals(chunkB)) assertThat(source.documentId()).isEqualTo(docB);
        }
    }

    /**
     * 索引代际隔离：已退休代际的分块绝不被当前代际检索命中；
     * 反向对照：不带代际的检索也看不到任何带代际的分块——隔离是双向的。
     */
    @Test
    void retiredGenerationChunksAreNeverSearchedWithRealEmbeddings() throws Exception {
        String model = System.getenv().getOrDefault("AI_OLLAMA_SMOKE_MODEL", DEFAULT_MODEL);
        OffsetDateTime now = OffsetDateTime.now();
        EmbeddingBatch probe = ollamaGateway(model, 0,
                EmbeddingFingerprints.fingerprint(PROVIDER, model, 0), null, now)
                .embed(project, List.of("维度检查"), EmbeddingProgressListener.NONE);
        int dimension = probe.dimension();
        String fingerprint = EmbeddingFingerprints.fingerprint(PROVIDER, model, dimension);
        UUID activeGeneration = UUID.randomUUID();
        UUID retiredGeneration = UUID.randomUUID();
        ProjectEmbeddingGateway gateway = ollamaGateway(model, dimension, fingerprint, activeGeneration, now);

        String query = "代际隔离标记 omega 的导出上限";
        EmbeddingBatch batch = gateway.embed(project,
                List.of("代际隔离标记 omega 的导出上限为二十", "代际隔离标记 omega 的导出上限为二十", query),
                EmbeddingProgressListener.NONE);
        UUID configId = insertOllamaGeneration(model, dimension, fingerprint, activeGeneration);
        // 退休代际也必须有自己的 embedding_index_generation 行：document_chunk.generation_id 有外键约束，
        // 隔离由“检索只按当前代际过滤”保证，而不是靠写入一个悬空的 generation_id。
        jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state) VALUES (?,?,'RETIRED')",
                retiredGeneration, configId);
        UUID document = insertOllamaDocument("代际.md", model, dimension, fingerprint);
        insertOllamaChunk(document, 0, "旧代", "代际隔离标记 omega 的导出上限为二十", model, dimension,
                fingerprint, retiredGeneration, batch.vectors().get(0));
        UUID activeChunk = insertOllamaChunk(document, 1, "新代", "代际隔离标记 omega 的导出上限为二十", model,
                dimension, fingerprint, activeGeneration, batch.vectors().get(1));
        when(embeddings.embed(any(), anyList(), any()))
                .thenReturn(new EmbeddingBatch(PROVIDER, model, dimension,
                        List.of(batch.vectors().get(2)), fingerprint, activeGeneration));

        List<DocumentSearchHit> hits = search.search(project, query, null, TOP_20);
        assertThat(hits).extracting(DocumentSearchHit::id).contains(activeChunk);
        assertThat(hits).hasSize(1);
    }

    // ---- 汇总 ----

    private static Map<String, Object> summarize(List<Map<String, Object>> cases) {
        int expected = 0;
        int recalledScoped = 0;
        int retainedDiverse = 0;
        int recalledAll = 0;
        int retainedAll = 0;
        int undefined = 0;
        int tailPreservedCount = 0;
        int tailCaseCount = 0;
        int totalPrompt = 0;
        long latencyTop8 = 0;
        long latencyTop20 = 0;
        for (Map<String, Object> item : cases) {
            int itemExpected = number(item.get("expectedEvidenceCount"));
            if (itemExpected == 0) undefined++;
            expected += itemExpected;
            recalledScoped += (itemExpected - number(item.get("notRecalledInTopK")));
            retainedDiverse += (itemExpected - number(item.get("notRecalledInTopK"))
                    - number(item.get("droppedByContext")));
            Object allRecalled = item.get("recallAt20AllProject");
            if (allRecalled instanceof Number number) {
                recalledAll += (int) Math.round(number.doubleValue() * itemExpected);
            }
            Object allRetained = item.get("retentionDiverseAllProject");
            if (allRetained instanceof Number number) {
                retainedAll += (int) Math.round(number.doubleValue() * itemExpected);
            }
            if (item.get("tailMarker") != null) {
                tailCaseCount++;
                if (Boolean.TRUE.equals(item.get("tailPreserved"))) tailPreservedCount++;
            }
            totalPrompt += number(item.get("contextPromptCodePoints"));
            latencyTop8 += number(item.get("latencyMsTop8"));
            latencyTop20 += number(item.get("latencyMsTop20"));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseCount", cases.size());
        summary.put("undefinedCaseCount", undefined);
        summary.put("expectedEvidenceCount", expected);
        summary.put("recallScoped", ratio(recalledScoped, expected));
        summary.put("retentionDiverseScoped", ratio(retainedDiverse, expected));
        summary.put("recallAllProject", ratio(recalledAll, expected));
        summary.put("retentionDiverseAllProject", ratio(retainedAll, expected));
        summary.put("tailCaseCount", tailCaseCount);
        summary.put("tailPreservedCount", tailPreservedCount);
        summary.put("averageContextPromptCodePoints", ratio(totalPrompt, cases.size()));
        summary.put("averageLatencyMsTop8", ratioOf(latencyTop8, cases.size()));
        summary.put("averageLatencyMsTop20", ratioOf(latencyTop20, cases.size()));
        return summary;
    }

    // ---- 辅助 ----

    private record Timed(List<DocumentSearchHit> hits, long latencyMs) {}

    private Timed searchWithScope(String query, List<UUID> scope, int topK) {
        long started = System.nanoTime();
        List<DocumentSearchHit> hits = search.search(project, query, scope, topK);
        return new Timed(hits, elapsedMillis(started));
    }

    private static Map<String, Object> dropReasons(Map<String, Object> measured) {
        Map<String, Object> reasons = new LinkedHashMap<>();
        reasons.put("crossDocumentDedupLoss", measured.get("crossDocumentDedupLoss"));
        reasons.put("intraDocumentDedupLoss", measured.get("intraDocumentDedupLoss"));
        reasons.put("sourceCapDrop", measured.get("sourceCapDrop"));
        reasons.put("budgetDrop", measured.get("budgetDrop"));
        reasons.put("belowSimilarityThresholdDrop", measured.get("belowSimilarityThresholdDrop"));
        reasons.put("emptyBodyDrop", measured.get("emptyBodyDrop"));
        reasons.put("unattributedDrop", measured.get("unattributedDrop"));
        return reasons;
    }

    private static int sumDropReasons(Map<String, Object> item) {
        @SuppressWarnings("unchecked")
        Map<String, Object> reasons = (Map<String, Object>) item.get("dropReasons");
        int total = 0;
        for (Object value : reasons.values()) total += number(value);
        return total;
    }

    private static int number(Object value) {
        return value instanceof Number numberValue ? numberValue.intValue() : 0;
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : Math.round(((double) numerator / denominator) * 10_000d) / 10_000d;
    }

    private static Double ratioOf(long numerator, int denominator) {
        return denominator == 0 ? null : Math.round(((double) numerator / denominator) * 10_000d) / 10_000d;
    }

    private static List<Map<String, Object>> rankOfExpected(
            List<DocumentSearchHit> candidates, List<ExpectedEvidence> expected) {
        List<Map<String, Object>> ranks = new ArrayList<>();
        for (ExpectedEvidence evidence : expected) {
            Integer rank = null;
            double similarity = 0;
            for (int index = 0; index < candidates.size(); index++) {
                if (candidates.get(index).id().equals(evidence.chunkId())) {
                    rank = index + 1;
                    similarity = candidates.get(index).similarity();
                    break;
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", evidence.label());
            item.put("candidateRank", rank);
            item.put("similarity", Math.round(similarity * 10_000d) / 10_000d);
            ranks.add(item);
        }
        return ranks;
    }

    private static List<Map<String, Object>> retainedSources(
            KnowledgeContext context, List<ExpectedEvidence> expected) {
        List<Map<String, Object>> retained = new ArrayList<>();
        for (KnowledgeSource source : context.sources()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", source.rank());
            item.put("chunkId", source.chunkId().toString());
            item.put("documentId", source.documentId().toString());
            item.put("filename", source.originalFilename());
            item.put("heading", source.heading());
            item.put("similarity", Math.round(source.similarity() * 10_000d) / 10_000d);
            item.put("expectedEvidence", expected.stream().anyMatch(e -> e.chunkId().equals(source.chunkId())));
            retained.add(item);
        }
        return retained;
    }

    private static Object tailPreserved(
            KnowledgeContext context, Case testCase, Map<String, UUID> chunkIds) {
        if (testCase.tailMarker() == null) return null;
        for (Evidence evidence : testCase.expected()) {
            UUID chunkId = chunkIds.get(key(evidence.docIndex(), evidence.chunkNo()));
            Optional<KnowledgeSource> source = context.sources().stream()
                    .filter(item -> item.chunkId().equals(chunkId)).findFirst();
            if (source.isPresent()) return source.get().content().contains(testCase.tailMarker());
        }
        return false;
    }

    private static int promptCodePoints(KnowledgeContext context) {
        return context.promptSources().codePointCount(0, context.promptSources().length());
    }

    private static String key(int docIndex, int chunkNo) {
        return docIndex + ":" + chunkNo;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    // ---- 种子数据（与既有真实基线一致的真实 schema 写入方式） ----

    private static ProjectEmbeddingGateway ollamaGateway(
            String model, int dimension, String fingerprint, UUID generationId, OffsetDateTime now) {
        SystemEmbeddingConfigRepository configRepository = mock(SystemEmbeddingConfigRepository.class);
        SystemEmbeddingConfig config = new SystemEmbeddingConfig(
                UUID.randomUUID(), PROVIDER, "http://127.0.0.1:11434", "/v1/embeddings", null,
                model, dimension, 32, fingerprint, true, now, now, generationId);
        when(configRepository.findActive()).thenReturn(Optional.of(config));
        return new ProjectEmbeddingGateway(configRepository, mock(ModelSecretCipher.class));
    }

    private UUID insertOllamaGeneration(String model, int dimension, String fingerprint, UUID generationId) {
        UUID configId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO system_embedding_config(id,provider,base_url,api_path,model_name,dimensions,
                  batch_size,fingerprint,enabled)
                VALUES (?,'OLLAMA','http://127.0.0.1:11434','/v1/embeddings',?,?,32,?,FALSE)
                """, configId, model, dimension, fingerprint);
        jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state) VALUES (?,?,'RETIRED')",
                generationId, configId);
        jdbc.update("UPDATE system_embedding_config SET generation_id=? WHERE id=?", generationId, configId);
        return configId;
    }

    private UUID insertOllamaDocument(String filename, String model, int dimension, String fingerprint) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,
                  size_bytes,object_key,status,chunk_count,embedding_provider,embedding_model,
                  embedding_dimension,embedding_fingerprint,uploaded_by,indexed_at)
                VALUES (?,?,?,?,'text/markdown',100,?,'READY',0,'OLLAMA',?,?,?, ?,now())
                """, id, project, filename, filename, "projects/" + project + "/" + id + ".md",
                model, dimension, fingerprint, user);
        return id;
    }

    private UUID insertOllamaChunk(UUID document, int chunkNo, String heading, String content,
                                   String model, int dimension, String fingerprint, UUID generationId,
                                   List<Double> vector) throws Exception {
        UUID id = UUID.randomUUID();
        String metadata = JSON.writeValueAsString(Map.of("embeddingGeneration", generationId.toString()));
        jdbc.update("""
                INSERT INTO document_chunk(id,project_id,document_id,chunk_no,heading,content,
                  content_hash,token_estimate,metadata,embedding_provider,embedding_model,
                  embedding_dimension,embedding_fingerprint,embedding,generation_id)
                VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,CAST(? AS vector),?)
                """, id, project, document, chunkNo, heading, content, sha256(content),
                Math.max(1, content.codePointCount(0, content.length()) / 4), metadata,
                PROVIDER, model, dimension, fingerprint, vector.toString(), generationId);
        return id;
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
                id, "检索质量基线", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')", id, user);
        return id;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算分块内容哈希", exception);
        }
    }

    @AfterAll
    static void reportLocation() {
        System.out.println("AGENT_RETRIEVAL_QUALITY_CASES=" + EVIDENCE.size());
    }
}
