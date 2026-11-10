package com.shitulelv.aicollab.common.ai.token;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelAwareTokenCounterTest {

    private final ModelAwareTokenCounter counter = new ModelAwareTokenCounter();

    @Test
    void heuristicCountingStrictlyMarkedAsEstimated() {
        // 对于 Claude / Gemini 或通用模型，使用加权启发式，必须明确标记为 ESTIMATED，严禁冒充精确值
        TokenCount claudeCount = counter.countText("测试中文段落与 English words 123.", "ANTHROPIC", "claude-3-5-sonnet");
        assertThat(claudeCount.source()).isEqualTo(TokenCountSource.ESTIMATED);
        assertThat(claudeCount.estimated()).isTrue();
        assertThat(claudeCount.count()).isPositive();

        TokenCount geminiCount = counter.countText("Gemini 模型测试", "GOOGLE", "gemini-1.5-pro");
        assertThat(geminiCount.source()).isEqualTo(TokenCountSource.ESTIMATED);
        assertThat(geminiCount.estimated()).isTrue();
    }

    @Test
    void patternApproximationForCl100kAndGptMarkedAsEstimated() {
        // 对于 gpt-4 / cl100k 模型，在未引入全量离线 BPE 词表字典前，模式分词算法必须诚实标注为 ESTIMATED，且 estimated = true
        TokenCount gptCount = counter.countText("Hello world! This is a test: 100 items.", "OPENAI", "gpt-4o");
        assertThat(gptCount.source()).isEqualTo(TokenCountSource.ESTIMATED);
        assertThat(gptCount.estimated()).isTrue();
        assertThat(gptCount.basis()).contains("CL100K_PATTERN_APPROX");
        assertThat(gptCount.count()).isPositive();
    }

    @Test
    void patternApproximationForQwenMarkedAsEstimated() {
        // 对于 Qwen 模型，模式分词算法必须诚实标注为 ESTIMATED，且 estimated = true
        TokenCount qwenCount = counter.countText("通义千问模型测试混合检索", "DASHSCOPE", "qwen-max");
        assertThat(qwenCount.source()).isEqualTo(TokenCountSource.ESTIMATED);
        assertThat(qwenCount.estimated()).isTrue();
        assertThat(qwenCount.basis()).contains("QWEN_PATTERN_APPROX");
        assertThat(qwenCount.count()).isPositive();
    }

    @Test
    void nativeApiProviderMarkedAsNative() {
        // 原生 API 模式严格标注为 NATIVE，且 estimated = false
        TokenCount nativeCount = counter.countText("原生统计接口测试", "NATIVE", "gpt-4o");
        assertThat(nativeCount.source()).isEqualTo(TokenCountSource.NATIVE);
        assertThat(nativeCount.estimated()).isFalse();
    }

    @Test
    void countMessagesIntegratesProtocolOverhead() {
        List<String> messages = List.of(
                "System instructions",
                "User query content",
                "Assistant response draft"
        );

        TokenCount result = counter.countMessages(messages, "OPENAI", "gpt-4o");

        // 3 条消息每条包装 4 tokens (12) + primer 3 tokens = 15 tokens 基础协议开销
        assertThat(result.count()).isGreaterThanOrEqualTo(15);
        assertThat(result.source()).isEqualTo(TokenCountSource.ESTIMATED);
        assertThat(result.estimated()).isTrue();

        // 当使用 NATIVE 提供方时，协议包装结果为 NATIVE
        TokenCount nativeResult = counter.countMessages(messages, "NATIVE", "gpt-4o");
        assertThat(nativeResult.source()).isEqualTo(TokenCountSource.NATIVE);
        assertThat(nativeResult.estimated()).isFalse();
    }

    @Test
    void countToolDefinitionsIntegratesSchemaOverhead() {
        List<String> tools = List.of(
                "{\"name\":\"search_project_knowledge\",\"parameters\":{}}",
                "{\"name\":\"create_task\",\"parameters\":{}}"
        );

        TokenCount result = counter.countToolDefinitions(tools, "OPENAI", "gpt-4o");

        // 2 个工具每个基础封装 16 tokens = 32 tokens 开销
        assertThat(result.count()).isGreaterThanOrEqualTo(32);
        assertThat(result.source()).isEqualTo(TokenCountSource.ESTIMATED);

        TokenCount nativeResult = counter.countToolDefinitions(tools, "NATIVE", "gpt-4o");
        assertThat(nativeResult.source()).isEqualTo(TokenCountSource.NATIVE);
        assertThat(nativeResult.estimated()).isFalse();
    }

    // 原 agentContextBudgetCalculatesTotalUnifiedBudget 用例已随 AgentContextBudget 的死 API
    // （calculateRequestTokens / RequestTokenBudget）删除：其断言精神（消息/工具协议开销聚合、
    // ESTIMATED 与 NATIVE 来源标记）已由上方 countMessagesIntegratesProtocolOverhead 与
    // countToolDefinitionsIntegratesSchemaOverhead 两个用例完整覆盖。
}
