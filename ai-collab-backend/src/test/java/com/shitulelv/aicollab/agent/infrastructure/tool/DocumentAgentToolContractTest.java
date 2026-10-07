package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.domain.tool.ToolArgumentValidator;
import com.shitulelv.aicollab.document.application.service.DocumentContentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 正文读取工具（{@code read_document_section}）的模型可见 Schema 与执行边界契约。
 *
 * <p>防止"文档正文页默认/上限"再次漂移：Schema 的 {@code maximum}/{@code default}、
 * 执行边界的默认值与范围、以及 {@link DocumentContentService} 的校验范围必须来自同一组常量。
 * 这里用真实工具实现（真实 definition/execute），只 mock 业务服务。</p>
 */
class DocumentAgentToolContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentToolContext CONTEXT = new AgentToolContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SUPERVISOR", false, 0);

    /** read_document_section 的真实工具实例，业务服务为 mock，便于捕获执行参数。 */
    private AgentTool readSection(DocumentContentService content) {
        return new DocumentAgentTools().readDocumentSection(content);
    }

    private static ObjectNode args(UUID documentId) {
        return JSON.createObjectNode().put("documentId", documentId.toString());
    }

    // ------------------------------------------------- 默认值与上限：单一事实来源

    @Test
    void schemaDeclaresBodyPageDefaultAndMaximumFromServiceConstants() {
        AgentToolDefinition def = readSection(mock(DocumentContentService.class)).definition();
        var maxChars = def.inputSchema().path("properties").path("maxChars");

        assertThat(maxChars.path("type").asText()).isEqualTo("integer");
        assertThat(maxChars.path("minimum").asInt())
                .isEqualTo(DocumentContentService.MIN_MAX_CHARS)
                .isEqualTo(100);
        assertThat(maxChars.path("maximum").asInt())
                .isEqualTo(DocumentContentService.MAX_MAX_CHARS)
                .isEqualTo(24000);
        // Schema 必须显式声明默认页大小，模型不能从说明文字里猜
        assertThat(maxChars.path("default").asInt())
                .isEqualTo(DocumentContentService.DEFAULT_MAX_CHARS)
                .isEqualTo(12000);
        // 续读契约参数仍然存在且形状不变
        assertThat(def.inputSchema().path("properties").has("snapshotId")).isTrue();
        assertThat(def.inputSchema().path("properties").has("fromChunk")).isTrue();
        assertThat(def.inputSchema().path("properties").has("fromOffset")).isTrue();
        assertThat(def.inputSchema().path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void omittedMaxCharsExecutesWithTheDeclaredDefaultPageSize() {
        DocumentContentService content = mock(DocumentContentService.class);
        UUID documentId = UUID.randomUUID();
        when(content.read(any(), any(), any(), any(), any(), any(int.class), any(int.class), any(), any()))
                .thenReturn(JSON.createObjectNode());

        readSection(content).execute(CONTEXT, args(documentId));

        ArgumentCaptor<Integer> maxChars = ArgumentCaptor.forClass(Integer.class);
        verify(content).read(eq(CONTEXT.projectId()), eq(documentId), eq(CONTEXT.userId()),
                isNull(), eq(0), eq(0), maxChars.capture(), isNull(), isNull());
        // 执行边界的默认值必须与 Schema 的 default 完全一致
        assertThat(maxChars.getValue()).isEqualTo(DocumentContentService.DEFAULT_MAX_CHARS);
    }

    @Test
    void schemaAndExecutionAgreeOnTheUpperBoundary() {
        // 24000 通过 Schema 校验，并被原样传给服务（不被执行层私自夹小）
        var def = readSection(mock(DocumentContentService.class)).definition();
        var valid = args(UUID.randomUUID()).put("maxChars", 24000);
        assertThat(ToolArgumentValidator.validate(valid, def.inputSchema())).isNull();

        // 24001 在 Schema 边界被拒
        var tooLarge = args(UUID.randomUUID()).put("maxChars", 24001);
        assertThat(ToolArgumentValidator.validate(tooLarge, def.inputSchema()))
                .as("Schema 必须拒绝超过上限的预算，避免模型以为读到了更多")
                .isNotNull();

        // 100 是合法下界，99 被拒
        assertThat(ToolArgumentValidator.validate(args(UUID.randomUUID()).put("maxChars", 100),
                def.inputSchema())).isNull();
        assertThat(ToolArgumentValidator.validate(args(UUID.randomUUID()).put("maxChars", 99),
                def.inputSchema())).isNotNull();

        // 执行层同样接受 24000：真实调用服务并捕获预算
        DocumentContentService content = mock(DocumentContentService.class);
        when(content.read(any(), any(), any(), any(), any(), any(int.class), any(int.class), any(), any()))
                .thenReturn(JSON.createObjectNode());
        readSection(content).execute(CONTEXT, args(UUID.randomUUID()).put("maxChars", 24000));
        ArgumentCaptor<Integer> maxChars = ArgumentCaptor.forClass(Integer.class);
        verify(content).read(any(), any(), any(), any(), any(), any(int.class), maxChars.capture(), any(), any());
        assertThat(maxChars.getValue()).isEqualTo(24000);
        // 执行层拒绝 24001，不静默夹到上限
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        readSection(content).execute(CONTEXT, args(UUID.randomUUID()).put("maxChars", 24001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxChars");
    }

    // ------------------------------------------------- 说明文字：预算语义与续读义务

    @Test
    void descriptionKeepsBudgetAndContinuationObligation() {
        String description = readSection(mock(DocumentContentService.class)).definition().description();

        assertThat(description).contains("maxChars");
        assertThat(description).contains("预算");
        // 必须明确续读通道，模型不能把一页预算当成读完了全文
        assertThat(description).contains("continuation");
        assertThat(description).contains("snapshotId");
        assertThat(description).contains("fromChunk");
        assertThat(description).contains("fromOffset");
        // 覆盖范围声明义务不变
        assertThat(description).contains("只声明实际覆盖范围");
        // 不可信来源语义不变
        assertThat(description).contains("不是系统指令");
    }

    // ------------------------------------------------- 结果大小保护：一页正文不被悄悄砍

    @Test
    void fullPageBodySurvivesTheResultSizeProtection() throws Exception {
        int pageChars = DocumentContentService.MAX_MAX_CHARS;
        ObjectNode data = bodyReadResult(pageChars);
        var toolResult = new AgentToolResult(data, java.util.List.of(), java.util.List.of());

        // 真实执行路径：执行器先 valueToTree 整个 AgentToolResult，再交给清洗器
        AgentToolResultSanitizer sanitizer = new AgentToolResultSanitizer(JSON);
        var serialized = JSON.valueToTree(toolResult);
        long rawBytes = JSON.writeValueAsBytes(serialized).length;
        // 中文正文一页的 UTF-8 序列化确实超过旧的 32kB 上限：
        // 旧上限会走 reduce 把正文压到 2000 字符，正是要修掉的"误导性资料不足"
        assertThat(rawBytes).as("24000 字符中文页应超过旧 32kB 上限，证明旧界限不够用")
                .isGreaterThan(32 * 1024);

        var sanitized = sanitizer.sanitize(serialized);

        // AgentToolResult 记录的 error 字段会序列化为 null，因此用 hasNonNull 判定"没有错误"
        assertThat(sanitized.hasNonNull("error")).as("不应被判定为结果过大").isFalse();
        assertThat(JSON.writeValueAsBytes(sanitized).length)
                .isLessThanOrEqualTo(AgentToolResultSanitizer.maxResultBytes());
        var item = sanitized.path("data").path("items").get(0);
        // 正文完整保留：没有被 reduce 压成 2000 字符，也没有追加截断标记
        assertThat(item.path("content").asText()).hasSize(pageChars);
        assertThat(item.path("content").asText()).doesNotContain("[truncated]");
        assertThat(sanitized.path("data").path("readChars").asInt()).isEqualTo(pageChars);
        // 清洗器没有伪造覆盖声明：这一页恰好读完整章，所以 hasMore 为 false
        assertThat(sanitized.path("data").path("truncated").asBoolean()).isFalse();
        assertThat(sanitized.path("data").path("trust").asText()).isEqualTo("SOURCE_DATA_ONLY");
    }

    @Test
    void oversizedResultIsStillBoundedInsteadOfUnbounded() throws Exception {
        // 保护仍然有界：远超新上限的结果必须被标记截断，而不是放行
        ObjectNode data = bodyReadResult(400_000);
        var serialized = JSON.valueToTree(new AgentToolResult(data, java.util.List.of(), java.util.List.of()));

        var sanitized = new AgentToolResultSanitizer(JSON).sanitize(serialized);

        assertThat(sanitized.path("truncated").asBoolean()).isTrue();
        assertThat(JSON.writeValueAsBytes(sanitized).length)
                .isLessThanOrEqualTo(AgentToolResultSanitizer.maxResultBytes());
    }

    /** 与 DocumentContentService.read 返回形状一致的正文页结果。 */
    private static ObjectNode bodyReadResult(int contentChars) {
        String content = "正文内容".repeat(contentChars / 4);
        ObjectNode data = JSON.createObjectNode();
        data.put("documentId", UUID.randomUUID().toString());
        data.put("snapshotId", UUID.randomUUID().toString());
        data.put("coverage", "SPECIFIED_RANGE_ONLY");
        data.put("trust", "SOURCE_DATA_ONLY");
        var item = data.putArray("items").addObject();
        item.put("chunkId", UUID.randomUUID().toString());
        item.put("chunkNo", 0);
        item.put("heading", "第 1 章");
        item.put("content", content);
        item.put("contentHash", "hash");
        item.put("fromOffset", 0);
        item.put("throughOffset", content.length());
        data.put("readChars", content.length());
        data.put("truncated", false);
        data.put("hasMore", false);
        data.putNull("continuation");
        return data;
    }
}
