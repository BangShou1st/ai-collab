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
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p>合成数字与真实数字<b>分开统计</b>，绝不混算；本份一切召回率只代表这组手写中文
 * 查询与小型合成语料，不代表生产语义质量，更不代表生成答案质量。</p>
 *
 * <p><b>范围维度（第二轮修正）</b>：把 {@code ALL_PROJECT}（用户未指定文档）与
 * {@code DOCUMENT_SCOPED}（用户问题明确点名某份文档）拆成两个<b>互不混算</b>的评估维度。
 * 只有问题本身明确要求指定文档时才传 documentIds，绝不把参考答案里的文档 ID 当作用户范围；
 * 指定范围问题可额外跑一次全项目检索作为对照，但对照结果单独归入
 * {@code scopedAllProjectReference}，不并入任一样本主口径。</p>
 *
 * <p>整类由 {@code AI_REAL_OLLAMA_RETRIEVAL=true} 门控：未启用时整类跳过（不启动容器、
 * 不访问 Ollama）；启用时逐用例测量并写入 {@code target/retrieval-baseline/2026-10-19/}。
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

    // ---- B2：版本化模型质量回归门槛（2026-10-19 修正口径后的新基线） ----
    // 基线（qwen3-embedding:0.6b，本合成语料；范围修正后的主口径）：
    //   ALL_PROJECT_PRIMARY: 14 题 / 22 项证据；Recall@8=Recall@20=21/22，Retention@8/20=19/22，Coverage@20=17/18
    //   DOCUMENT_SCOPED_PRIMARY: 6 题 / 6 项证据；Recall@8/20=6/6，Retention@8/20=6/6，Coverage@20=6/6
    //   SCOPED_ALL_PROJECT_REFERENCE: 6 题 / 6 项证据；配对反事实，明确不计入上述主口径。
    // 门槛按小样本可解释的离散步长设置，允许每个主指标再损失一项证据/一个文档覆盖：
    //   ALL_PROJECT_PRIMARY 最低 20/22 recall、18/22 retention、16/18 coverage；DOCUMENT_SCOPED_PRIMARY 最低 5/6。
    // 这是“模型相关的质量回归门槛”，只在 AI_REAL_OLLAMA_RETRIEVAL=true 时生效；环境不可用则整类跳过。
    private static final double ALL_PROJECT_RECALL_AT20_FLOOR = 20d / 22d;
    private static final double ALL_PROJECT_RETENTION_AT20_FLOOR = 18d / 22d;
    private static final double ALL_PROJECT_COVERAGE_AT20_FLOOR = 16d / 18d;
    // Stored ratios round to four decimals, so use the representable 5/6 floor.
    private static final double DOCUMENT_SCOPED_RECALL_AT20_FLOOR = 0.8333d;
    private static final double DOCUMENT_SCOPED_RETENTION_AT20_FLOOR = 0.8333d;
    private static final double DOCUMENT_SCOPED_COVERAGE_AT20_FLOOR = 0.8333d;

    /** 诊断 JSON 只落 Maven target/，绝不写回仓库里既有的验收快照（P4-RAG-QUALITY-01：新路径，不覆盖 2026-10-19 旧证据）。 */
    static final Path EVIDENCE_PATH = Path.of("target", "retrieval-baseline", "p4-rag-quality-01",
            "real-retrieval-quality-scoped.json");

    private record ChunkSeed(String heading, String content) {}

    private record DocSeed(String filename, List<ChunkSeed> chunks) {}

    /** 一条期望证据：docIndex/chunkNo 指向下面 CORPUS 的位置，label 仅供人读。 */
    private record Evidence(int docIndex, int chunkNo, String label) {}

    /**
     * 一个固定用例。
     *
     * <p>{@code scope} 只在<b>用户问题本身明确点名某份文档</b>时才设置（DOCUMENT_SCOPED）；
     * 为空表示用户没有指定文档，只能按全项目范围评估（ALL_PROJECT）。绝不为了让 Recall 好看，
     * 把参考答案里出现过的文档 ID 反填成“用户指定范围”。</p>
     *
     * @param scope      用户明确指定的检索文档范围（docIndex）；为空表示“全项目检索”
     * @param tailMarker 只对“关键条件位于长分块尾部”用例设置：断言该尾标记是否随正文保留
     * @param noEvidence true 表示语料中本就没有可回答该问题的证据（应如实判定未定义，不补造）
     */
    private record Case(
            String name, String scenario, String query,
            List<Integer> scope, List<Evidence> expected,
            String tailMarker, boolean noEvidence) {
        boolean documentScoped() {
            return scope != null;
        }
    }

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
            new Case("指定文档范围检索", "用户问题明确点名《批量导出规范》",
                    "根据《批量导出规范》，已完成的导出文件会在临时存储区保留多久？",
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
                            new Evidence(1, 0, "回滚触发条件")), null, false),
            // ---- 指定文档范围（DOCUMENT_SCOPED）对照：问题明确点名某份文档，具有真实业务意义 ----
            new Case("指定-回滚触发条件", "用户点名《发布回滚预案》询问回滚条件",
                    "根据《发布回滚预案》，满足什么条件时必须暂停发布并回滚？",
                    List.of(1), List.of(new Evidence(1, 0, "回滚触发条件")), null, false),
            new Case("指定-只读下载权限", "用户点名《安全与脱敏规范》询问下载权限",
                    "按照《安全与脱敏规范》，只读成员能不能下载文档的原始文件？",
                    List.of(9), List.of(new Evidence(9, 1, "权限分级")), null, false),
            new Case("指定-性能指标", "用户点名《容量与性能约束》询问性能指标",
                    "《容量与性能约束》里，知识检索接口的响应时间与吞吐量指标分别是多少？",
                    List.of(8), List.of(new Evidence(8, 1, "性能指标")), null, false),
            new Case("指定-运行期预算额度", "用户点名《任务预算规则》询问运行期额度",
                    "在《任务预算规则》中，任务运行期间可以临时增加预算额度吗？",
                    List.of(2), List.of(new Evidence(2, 0, "预算确认")), null, false),
            new Case("指定-合同审批主体", "用户点名《合同模板甲》询问审批主体",
                    "只在《合同模板甲》里，重大变更需要由谁批准？",
                    List.of(5), List.of(new Evidence(5, 0, "合同模板甲")), null, false));

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

        List<CaseMeasurement> measurements = new ArrayList<>();
        for (Case testCase : CASES) {
            measurements.add(runCase(testCase, documentIds, chunkIds));
        }
        List<Map<String, Object>> measuredCases = measurements.stream()
                .map(CaseMeasurement::item).toList();

        // 主口径按用户原始问题的范围划分。指定文档问题的全项目检索只进入配对反事实对照。
        List<CaseMeasurement> allProjectPrimaryCases = measurements.stream()
                .filter(measurement -> "ALL_PROJECT".equals(measurement.item().get("scope")))
                .toList();
        List<Map<String, Object>> allProjectPrimaryTop8 = allProjectPrimaryCases.stream()
                .map(CaseMeasurement::allProjectTop8).toList();
        List<Map<String, Object>> allProjectPrimaryTop20 = allProjectPrimaryCases.stream()
                .map(CaseMeasurement::allProjectTop20).toList();
        List<Map<String, Object>> documentScopedPrimaryTop8 = measurements.stream()
                .filter(measurement -> measurement.scopedTop8() != null)
                .map(CaseMeasurement::scopedTop8).toList();
        List<Map<String, Object>> documentScopedPrimaryTop20 = measurements.stream()
                .filter(measurement -> measurement.scopedTop20() != null)
                .map(CaseMeasurement::scopedTop20).toList();
        // 六条指定范围问题移除 scope 后的配对全项目搜索，只作为反事实参考。
        List<Map<String, Object>> scopedReferenceTop8 = measurements.stream()
                .filter(measurement -> measurement.scopedTop8() != null)
                .map(CaseMeasurement::allProjectTop8).toList();
        List<Map<String, Object>> scopedReferenceTop20 = measurements.stream()
                .filter(measurement -> measurement.scopedTop20() != null)
                .map(CaseMeasurement::allProjectTop20).toList();
        // 历史 20 样本全项目汇总保留为探索指标，其中包含上面六条指定范围问题的反事实搜索。
        List<Map<String, Object>> allProjectCounterfactualTop8 = measurements.stream()
                .map(CaseMeasurement::allProjectTop8).toList();
        List<Map<String, Object>> allProjectCounterfactualTop20 = measurements.stream()
                .map(CaseMeasurement::allProjectTop20).toList();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("allProjectPrimary", summarizeDimension(allProjectPrimaryTop8, allProjectPrimaryTop20));
        summary.put("documentScopedPrimary", summarizeDimension(documentScopedPrimaryTop8, documentScopedPrimaryTop20));
        summary.put("scopedAllProjectReference", summarizeDimension(scopedReferenceTop8, scopedReferenceTop20));
        summary.put("allProjectCounterfactualIncludingDocumentScopedPrompts",
                summarizeDimension(allProjectCounterfactualTop8, allProjectCounterfactualTop20));
        summary.put("sourceCapStrategyApproximateAllProjectPrimary",
                summarizeSourceCapStrategy(measuredCases, "ALL_PROJECT", "sourceCapStrategyApproximate"));
        summary.put("sourceCapStrategyApproximateDocumentScopedPrimary",
                summarizeSourceCapStrategy(measuredCases, "DOCUMENT_SCOPED", "sourceCapStrategyApproximate"));
        summary.put("sourceCapStrategyApproximateScopedAllProjectReference",
                summarizeSourceCapStrategy(measuredCases, "DOCUMENT_SCOPED", "sourceCapStrategyAllProjectReferenceApproximate"));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evaluation", "real local Ollama embeddings on disposable Testcontainers pgvector database");
        evidence.put("baseline", "D-RAG-01C agent retrieval quality round 2 (scope-corrected), dev @ 2026-10-19");
        evidence.put("scopeDimensions", "ALL_PROJECT_PRIMARY contains only questions without a user-named document; "
                + "DOCUMENT_SCOPED_PRIMARY contains only explicitly scoped questions; "
                + "SCOPED_ALL_PROJECT_REFERENCE is the paired all-project counterfactual and is excluded from both primary buckets");
        evidence.put("model", model);
        evidence.put("dimension", dimension);
        evidence.put("fingerprint", fingerprint);
        evidence.put("generationId", generationId.toString());
        evidence.put("probeLatencyMs", probeLatencyMs);
        evidence.put("corpusAndQueryEmbeddingLatencyMs", embeddingLatencyMs);
        evidence.put("documentCount", CORPUS.size());
        evidence.put("indexingChunkCount", flatChunks.size());
        evidence.put("caseCount", measuredCases.size());
        evidence.put("allProjectPrimaryCaseCount", allProjectPrimaryTop20.size());
        evidence.put("documentScopedPrimaryCaseCount", documentScopedPrimaryTop20.size());
        evidence.put("scopedAllProjectReferenceCaseCount", scopedReferenceTop20.size());
        evidence.put("allProjectCounterfactualCaseCount", allProjectCounterfactualTop20.size());
        evidence.put("allProjectCounterfactualNote",
                "该 20 样本探索汇总包含六条用户已指定文档问题的全项目反事实搜索，不代表独立全项目问题集。");
        evidence.put("sourceCapStrategySemantics",
                "A 使用生产 CrossDocumentSourceSelector，且装配预算通过 buildDiverse parity test；"
                        + "B 是测试侧相关度保护候选选择模拟；C 是测试侧保守折中候选选择模拟（保留 E6 覆盖保证同时消除弱代表占位）。"
                        + "B 和 C 的结果属于近似实验指标，不代表生产上下文提升。");
        evidence.put("cases", measuredCases);
        evidence.put("summary", summary);
        Files.createDirectories(EVIDENCE_PATH.getParent());
        Files.writeString(EVIDENCE_PATH,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence), StandardCharsets.UTF_8);
        System.out.println("AGENT_RETRIEVAL_QUALITY_EVIDENCE=" + EVIDENCE_PATH.toAbsolutePath());

        assertThat(measuredCases).hasSize(CASES.size());
        assertThat(documentScopedPrimaryTop20).as("必须有指定文档范围样本")
                .hasSize((int) CASES.stream().filter(Case::documentScoped).count());
        assertThat(documentScopedPrimaryTop20.size()).as("指定文档范围样本数必须在 3~5 之上").isGreaterThanOrEqualTo(3);
        assertThat(allProjectPrimaryTop20).as("ALL_PROJECT_PRIMARY 只含用户未指定文档的用例")
                .hasSize((int) CASES.stream().filter(testCase -> !testCase.documentScoped()).count());
        assertThat(scopedReferenceTop20).as("指定范围问题的全项目配对对照数")
                .hasSize(documentScopedPrimaryTop20.size());

        assertModelQualityGate(summary, "allProjectPrimary", 14, 22,
                ALL_PROJECT_RECALL_AT20_FLOOR, ALL_PROJECT_RETENTION_AT20_FLOOR,
                ALL_PROJECT_COVERAGE_AT20_FLOOR);
        assertModelQualityGate(summary, "documentScopedPrimary", 6, 6,
                DOCUMENT_SCOPED_RECALL_AT20_FLOOR, DOCUMENT_SCOPED_RETENTION_AT20_FLOOR,
                DOCUMENT_SCOPED_COVERAGE_AT20_FLOOR);
        assertDimensionCounts(summary, "scopedAllProjectReference", 6, 6);
    }

    /**
     * B2 的模型质量回归门槛：断言某一范围维度的样本数、Recall@20、Context Retention 与 Document Coverage
     * 不低于版本化基线向下留出容忍度后的下限。门槛为 null（期望证据为空）时明确失败，不静默放过。
     */
    private static void assertModelQualityGate(Map<String, Object> summary, String dimension,
                                               int expectedSamples, int expectedEvidence, double recallFloor,
                                               double retentionFloor, double coverageFloor) {
        @SuppressWarnings("unchecked")
        Map<String, Object> bucket = (Map<String, Object>) summary.get(dimension);
        assertThat(bucket).as("%s 维度汇总必须存在", dimension).isNotNull();
        assertThat(number(bucket.get("sampleCount")))
                .as("%s：样本数必须等于该范围的用例数（不得把相同查询重复测量成两组）", dimension)
                .isEqualTo(expectedSamples);
        assertThat(number(bucket.get("expectedEvidenceCount")))
                .as("%s：期望证据数必须等于固定语料事实数", dimension)
                .isEqualTo(expectedEvidence);
        Object recall = bucket.get("recallAt20");
        Object retention = bucket.get("retentionDiverseAt20");
        assertThat(recall).as("%s：Recall@20 必须有定义", dimension).isInstanceOf(Number.class);
        assertThat(retention).as("%s：Context Retention 必须有定义", dimension).isInstanceOf(Number.class);
        assertThat(((Number) recall).doubleValue())
                .as("%s：Recall@20 不得低于版本化质量门槛 %.2f", dimension, recallFloor)
                .isGreaterThanOrEqualTo(recallFloor);
        assertThat(((Number) retention).doubleValue())
                .as("%s：Context Retention 不得低于版本化质量门槛 %.2f", dimension, retentionFloor)
                .isGreaterThanOrEqualTo(retentionFloor);
        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) bucket.get("documentCoverageAt20");
        assertThat(coverage).as("%s：Document Coverage 必须可计算", dimension).isNotNull();
        assertThat(((Number) coverage.get("retainedRatio")).doubleValue())
                .as("%s：Document Coverage（保留侧）不得低于版本化质量门槛 %.2f", dimension, coverageFloor)
                .isGreaterThanOrEqualTo(coverageFloor);
    }

    @SuppressWarnings("unchecked")
    private static void assertDimensionCounts(Map<String, Object> summary, String dimension,
                                              int expectedSamples, int expectedEvidence) {
        Map<String, Object> bucket = (Map<String, Object>) summary.get(dimension);
        assertThat(bucket).as("%s 维度汇总必须存在", dimension).isNotNull();
        assertThat(number(bucket.get("sampleCount"))).isEqualTo(expectedSamples);
        assertThat(number(bucket.get("expectedEvidenceCount"))).isEqualTo(expectedEvidence);
    }

    // ---- 单用例测量 ----

    /** 一个用例的两种范围维度的测量结果：全项目口径恒有；指定范围口径仅 DOCUMENT_SCOPED 用例有。 */
    private record CaseMeasurement(
            Map<String, Object> item,
            Map<String, Object> allProjectTop8,
            Map<String, Object> allProjectTop20,
            Map<String, Object> scopedTop8,
            Map<String, Object> scopedTop20) {}

    private CaseMeasurement runCase(Case testCase, Map<Integer, UUID> documentIds,
                                    Map<String, UUID> chunkIds) {
        List<UUID> scopeIds = testCase.scope() == null ? null
                : testCase.scope().stream().map(documentIds::get).toList();
        List<ExpectedEvidence> expected = testCase.expected().stream()
                .map(evidence -> new ExpectedEvidence(
                        documentIds.get(evidence.docIndex()), chunkIds.get(key(
                        evidence.docIndex(), evidence.chunkNo())), evidence.label()))
                .toList();

        // 全项目口径恒测一次（每个用例都贡献一份全项目样本）。
        Timed allTop8 = searchWithScope(testCase.query(), null, TOP_8);
        Timed allTop20 = searchWithScope(testCase.query(), null, TOP_20);
        var allLegacyResult = builder.buildWithDiagnostics(allTop8.hits(), AGENT_LIMIT);
        var allDiverseResult = builder.buildDiverseWithDiagnostics(allTop20.hits(), AGENT_LIMIT);
        Map<String, Object> allLegacy = RetrievalCoverage.measure(
                TOP_8, AGENT_LIMIT, expected, allTop8.hits(), allLegacyResult);
        Map<String, Object> allDiverse = RetrievalCoverage.measure(
                TOP_20, AGENT_LIMIT, expected, allTop20.hits(), allDiverseResult);

        // 指定范围口径：只有问题本身点名文档时才跑（并额外保留一份全项目对照，不与主口径混算）。
        Timed scopedTop8 = null;
        Timed scopedTop20 = null;
        Map<String, Object> scopedLegacy = null;
        Map<String, Object> scopedDiverse = null;
        KnowledgeContext primaryContext = allDiverseResult.context();
        Timed primaryTop20 = allTop20;
        Map<String, Object> primaryDiverse = allDiverse;
        if (testCase.documentScoped()) {
            scopedTop8 = searchWithScope(testCase.query(), scopeIds, TOP_8);
            scopedTop20 = searchWithScope(testCase.query(), scopeIds, TOP_20);
            var scopedLegacyResult = builder.buildWithDiagnostics(scopedTop8.hits(), AGENT_LIMIT);
            var scopedDiverseResult = builder.buildDiverseWithDiagnostics(scopedTop20.hits(), AGENT_LIMIT);
            scopedLegacy = RetrievalCoverage.measure(
                    TOP_8, AGENT_LIMIT, expected, scopedTop8.hits(), scopedLegacyResult);
            scopedDiverse = RetrievalCoverage.measure(
                    TOP_20, AGENT_LIMIT, expected, scopedTop20.hits(), scopedDiverseResult);
            primaryContext = scopedDiverseResult.context();
            primaryTop20 = scopedTop20;
            primaryDiverse = scopedDiverse;
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("case", testCase.name());
        item.put("scenario", testCase.scenario());
        item.put("query", testCase.query());
        item.put("scope", testCase.documentScoped() ? "DOCUMENT_SCOPED" : "ALL_PROJECT");
        // 阶段 D：A 与生产选择器对齐；B 是测试侧选择器模拟，明确标为近似上下文收益。
        item.put("sourceCapStrategyApproximate", compareSourceCapStrategies(primaryTop20.hits(), expected));
        item.put("expectedEvidence", testCase.expected().stream().map(evidence -> Map.of(
                "label", evidence.label(),
                "documentId", documentIds.get(evidence.docIndex()).toString(),
                "chunkId", chunkIds.get(key(evidence.docIndex(), evidence.chunkNo())).toString()))
                .toList());
        item.put("noExpectedEvidence", testCase.noEvidence());
        // 主口径 = 指定范围（若指定）否则全项目。
        item.put("expectedEvidenceCount", primaryDiverse.get("expectedEvidenceCount"));
        item.put("retrievalStatus", primaryDiverse.get("retrievalStatus"));
        item.put("notRecalledInTopK", primaryDiverse.get("notRecalledInTopK"));
        item.put("recallAt8", scopedLegacy != null ? scopedLegacy.get("retrievalRecall")
                : allLegacy.get("retrievalRecall"));
        item.put("recallAt20", primaryDiverse.get("retrievalRecall"));
        item.put("retentionLegacy", scopedLegacy != null ? scopedLegacy.get("contextEvidenceRetention")
                : allLegacy.get("contextEvidenceRetention"));
        item.put("retentionDiverse", primaryDiverse.get("contextEvidenceRetention"));
        item.put("documentCoverage", primaryDiverse.get("documentCoverage"));
        item.put("candidateRankOfExpected", rankOfExpected(primaryTop20.hits(), expected));
        item.put("unexpectedSourcesRetained", primaryDiverse.get("unexpectedSourcesRetained"));
        item.put("droppedByContext", primaryDiverse.get("droppedByContext"));
        item.put("dropReasons", dropReasons(primaryDiverse));
        item.put("retainedSourceCount", primaryContext.sources().size());
        item.put("contextPromptCodePoints", promptCodePoints(primaryContext));
        item.put("latencyMsTop8", (scopedTop8 != null ? scopedTop8 : allTop8).latencyMs());
        item.put("latencyMsTop20", primaryTop20.latencyMs());
        item.put("tailMarker", testCase.tailMarker());
        item.put("tailPreserved", tailPreserved(primaryContext, testCase, chunkIds));
        item.put("retainedSources", retainedSources(primaryContext, expected));
        if (testCase.documentScoped()) {
            // 对照：同一指定范围问题的全项目搜索，单独记录、不与主口径混算。
            item.put("referenceAllProjectRecallAt20", allDiverse.get("retrievalRecall"));
            item.put("referenceAllProjectRetentionDiverse", allDiverse.get("contextEvidenceRetention"));
            item.put("scopeViolationCount", scopeViolationCount(scopedTop20.hits(), scopeIds));
            item.put("sourceCapStrategyAllProjectReferenceApproximate",
                    compareSourceCapStrategies(allTop20.hits(), expected));
        }
        EVIDENCE.put(testCase.name(), item);

        // ---- L1 硬性不变量（必须通过，主口径） ----
        // 1. 丢弃归因互斥且合计恒等于 droppedByContext：不得出现无归因的静默丢弃。
        assertThat(sumDropReasons(item))
                .as("%s：丢弃归因之和必须等于 droppedByContext", testCase.name())
                .isEqualTo(primaryDiverse.get("droppedByContext"));
        // 2. 正文预算硬上限 8000 code point：公平预算不得超限。
        assertThat(promptCodePoints(primaryContext))
                .as("%s：上下文正文预算不得超过 8000 code point", testCase.name())
                .isLessThanOrEqualTo(8000);
        // 3. 保留来源的身份必须与证据一致：不得张冠李戴。
        for (KnowledgeSource source : primaryContext.sources()) {
            expected.stream()
                    .filter(evidence -> evidence.chunkId().equals(source.chunkId()))
                    .forEach(evidence -> assertThat(source.documentId())
                            .as("%s：保留来源 %s 的 documentId 必须与其 chunkId 归属一致", testCase.name(), evidence.label())
                            .isEqualTo(evidence.documentId()));
        }
        // 4. 指定文档范围时，召回候选不得越界（ACL/范围隔离），且必须真的跑过指定范围检索。
        if (testCase.documentScoped()) {
            assertThat(scopedTop20.hits()).extracting(DocumentSearchHit::documentId)
                    .as("%s：指定范围检索不得返回范围外文档", testCase.name())
                    .allMatch(scopeIds::contains);
            assertThat(scopedDiverse).as("指定范围样本必须真的执行了指定范围检索").isNotNull();
        }
        // 5. 无期望证据时必须判定为未定义并如实留空，不得伪装成召回失败或补造来源。
        if (testCase.noEvidence()) {
            assertThat(primaryDiverse.get("retrievalStatus"))
                    .as("%s：没有可回答证据时必须判为未定义", testCase.name())
                    .isEqualTo(RetrievalCoverage.UNDEFINED_NO_EXPECTED_EVIDENCE);
            assertThat(RetrievalCoverage.classify(testCase.name(),
                    scopedLegacy != null ? scopedLegacy : allLegacy, primaryDiverse).get("verdict"))
                    .isEqualTo(RetrievalCoverage.UNDEFINED_NO_EXPECTED_EVIDENCE);
        }
        return new CaseMeasurement(item, allLegacy, allDiverse, scopedLegacy, scopedDiverse);
    }

    private static int scopeViolationCount(List<DocumentSearchHit> hits, List<UUID> scope) {
        if (scope == null) return 0;
        int violations = 0;
        for (DocumentSearchHit hit : hits) {
            if (!scope.contains(hit.documentId())) violations++;
        }
        return violations;
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

    // ---- 范围维度的汇总 ----

    /**
     * 单一范围维度的汇总：用 {@link RetrievalCoverage#aggregate} 按计数加权，避免“按用例平均”掩盖样本量差异。
     * Top8 口径给 Recall@8 与旧入口保留率；Top20 口径给 Recall@20 与新入口（跨文档）保留率与文档覆盖。
     */
    private static Map<String, Object> summarizeDimension(
            List<Map<String, Object>> top8, List<Map<String, Object>> top20) {
        Map<String, Object> at8 = RetrievalCoverage.aggregate(top8);
        Map<String, Object> at20 = RetrievalCoverage.aggregate(top20);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sampleCount", at20.get("caseCount"));
        summary.put("undefinedSampleCount", at20.get("undefinedCaseCount"));
        summary.put("expectedEvidenceCount", at20.get("expectedEvidenceCount"));
        summary.put("recallAt8", at8.get("retrievalRecall"));
        summary.put("recallAt20", at20.get("retrievalRecall"));
        summary.put("retentionLegacyAt8", at8.get("contextEvidenceRetention"));
        summary.put("retentionDiverseAt20", at20.get("contextEvidenceRetention"));
        summary.put("documentCoverageAt20", at20.get("documentCoverage"));
        summary.put("sourceCapDropAt20", at20.get("sourceCapDrop"));
        summary.put("budgetDropAt20", at20.get("budgetDrop"));
        summary.put("dbMissAt20", at20.get("notRecalledInTopK"));
        return summary;
    }

    // ---- 阶段 D：SOURCE_CAP 策略对照（同一批真实候选、同一 limit、同一正文预算） ----

    private static Map<String, Object> compareSourceCapStrategies(
            List<DocumentSearchHit> candidates, List<ExpectedEvidence> expected) {
        Set<UUID> expectedChunks = new LinkedHashSet<>();
        for (ExpectedEvidence evidence : expected) {
            expectedChunks.add(evidence.chunkId());
        }
        var current = SourceCapStrategyExperiment.assemble(
                SourceCapStrategyExperiment.selectCurrent(candidates, AGENT_LIMIT), AGENT_LIMIT);
        var protectedStrategy = SourceCapStrategyExperiment.assemble(
                SourceCapStrategyExperiment.selectRelevanceProtected(candidates, AGENT_LIMIT), AGENT_LIMIT);
        var compromiseStrategy = SourceCapStrategyExperiment.assemble(
                SourceCapStrategyExperiment.selectConservativeCompromise(candidates, AGENT_LIMIT), AGENT_LIMIT);

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("expectedEvidenceCount", expectedChunks.size());
        comparison.put("currentExpectedRetained", retainedCount(current.chunkIds(), expectedChunks));
        comparison.put("protectedExpectedRetained", retainedCount(protectedStrategy.chunkIds(), expectedChunks));
        comparison.put("compromiseExpectedRetained", retainedCount(compromiseStrategy.chunkIds(), expectedChunks));
        comparison.put("currentDocumentsCovered", coveredExpectedDocuments(current.chunkIds(), expected));
        comparison.put("protectedDocumentsCovered", coveredExpectedDocuments(protectedStrategy.chunkIds(), expected));
        comparison.put("compromiseDocumentsCovered", coveredExpectedDocuments(compromiseStrategy.chunkIds(), expected));
        comparison.put("currentUnexpectedSources", unexpectedCount(current.chunkIds(), expectedChunks));
        comparison.put("protectedUnexpectedSources", unexpectedCount(protectedStrategy.chunkIds(), expectedChunks));
        comparison.put("compromiseUnexpectedSources", unexpectedCount(compromiseStrategy.chunkIds(), expectedChunks));
        comparison.put("currentPromptCodePoints", current.promptCodePoints());
        comparison.put("protectedPromptCodePoints", protectedStrategy.promptCodePoints());
        comparison.put("compromisePromptCodePoints", compromiseStrategy.promptCodePoints());
        return comparison;
    }

    private static int retainedCount(List<UUID> retained, Set<UUID> expectedChunks) {
        int count = 0;
        for (UUID chunk : expectedChunks) if (retained.contains(chunk)) count++;
        return count;
    }

    private static int coveredExpectedDocuments(List<UUID> retained, List<ExpectedEvidence> expected) {
        Set<UUID> covered = new LinkedHashSet<>();
        for (ExpectedEvidence evidence : expected) {
            if (retained.contains(evidence.chunkId())) covered.add(evidence.documentId());
        }
        return covered.size();
    }

    private static int unexpectedCount(List<UUID> retained, Set<UUID> expectedChunks) {
        int count = 0;
        for (UUID chunk : retained) if (!expectedChunks.contains(chunk)) count++;
        return count;
    }

    private static double ratioOrZero(int numerator, int denominator) {
        return denominator == 0 ? 0d : Math.round(((double) numerator / denominator) * 10_000d) / 10_000d;
    }

    /** 汇总某一范围维度下 A/B/C 三种 SOURCE_CAP 策略的期望证据保留与文档覆盖（同一批真实候选）。 */
    private static Map<String, Object> summarizeSourceCapStrategy(
            List<Map<String, Object>> items, String scopeLabel, String strategyField) {
        int cases = 0;
        int expected = 0;
        int currentRetained = 0;
        int protectedRetained = 0;
        int compromiseRetained = 0;
        int currentDocs = 0;
        int protectedDocs = 0;
        int compromiseDocs = 0;
        int currentUnexpected = 0;
        int protectedUnexpected = 0;
        int compromiseUnexpected = 0;
        for (Map<String, Object> item : items) {
            if (!scopeLabel.equals(item.get("scope"))) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> strategy = (Map<String, Object>) item.get(strategyField);
            cases++;
            expected += number(strategy.get("expectedEvidenceCount"));
            currentRetained += number(strategy.get("currentExpectedRetained"));
            protectedRetained += number(strategy.get("protectedExpectedRetained"));
            compromiseRetained += number(strategy.get("compromiseExpectedRetained"));
            currentDocs += number(strategy.get("currentDocumentsCovered"));
            protectedDocs += number(strategy.get("protectedDocumentsCovered"));
            compromiseDocs += number(strategy.get("compromiseDocumentsCovered"));
            currentUnexpected += number(strategy.get("currentUnexpectedSources"));
            protectedUnexpected += number(strategy.get("protectedUnexpectedSources"));
            compromiseUnexpected += number(strategy.get("compromiseUnexpectedSources"));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseCount", cases);
        summary.put("expectedEvidenceCount", expected);
        summary.put("currentExpectedRetained", currentRetained);
        summary.put("protectedExpectedRetained", protectedRetained);
        summary.put("compromiseExpectedRetained", compromiseRetained);
        summary.put("deltaExpectedRetained_B", protectedRetained - currentRetained);
        summary.put("deltaExpectedRetained_C", compromiseRetained - currentRetained);
        summary.put("currentExpectedRetention", ratioOrZero(currentRetained, expected));
        summary.put("protectedExpectedRetention", ratioOrZero(protectedRetained, expected));
        summary.put("compromiseExpectedRetention", ratioOrZero(compromiseRetained, expected));
        summary.put("currentDocumentsCovered", currentDocs);
        summary.put("protectedDocumentsCovered", protectedDocs);
        summary.put("compromiseDocumentsCovered", compromiseDocs);
        summary.put("deltaDocumentsCovered_B", protectedDocs - currentDocs);
        summary.put("deltaDocumentsCovered_C", compromiseDocs - currentDocs);
        summary.put("currentUnexpectedSources", currentUnexpected);
        summary.put("protectedUnexpectedSources", protectedUnexpected);
        summary.put("compromiseUnexpectedSources", compromiseUnexpected);
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
