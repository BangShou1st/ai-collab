package com.shitulelv.aicollab.common.ai.token;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一 Token 计数器测试（J0-1）：provider/model 不再影响数值，
 * 所有来源必须是 ESTIMATE，协议开销仍然存在。
 */
class ModelAwareTokenCounterTest {

    private final ModelAwareTokenCounter counter = new ModelAwareTokenCounter();

    @Test
    void allTextSourcesAreEstimatedRegardlessOfProviderOrModel() {
        String text = "测试中文段落与 English words 123.";
        TokenCount claude = counter.countText(text, "ANTHROPIC", "claude-3-5-sonnet");
        TokenCount gemini = counter.countText(text, "GOOGLE", "gemini-1.5-pro");
        TokenCount openai = counter.countText(text, "OPENAI", "gpt-4o");
        TokenCount qwen = counter.countText(text, "DASHSCOPE", "qwen-max");
        TokenCount nativeNamed = counter.countText(text, "NATIVE", "native:anything");

        for (TokenCount count : List.of(claude, gemini, openai, qwen, nativeNamed)) {
            assertThat(count.source()).isEqualTo(TokenCountSource.ESTIMATE);
            assertThat(count.estimated()).isTrue();
            assertThat(count.basis()).isEqualTo(ModelAwareTokenCounter.TEXT_BASIS);
            assertThat(count.count()).isEqualTo(TokenEstimator.estimate(text));
        }
        assertThat(claude.count()).isEqualTo(gemini.count()).isEqualTo(openai.count())
                .isEqualTo(qwen.count()).isEqualTo(nativeNamed.count());
    }

    @Test
    void noProviderCanClaimNativeExactness() {
        // 旧实现把 provider=NATIVE 或 model 前缀 native: 伪装成精确 NATIVE；
        // J0-1 起该路径已删除：任何文本计数都只能是 ESTIMATE。
        TokenCount count = counter.countText("原生统计接口测试", "NATIVE", "native:gpt-4o");
        assertThat(count.source()).isNotEqualTo(TokenCountSource.PROVIDER);
        assertThat(count.source()).isNotEqualTo(TokenCountSource.TOKENIZER);
        assertThat(count.source()).isEqualTo(TokenCountSource.ESTIMATE);
        assertThat(count.estimated()).isTrue();
    }

    @Test
    void emptyTextIsZeroAndUnknown() {
        TokenCount empty = counter.countText("", "OPENAI", "gpt-4o");
        assertThat(empty.count()).isZero();
        assertThat(empty.source()).isEqualTo(TokenCountSource.UNKNOWN);
        assertThat(empty.estimated()).isTrue();
        assertThat(counter.countText(null, null, null).count()).isZero();
    }

    @Test
    void countMessagesIntegratesProtocolOverhead() {
        List<String> messages = List.of(
                "System instructions",
                "User query content",
                "Assistant response draft"
        );

        TokenCount result = counter.countMessages(messages, "OPENAI", "gpt-4o");

        int expected = 3 * 4 + 3
                + TokenEstimator.estimate("System instructions")
                + TokenEstimator.estimate("User query content")
                + TokenEstimator.estimate("Assistant response draft");
        assertThat(result.count()).isEqualTo(expected);
        assertThat(result.source()).isEqualTo(TokenCountSource.ESTIMATE);
        assertThat(result.estimated()).isTrue();
        assertThat(result.basis()).isEqualTo(ModelAwareTokenCounter.MESSAGE_BASIS);

        // provider/model 不改变协议计数
        TokenCount nativeNamed = counter.countMessages(messages, "NATIVE", "native:gpt-4o");
        assertThat(nativeNamed.count()).isEqualTo(result.count());
        assertThat(nativeNamed.source()).isEqualTo(TokenCountSource.ESTIMATE);
        assertThat(nativeNamed.estimated()).isTrue();
    }

    @Test
    void countToolDefinitionsIntegratesSchemaOverhead() {
        List<String> tools = List.of(
                "{\"name\":\"search_project_knowledge\",\"parameters\":{}}",
                "{\"name\":\"create_task\",\"parameters\":{}}"
        );

        TokenCount result = counter.countToolDefinitions(tools, "OPENAI", "gpt-4o");

        int expected = 2 * 16
                + TokenEstimator.estimate("{\"name\":\"search_project_knowledge\",\"parameters\":{}}")
                + TokenEstimator.estimate("{\"name\":\"create_task\",\"parameters\":{}}");
        assertThat(result.count()).isEqualTo(expected);
        assertThat(result.source()).isEqualTo(TokenCountSource.ESTIMATE);
        assertThat(result.estimated()).isTrue();
        assertThat(result.basis()).isEqualTo(ModelAwareTokenCounter.TOOL_SCHEMA_BASIS);

        TokenCount nativeNamed = counter.countToolDefinitions(tools, "NATIVE", "native:gpt-4o");
        assertThat(nativeNamed.count()).isEqualTo(result.count());
        assertThat(nativeNamed.source()).isEqualTo(TokenCountSource.ESTIMATE);
        assertThat(nativeNamed.estimated()).isTrue();
    }

    @Test
    void emptyMessageAndToolListsAreZero() {
        assertThat(counter.countMessages(List.of(), "OPENAI", "gpt-4o").count()).isZero();
        assertThat(counter.countToolDefinitions(List.of(), "OPENAI", "gpt-4o").count()).isZero();
        assertThat(counter.countMessages(null, null, null).count()).isZero();
        assertThat(counter.countToolDefinitions(null, null, null).count()).isZero();
    }
}
