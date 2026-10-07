package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.document.application.service.DocumentContentService;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 正文页预算放大的端到端回归（真实 PostgreSQL + 真实 {@link DocumentContentService} +
 * 真实 read_document_section 工具 + 真实结果大小保护）。
 *
 * <p>覆盖设计第 6 节要求：默认页 12000 / 上限 24000 字符必须在真实执行边界生效；
 * 一页 24000 字符正文必须能穿过结果大小保护而不是在别处被拒或被悄悄截断；
 * 原 heading/chunk/offset/snapshot 续读契约不变；UTF-8 代理对边界不被切断。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
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
class DocumentBodyPageBudgetIntegrationTest {
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

    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentContentService content;

    private final ObjectMapper json = new ObjectMapper();
    private final AgentToolResultSanitizer sanitizer = new AgentToolResultSanitizer(json);

    /** 真实生产工具实例：definition/execute 都是真实实现，服务是真实容器 Bean。 */
    private AgentTool readTool() {
        return new DocumentAgentTools().readDocumentSection(content);
    }

    // ------------------------------------------------------------ 夹具

    private UUID userId;
    private UUID projectId;
    private UUID documentId;

    /** 建 project/document 并落库指定正文块。 */
    private void seed(List<DocumentChunk> chunks) {
        userId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                userId, "body-" + userId.toString().substring(0, 8), "test-only-hash", "Body owner");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                projectId, "正文页预算", userId, userId);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                projectId, userId);
        jdbc.update("""
                INSERT INTO project_document(
                    id, project_id, display_name, original_filename, mime_type,
                    size_bytes, object_key, status, chunk_count,
                    embedding_provider, embedding_model, embedding_dimension, uploaded_by)
                VALUES (?, ?, '正文', '正文.md', 'text/markdown',
                    10, ?, 'READY', ?, 'test-provider', 'test-model', 3, ?)
                """, documentId, projectId, "projects/" + projectId + "/body.md", chunks.size(), userId);
        UUID token = UUID.randomUUID();
        jdbc.update("UPDATE project_document SET status='PARSING',processing_token=? WHERE id=?",
                token, documentId);
        content.saveParsed(projectId, documentId, token,
                "原件".getBytes(java.nio.charset.StandardCharsets.UTF_8), chunks);
        jdbc.update("UPDATE project_document SET status='READY' WHERE id=?", documentId);
    }

    private static DocumentChunk chunk(int no, String heading, String body) {
        return new DocumentChunk(no, heading, body, "hash-" + no, Math.max(1, body.length() / 4),
                Map.of("pageNumber", no + 1));
    }

    private AgentToolContext context() {
        return new AgentToolContext(UUID.randomUUID(), projectId, userId, "OWNER", false, 0);
    }

    private ObjectNode readArgs(int fromChunk, int fromOffset, int maxChars) {
        return json.createObjectNode()
                .put("documentId", documentId.toString())
                .put("fromChunk", fromChunk)
                .put("fromOffset", fromOffset)
                .put("maxChars", maxChars);
    }

    // ------------------------------------------------------------ 预算范围

    @Test
    void maxCharsUpperBoundIs24000AndLargerValuesAreRejected() {
        seed(List.of(chunk(0, "第一章", "甲".repeat(30000))));

        assertThat(DocumentContentService.DEFAULT_MAX_CHARS).isEqualTo(12000);
        assertThat(DocumentContentService.MAX_MAX_CHARS).isEqualTo(24000);

        // 24000 被接受（新上限）
        var atLimit = content.read(projectId, documentId, userId, null, 0, 0,
                DocumentContentService.MAX_MAX_CHARS, null, null);
        assertThat(atLimit.path("readChars").asInt())
                .isEqualTo(DocumentContentService.MAX_MAX_CHARS);
        assertThat(atLimit.path("items").get(0).path("content").asText())
                .hasSize(DocumentContentService.MAX_MAX_CHARS);

        // 24001 被拒：放宽没有变成无界
        assertThatThrownBy(() -> content.read(projectId, documentId, userId, null, 0, 0,
                24001, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档范围或预算无效");
        // 旧的 6000 上限不再拒绝：6001 现在是合法预算（这正是本次放宽的目的）
        assertThat(content.read(projectId, documentId, userId, null, 0, 0, 6001, null, null)
                .path("readChars").asInt()).isEqualTo(6001);
        // 下界与方向检查保持原样
        assertThatThrownBy(() -> content.read(projectId, documentId, userId, null, 0, 0,
                99, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> content.read(projectId, documentId, userId, null, -1, 0,
                1000, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> content.read(projectId, documentId, userId, null, 0, -1,
                1000, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> content.read(projectId, documentId, userId, null, 0, 0,
                1000, "长".repeat(301), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultPageSizeIs12000WhenBudgetIsOmittedFromTheToolCall() {
        seed(List.of(chunk(0, "第一章", "乙".repeat(30000))));

        // 省略 maxChars：真实工具必须用 12000 的默认页，而不是旧 3000
        var args = json.createObjectNode()
                .put("documentId", documentId.toString())
                .put("fromChunk", 0)
                .put("fromOffset", 0);
        var data = readTool().execute(context(), args).data();

        assertThat(data.path("readChars").asInt())
                .isEqualTo(DocumentContentService.DEFAULT_MAX_CHARS)
                .isEqualTo(12000);
    }

    // ------------------------------------------------------------ 结果大小保护

    @Test
    void fullPageBodySurvivesTheRealResultSizeProtectionPath() throws Exception {
        seed(List.of(chunk(0, "第一章", "中文正文".repeat(8000)))); // 32000 字符

        var result = readTool().execute(context(),
                readArgs(0, 0, DocumentContentService.MAX_MAX_CHARS));
        var data = result.data();
        assertThat(data.path("readChars").asInt()).isEqualTo(24000);

        // 真实执行器路径：先 valueToTree 整个 AgentToolResult，再交给清洗器
        var serialized = json.valueToTree(result);
        long rawBytes = json.writeValueAsBytes(serialized).length;
        assertThat(rawBytes)
                .as("24000 字符中文页的序列化大小应超过旧的 32kB 结果上限")
                .isGreaterThan(32 * 1024);

        var sanitized = sanitizer.sanitize(serialized);

        // 没有被大小保护判定为过大而替代成错误对象，也没有走 reduce 缩减分支
        assertThat(sanitized.hasNonNull("error")).as("不应被判定为结果过大").isFalse();
        assertThat(sanitized.path("originalSize").isMissingNode())
                .as("正文页不应走 reduce/过大分支").isTrue();
        assertThat(json.writeValueAsBytes(sanitized).length)
                .isLessThanOrEqualTo(AgentToolResultSanitizer.maxResultBytes());

        // 正文完整穿过，且没有被追加截断标记
        String visible = sanitized.path("data").path("items").get(0).path("content").asText();
        assertThat(visible).hasSize(24000);
        assertThat(visible).doesNotContain("[truncated]");
        assertThat(sanitized.path("data").path("readChars").asInt()).isEqualTo(24000);
        // 不可信来源语义与覆盖声明不变
        assertThat(sanitized.path("data").path("trust").asText()).isEqualTo("SOURCE_DATA_ONLY");
        assertThat(sanitized.path("data").path("coverage").asText()).isEqualTo("SPECIFIED_RANGE_ONLY");
    }

    /**
     * 真实分块粒度下的最坏情况：DocumentChunker 的目标块长约 1200 字符，一页 24000 字符
     * 因此会横跨约 20 个块；每个块还会各自贡献一条最多 600 字符的引用摘录。
     * 这是"24000 字符一页"在实际文档上的真实体积，必须整体穿过结果大小保护。
     */
    @Test
    void fullPageSpanningManyRealChunksStillSurvivesResultSizeProtection() throws Exception {
        List<DocumentChunk> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) chunks.add(chunk(i, "第 " + (i + 1) + " 节", "正文内容".repeat(300)));
        seed(chunks); // 20 块 × 1200 字符 = 24000 字符

        var result = readTool().execute(context(),
                readArgs(0, 0, DocumentContentService.MAX_MAX_CHARS));
        assertThat(result.data().path("readChars").asInt()).isEqualTo(24000);
        assertThat(result.data().path("items")).hasSize(20);
        // 引用摘录与 items 同范围，构成完整的工具结果体积
        assertThat(result.citations()).hasSize(20);

        var serialized = json.valueToTree(result);
        long rawBytes = json.writeValueAsBytes(serialized).length;
        System.out.println("DIAG multiChunk rawBytes=" + rawBytes);

        var sanitized = sanitizer.sanitize(serialized);

        assertThat(sanitized.hasNonNull("error"))
                .as("跨 20 块的一页正文不应被判定为结果过大").isFalse();
        assertThat(sanitized.path("originalSize").isMissingNode())
                .as("不应走 reduce 缩减分支").isTrue();
        assertThat(json.writeValueAsBytes(sanitized).length)
                .isLessThanOrEqualTo(AgentToolResultSanitizer.maxResultBytes());
        // 每一块的正文都完整可见，没有被清洗器压到 2000 字符
        int visibleTotal = 0;
        for (var item : sanitized.path("data").path("items")) {
            String visible = item.path("content").asText();
            assertThat(visible).hasSize(1200);
            assertThat(visible).doesNotContain("[truncated]");
            visibleTotal += visible.length();
        }
        assertThat(visibleTotal).isEqualTo(24000);
        assertThat(sanitized.path("data").path("readChars").asInt()).isEqualTo(24000);
    }

    /**
     * 最严格的最坏情况：用<b>真实</b> {@link com.shitulelv.aicollab.document.domain.service.DocumentChunker}
     * 切分长中文正文（目标块长 1200、重叠 150，因此 24000 字符会得到比"每块恰好 1200"更多的块），
     * 再按最大预算读取一页。重叠会让一页横跨更多块，每块又多一条引用摘录，
     * 这是结果体积的真实上界来源。
     */
    @Test
    void realChunkerWorstCasePageStillFitsTheResultSizeBound() throws Exception {
        // 长中文正文，交由真实分块器切分
        var split = new com.shitulelv.aicollab.document.domain.service.DocumentChunker()
                .split("正文内容测试".repeat(20000));
        assertThat(split.size()).as("真实分块器应产出多块").isGreaterThan(20);
        seed(split);

        var result = readTool().execute(context(),
                readArgs(0, 0, DocumentContentService.MAX_MAX_CHARS));
        int readChars = result.data().path("readChars").asInt();
        int itemCount = result.data().path("items").size();
        assertThat(readChars).isEqualTo(DocumentContentService.MAX_MAX_CHARS);

        var serialized = json.valueToTree(result);
        long rawBytes = json.writeValueAsBytes(serialized).length;
        System.out.println("DIAG realChunker chunks=" + split.size() + " items=" + itemCount
                + " readChars=" + readChars + " rawBytes=" + rawBytes
                + " bound=" + AgentToolResultSanitizer.maxResultBytes());

        var sanitized = sanitizer.sanitize(serialized);
        assertThat(sanitized.hasNonNull("error"))
                .as("真实分块器下的一页正文不应被判定为结果过大").isFalse();
        assertThat(sanitized.path("originalSize").isMissingNode())
                .as("不应走 reduce 缩减分支").isTrue();
        assertThat(json.writeValueAsBytes(sanitized).length)
                .isLessThanOrEqualTo(AgentToolResultSanitizer.maxResultBytes());
        // 可见正文总和等于声明的 readChars，没有被悄悄截断
        int visibleTotal = 0;
        for (var item : sanitized.path("data").path("items")) {
            assertThat(item.path("content").asText()).doesNotContain("[truncated]");
            visibleTotal += item.path("content").asText().length();
        }
        assertThat(visibleTotal).isEqualTo(readChars);
    }

    // ------------------------------------------------------------ 续读契约

    @Test
    void continuationContractStillWorksAcrossPagesWithinOneSnapshot() {
        seed(List.of(
                chunk(0, "第一章", "甲".repeat(24000)),
                chunk(1, "第二章", "乙".repeat(5000)),
                chunk(2, "第三章", "丙".repeat(2000))));

        // 第一页：恰好用完 24000 预算
        var page1 = content.read(projectId, documentId, userId, null, 0, 0, 24000, null, null);
        assertThat(page1.path("readChars").asInt()).isEqualTo(24000);
        assertThat(page1.path("truncated").asBoolean()).isTrue();
        assertThat(page1.path("hasMore").asBoolean()).isTrue();
        assertThat(page1.path("items")).hasSize(1);
        assertThat(page1.path("items").get(0).path("fromOffset").asInt()).isZero();
        assertThat(page1.path("items").get(0).path("throughOffset").asInt()).isEqualTo(24000);

        var cursor = page1.path("continuation");
        // continuation 形状不变
        assertThat(cursor.path("snapshotId").asText()).isNotBlank();
        assertThat(cursor.path("fromChunk").asInt()).isEqualTo(1);
        assertThat(cursor.path("fromOffset").asInt()).isZero();

        // 用 continuation 续读第二页：不重不漏，从块 1 偏移 0 开始
        var page2 = content.read(projectId, documentId, userId,
                UUID.fromString(cursor.path("snapshotId").asText()),
                cursor.path("fromChunk").asInt(), cursor.path("fromOffset").asInt(),
                24000, null, null);
        assertThat(page2.path("items")).hasSize(2);
        assertThat(page2.path("items").get(0).path("chunkNo").asInt()).isEqualTo(1);
        assertThat(page2.path("items").get(0).path("fromOffset").asInt()).isZero();
        assertThat(page2.path("items").get(1).path("chunkNo").asInt()).isEqualTo(2);
        assertThat(page2.path("readChars").asInt()).isEqualTo(7000);
        // 读到文档末尾：hasMore/truncated 一致为 false，continuation 为 null
        assertThat(page2.path("hasMore").asBoolean()).isFalse();
        assertThat(page2.path("truncated").asBoolean()).isFalse();
        assertThat(page2.path("continuation").isNull()).isTrue();

        // 跨 snapshot 读取仍被拒（版本正确性语义不变）
        jdbc.update("UPDATE project_document SET status='PARSING' WHERE id=?", documentId);
        content.saveParsed(projectId, documentId,
                jdbc.queryForObject("SELECT processing_token FROM project_document WHERE id=?",
                        UUID.class, documentId),
                "新原件".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                List.of(chunk(0, "第一章", "新正文")));
        UUID staleSnapshot = UUID.fromString(cursor.path("snapshotId").asText());
        assertThatThrownBy(() -> content.read(projectId, documentId, userId, staleSnapshot,
                0, 0, 1000, null, null))
                .hasMessageContaining("来源已更新");
    }

    @Test
    void headingScopedReadKeepsPerPageBudgetAndContinuation() {
        seed(List.of(
                chunk(0, "第一章", "甲".repeat(200)),
                chunk(1, "第二章", "乙".repeat(30000)),
                chunk(2, "第三章", "丙".repeat(200))));

        // 只读第二章：预算在该章节内生效，续读仍落在同一 heading
        var page = content.read(projectId, documentId, userId, null, 0, 0, 12000, "第二章", null);
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("heading").asText()).isEqualTo("第二章");
        assertThat(page.path("readChars").asInt()).isEqualTo(12000);
        assertThat(page.path("hasMore").asBoolean()).isTrue();

        var cursor = page.path("continuation");
        var next = content.read(projectId, documentId, userId,
                UUID.fromString(cursor.path("snapshotId").asText()),
                cursor.path("fromChunk").asInt(), cursor.path("fromOffset").asInt(),
                12000, "第二章", null);
        assertThat(next.path("items").get(0).path("fromOffset").asInt()).isEqualTo(12000);
        assertThat(next.path("items").get(0).path("heading").asText()).isEqualTo("第二章");
    }

    // ------------------------------------------------------------ UTF-8 / 代理对边界

    @Test
    void pageBudgetNeverSplitsASurrogatePairAtTheBoundary() {
        // 每个 😀 占 2 个 UTF-16 码元：预算 101 会落在高代理位上
        seed(List.of(chunk(0, "表情", "😀".repeat(100))));

        var page = content.read(projectId, documentId, userId, null, 0, 0, 101, null, null);
        String visible = page.path("items").get(0).path("content").asText();

        // 退让一个码元，保证可见正文是完整码点序列
        assertThat(page.path("readChars").asInt()).isEqualTo(100);
        assertThat(visible).hasSize(100);
        assertThat(visible.codePointCount(0, visible.length())).isEqualTo(50);
        assertThat(Character.isHighSurrogate(visible.charAt(visible.length() - 1))).isFalse();

        // 续读点与可见终点一致，续读内容不丢失也不重复任何码点
        var cursor = page.path("continuation");
        assertThat(page.path("items").get(0).path("throughOffset").asInt()).isEqualTo(100);
        assertThat(cursor.path("fromChunk").asInt()).isZero();
        assertThat(cursor.path("fromOffset").asInt()).isEqualTo(100);

        var rest = content.read(projectId, documentId, userId,
                UUID.fromString(cursor.path("snapshotId").asText()),
                cursor.path("fromChunk").asInt(), cursor.path("fromOffset").asInt(),
                1000, null, null);
        String remainder = rest.path("items").get(0).path("content").asText();
        assertThat(remainder).hasSize(100);
        assertThat(rest.path("items").get(0).path("fromOffset").asInt()).isEqualTo(100);
        // 两页拼起来正好是完整原文
        assertThat(visible + remainder).isEqualTo("😀".repeat(100));
    }
}
