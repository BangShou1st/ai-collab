package com.shitulelv.aicollab.retrieval.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * 神经网络大模型真实语义重排器。
 *
 * <p>复用现有 ChatModelGateway 执行跨文档段落与用户查询的细粒度语义相关性重排评分。
 * <p>支持有界候选池（最多 20 条）与 3000ms 硬超时保护。
 * <p>当未配置真实模型网关、调用超时或解析异常时，平滑降级至 SemanticHeuristicReranker 并标记 rerankerDegraded = true。
 * <p>严格遵守不可变证据契约：仅重排顺序并填充 rerankScore 与 rerankSource，严禁篡改原始证据身份、坐标与正文。
 */
@Component
public class NeuralModelReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(NeuralModelReranker.class);
    public static final String NAME = "neural-model";
    public static final int DEFAULT_MAX_CANDIDATES = 20;
    public static final long DEFAULT_TIMEOUT_MS = 3000L;

    private static final String SYSTEM_PROMPT = """
            You are a specialized semantic relevance evaluation reranker.
            Given a user search query and a numbered list of candidate text passages, evaluate each passage's direct semantic relevance to answering the query.
            Output a JSON object mapping each candidate index to a relevance score between 0.00 and 1.00 (e.g. {"0": 0.95, "1": 0.40, "2": 0.10}).
            Only output the valid JSON object. Do not include markdown code fence formatting or conversational text.
            """;

    private final ChatModelGateway chatModelGateway;
    private final SemanticHeuristicReranker fallbackReranker;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final int maxCandidates;
    private final long timeoutMs;

    public NeuralModelReranker() {
        this(null, new SemanticHeuristicReranker(), new ObjectMapper(), null, DEFAULT_MAX_CANDIDATES, DEFAULT_TIMEOUT_MS);
    }

    @Autowired
    public NeuralModelReranker(
            @Autowired(required = false) ChatModelGateway chatModelGateway,
            SemanticHeuristicReranker fallbackReranker,
            ObjectMapper objectMapper) {
        this(chatModelGateway, fallbackReranker, objectMapper, null, DEFAULT_MAX_CANDIDATES, DEFAULT_TIMEOUT_MS);
    }

    public NeuralModelReranker(
            ChatModelGateway chatModelGateway,
            SemanticHeuristicReranker fallbackReranker,
            ObjectMapper objectMapper,
            ExecutorService executor,
            int maxCandidates,
            long timeoutMs) {
        this.chatModelGateway = chatModelGateway;
        this.fallbackReranker = fallbackReranker != null ? fallbackReranker : new SemanticHeuristicReranker();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.maxCandidates = maxCandidates > 0 ? maxCandidates : DEFAULT_MAX_CANDIDATES;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        if (executor != null) {
            this.executor = executor;
        } else {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        if (query == null || query.isBlank() || chatModelGateway == null) {
            log.info("NeuralModelReranker 未配置真实模型或查询为空，安全降级至 SemanticHeuristicReranker");
            return safeDegrade(query, candidates, topN);
        }

        List<RetrievalCandidate> target = candidates;
        if (target.size() > maxCandidates) {
            target = new ArrayList<>(target.subList(0, maxCandidates));
        }

        try {
            List<RetrievalCandidate> finalTarget = target;
            Future<List<RetrievalCandidate>> future = executor.submit(() -> doNeuralRerank(query, finalTarget, topN));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("NeuralModelReranker 执行超时 ({}ms)，安全降级至 SemanticHeuristicReranker", timeoutMs);
            return safeDegrade(query, candidates, topN);
        } catch (Throwable t) {
            log.warn("NeuralModelReranker 执行异常，安全降级至 SemanticHeuristicReranker: {}", t.getMessage());
            return safeDegrade(query, candidates, topN);
        }
    }

    private List<RetrievalCandidate> doNeuralRerank(String query, List<RetrievalCandidate> candidates, int topN) {
        String userPrompt = buildRerankPrompt(query, candidates);
        ChatCompletionCommand command = new ChatCompletionCommand(
                null,
                SYSTEM_PROMPT,
                userPrompt,
                ChatCompletionCommand.OutputFormat.JSON_OBJECT,
                ModelPurpose.KNOWLEDGE_CHAT,
                null,
                List.of(),
                null
        );

        ChatCompletionResult completion = chatModelGateway.complete(command);
        if (completion == null || completion.content() == null || completion.content().isBlank()) {
            throw new IllegalStateException("Neural model returned empty response");
        }

        Map<Integer, Double> scores = parseScores(completion.content(), candidates.size());
        if (scores.isEmpty()) {
            throw new IllegalStateException("Failed to parse neural reranker scores from response");
        }

        List<RetrievalCandidate> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalCandidate candidate = candidates.get(i);
            double score = scores.getOrDefault(i, 0.0);

            Map<String, Object> extraMeta = new LinkedHashMap<>();
            extraMeta.put("rerankSource", NAME);

            // 严格遵守证据不可变契约：保留原始 chunkId、documentId、heading、content、contentHash、metadata
            scored.add(candidate.withMetadata(extraMeta).withRerank(score, NAME));
        }

        // 稳定排序比较器：neuralScore 降序 -> rrfScore 降序 -> id 升序
        scored.sort((a, b) -> {
            int scoreCmp = Double.compare(b.rerankScore(), a.rerankScore());
            if (scoreCmp != 0) {
                return scoreCmp;
            }
            double rrfA = a.rrfScore() != null ? a.rrfScore() : 0.0;
            double rrfB = b.rrfScore() != null ? b.rrfScore() : 0.0;
            int rrfCmp = Double.compare(rrfB, rrfA);
            if (rrfCmp != 0) {
                return rrfCmp;
            }
            return a.id().compareTo(b.id());
        });

        if (topN > 0 && scored.size() > topN) {
            return new ArrayList<>(scored.subList(0, topN));
        }
        return scored;
    }

    private String buildRerankPrompt(String query, List<RetrievalCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Query: ").append(query.trim()).append("\n\nCandidate Passages:\n");
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalCandidate c = candidates.get(i);
            sb.append("[").append(i).append("] ");
            if (c.heading() != null && !c.heading().isBlank()) {
                sb.append("Title: ").append(c.heading().trim()).append(" | ");
            }
            String text = c.effectiveContent();
            if (text.length() > 400) {
                text = text.substring(0, 400) + "...";
            }
            sb.append("Content: ").append(text.replace("\n", " ")).append("\n\n");
        }
        return sb.toString();
    }

    private Map<Integer, Double> parseScores(String content, int expectedCount) {
        Map<Integer, Double> scores = new HashMap<>();
        String cleaned = content.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        try {
            JsonNode root = objectMapper.readTree(cleaned);
            if (root.isObject()) {
                root.fieldNames().forEachRemaining(key -> {
                    try {
                        int idx = Integer.parseInt(key.trim());
                        JsonNode val = root.get(key);
                        if (val.isNumber()) {
                            scores.put(idx, val.asDouble());
                        } else if (val.isTextual()) {
                            scores.put(idx, Double.parseDouble(val.asText().trim()));
                        }
                    } catch (Exception ignored) {
                    }
                });
            } else if (root.isArray()) {
                for (int i = 0; i < root.size(); i++) {
                    JsonNode node = root.get(i);
                    if (node.isObject()) {
                        int idx = node.has("index") ? node.get("index").asInt() : i;
                        double score = node.has("score") ? node.get("score").asDouble() : 0.0;
                        scores.put(idx, score);
                    } else if (node.isNumber()) {
                        scores.put(i, node.asDouble());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("无法直接以 JSON 解析神经重排输出: {}", e.getMessage());
        }
        return scores;
    }

    private List<RetrievalCandidate> safeDegrade(String query, List<RetrievalCandidate> candidates, int topN) {
        List<RetrievalCandidate> fallbackResults = fallbackReranker.rerank(query, candidates, topN);
        List<RetrievalCandidate> degraded = new ArrayList<>(fallbackResults.size());
        for (RetrievalCandidate c : fallbackResults) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("rerankerDegraded", true);
            extra.put("rerankSource", "degraded");
            degraded.add(c.withMetadata(extra));
        }
        return degraded;
    }
}
