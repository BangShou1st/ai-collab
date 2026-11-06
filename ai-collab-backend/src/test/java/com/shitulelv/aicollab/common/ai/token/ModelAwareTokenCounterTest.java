package com.shitulelv.aicollab.common.ai.token;

import com.shitulelv.aicollab.agent.application.runtime.AgentContextBudget;
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
    void fineGrainedTokenizerForCl100kAndGptMarkedAsTokenizer() {
        // 对于 gpt-4 / cl100k 模型，细粒度分词器处理，必须标注为 TOKENIZER，且 estimated = false
        TokenCount gptCount = counter.countText("Hello world! This is a test: 100 items.", "OPENAI", "gpt-4o");
        assertThat(gptCount.source()).isEqualTo(TokenCountSource.TOKENIZER);
        assertThat(gptCount.estimated()).isFalse();
        assertThat(gptCount.basis()).contains("CL100K");
        assertThat(gptCount.count()).isPositive();
    }

    @Test
    void fineGrainedTokenizerForQwenMarkedAsTokenizer() {
        // 对于 Qwen 模型，支持大词表分词，标注为 TOKENIZER，且 estimated = false
        TokenCount qwenCount = counter.countText("通义千问模型测试混合检索", "DASHSCOPE", "qwen-max");
        assertThat(qwenCount.source()).isEqualTo(TokenCountSource.TOKENIZER);
        assertThat(qwenCount.estimated()).isFalse();
        assertThat(qwenCount.basis()).contains("QWEN");
        assertThat(qwenCount.count()).isPositive();
    }

    @Test
    void nativeApiProviderMarkedAsNative() {
        // 原生 API 模式严格标注为 NATIVE
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
        assertThat(result.source()).isEqualTo(TokenCountSource.TOKENIZER);
        assertThat(result.estimated()).isFalse();
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
        assertThat(result.source()).isEqualTo(TokenCountSource.TOKENIZER);
    }

    @Test
    void agentContextBudgetCalculatesTotalUnifiedBudget() {
        List<String> messages = List.of("User message");
        List<String> tools = List.of("{\"name\":\"tool1\"}");
        int outputReserve = 4000;
        int safetyMargin = 1000;

        AgentContextBudget.RequestTokenBudget budget = AgentContextBudget.calculateRequestTokens(
                counter, messages, tools, outputReserve, safetyMargin, "OPENAI", "gpt-4o"
        );

        assertThat(budget.messageTokens()).isPositive();
        assertThat(budget.toolSchemaTokens()).isPositive();
        assertThat(budget.outputReserveTokens()).isEqualTo(4000);
        assertThat(budget.safetyMarginTokens()).isEqualTo(1000);
        assertThat(budget.grandTotalTokens()).isEqualTo(
                budget.totalInputTokens() + outputReserve + safetyMargin
        );
        assertThat(budget.source()).isEqualTo(TokenCountSource.TOKENIZER);
        assertThat(budget.estimated()).isFalse();
    }
}
