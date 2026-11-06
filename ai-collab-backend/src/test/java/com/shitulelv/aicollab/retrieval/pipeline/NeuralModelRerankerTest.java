package com.shitulelv.aicollab.retrieval.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class NeuralModelRerankerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static class StubChatModelGateway implements ChatModelGateway {
        private final Function<ChatCompletionCommand, ChatCompletionResult> fn;

        StubChatModelGateway(Function<ChatCompletionCommand, ChatCompletionResult> fn) {
            this.fn = fn;
        }

        @Override
        public ChatCompletionResult complete(ChatCompletionCommand command) {
            return fn.apply(command);
        }

        @Override
        public void completeStream(
                ChatCompletionCommand command,
                Consumer<String> onToken,
                Consumer<ChatCompletionResult> onDone,
                Consumer<Exception> onError) {
        }
    }

    private RetrievalCandidate createCandidate(String filename, String heading, String content, double rrf) {
        return new RetrievalCandidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                filename,
                heading,
                content,
                "hash_" + filename,
                Map.of("pageNumber", 1, "charFrom", 0, "charThrough", content.length()),
                1,
                0.9,
                1,
                0.9,
                rrf,
                null,
                null
        );
    }

    @Test
    void testSuccessfulNeuralRerankingAndImmutabilityContract() {
        RetrievalCandidate c1 = createCandidate("doc1.md", "前言", "基础介绍文本", 0.03);
        RetrievalCandidate c2 = createCandidate("doc2.md", "核心架构", "高相关度核心架构内容", 0.02);

        StubChatModelGateway mockGateway = new StubChatModelGateway(command -> new ChatCompletionResult(
                "{\"0\": 0.35, \"1\": 0.98}",
                "MOCK",
                "mock-model",
                50,
                20,
                30L
        ));

        NeuralModelReranker reranker = new NeuralModelReranker(
                mockGateway,
                new SemanticHeuristicReranker(),
                objectMapper,
                Executors.newVirtualThreadPerTaskExecutor(),
                20,
                3000
        );

        List<RetrievalCandidate> results = reranker.rerank("核心架构", List.of(c1, c2), 2);

        assertThat(results).hasSize(2);
        // c2 应被排在首位，因为其神经网络得分 0.98 > 0.35
        RetrievalCandidate top = results.getFirst();
        assertThat(top.id()).isEqualTo(c2.id());
        assertThat(top.rerankScore()).isEqualTo(0.98);
        assertThat(top.rerankSource()).isEqualTo(NeuralModelReranker.NAME);
        assertThat(top.rerankerName()).isEqualTo(NeuralModelReranker.NAME);

        // 验证不可变证据契约：chunkId, docId, filename, heading, content, hash, metadata 完全不变
        assertThat(top.documentId()).isEqualTo(c2.documentId());
        assertThat(top.originalFilename()).isEqualTo("doc2.md");
        assertThat(top.heading()).isEqualTo("核心架构");
        assertThat(top.content()).isEqualTo("高相关度核心架构内容");
        assertThat(top.contentHash()).isEqualTo("hash_doc2.md");
        assertThat(top.metadata()).containsEntry("pageNumber", 1);
        assertThat(top.metadata()).containsEntry("rerankSource", NeuralModelReranker.NAME);
    }

    @Test
    void testSafeDegradationWhenGatewayIsNull() {
        RetrievalCandidate c1 = createCandidate("doc1.md", "标题", "系统架构设计", 0.03);

        NeuralModelReranker reranker = new NeuralModelReranker(
                null,
                new SemanticHeuristicReranker(),
                objectMapper,
                null,
                20,
                3000
        );

        List<RetrievalCandidate> results = reranker.rerank("系统架构", List.of(c1), 1);

        assertThat(results).hasSize(1);
        RetrievalCandidate cand = results.getFirst();
        assertThat(cand.metadata()).containsEntry("rerankerDegraded", true);
        assertThat(cand.metadata()).containsEntry("rerankSource", "degraded");
    }

    @Test
    void testSafeDegradationOnTimeout() {
        RetrievalCandidate c1 = createCandidate("doc1.md", "标题", "正文内容", 0.03);

        StubChatModelGateway slowGateway = new StubChatModelGateway(command -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            return new ChatCompletionResult("{\"0\": 0.9}", "MOCK", "mock-model", 50, 10, 500L);
        });

        // 超时设定为极短 50ms
        NeuralModelReranker reranker = new NeuralModelReranker(
                slowGateway,
                new SemanticHeuristicReranker(),
                objectMapper,
                Executors.newVirtualThreadPerTaskExecutor(),
                20,
                50
        );

        List<RetrievalCandidate> results = reranker.rerank("查询", List.of(c1), 1);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().metadata()).containsEntry("rerankerDegraded", true);
    }

    @Test
    void testSafeDegradationOnModelException() {
        RetrievalCandidate c1 = createCandidate("doc1.md", "标题", "正文内容", 0.03);

        StubChatModelGateway errorGateway = new StubChatModelGateway(command -> {
            throw new RuntimeException("Model upstream 503 error");
        });

        NeuralModelReranker reranker = new NeuralModelReranker(
                errorGateway,
                new SemanticHeuristicReranker(),
                objectMapper,
                Executors.newVirtualThreadPerTaskExecutor(),
                20,
                3000
        );

        List<RetrievalCandidate> results = reranker.rerank("查询", List.of(c1), 1);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().metadata()).containsEntry("rerankerDegraded", true);
    }
}
