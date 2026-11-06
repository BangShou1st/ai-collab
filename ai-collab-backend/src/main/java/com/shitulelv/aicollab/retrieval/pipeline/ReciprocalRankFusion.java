package com.shitulelv.aicollab.retrieval.pipeline;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Reciprocal Rank Fusion (RRF) 融合策略实现。
 *
 * <p>公式：RRF_Score = sum(w / (k + rank))，默认常数 k = 60。
 * <p>去重规则：严格基于 Chunk ID 去重，严禁仅按文本哈希去重（防止不同文档的相同文本被错误合并，抹杀来源身份）。
 * <p>稳定排序：按 rrfScore 降序，相同时按相似度最高值降序，再相同时按 chunkId 排序。
 */
@Component
public class ReciprocalRankFusion implements FusionStrategy {

    public static final int DEFAULT_K = 60;
    public static final double DEFAULT_DENSE_WEIGHT = 1.0;
    public static final double DEFAULT_LEXICAL_WEIGHT = 1.0;
    private static final double EPSILON = 1e-9;

    private final int k;
    private final double denseWeight;
    private final double lexicalWeight;

    public ReciprocalRankFusion() {
        this(DEFAULT_K, DEFAULT_DENSE_WEIGHT, DEFAULT_LEXICAL_WEIGHT);
    }

    public ReciprocalRankFusion(int k, double denseWeight, double lexicalWeight) {
        if (k <= 0) {
            throw new IllegalArgumentException("RRF 常数 k 必须为正整数");
        }
        this.k = k;
        this.denseWeight = denseWeight;
        this.lexicalWeight = lexicalWeight;
    }

    @Override
    public List<RetrievalCandidate> fuse(List<RetrievalCandidate> denseCandidates,
                                         List<RetrievalCandidate> lexicalCandidates,
                                         int topK) {
        if ((denseCandidates == null || denseCandidates.isEmpty())
                && (lexicalCandidates == null || lexicalCandidates.isEmpty())) {
            return List.of();
        }

        // 以 Chunk ID (UUID) 为唯一凭据去重聚合，严禁用 content 或 contentHash 去重
        Map<UUID, FusionAccumulator> map = new LinkedHashMap<>();

        if (denseCandidates != null) {
            for (int i = 0; i < denseCandidates.size(); i++) {
                RetrievalCandidate dc = denseCandidates.get(i);
                int rank = i + 1;
                FusionAccumulator acc = map.computeIfAbsent(dc.id(), id -> new FusionAccumulator(dc));
                acc.denseRank = rank;
                acc.denseSimilarity = dc.denseSimilarity();
                acc.denseScoreContribution = denseWeight / (k + rank);
            }
        }

        if (lexicalCandidates != null) {
            for (int j = 0; j < lexicalCandidates.size(); j++) {
                RetrievalCandidate lc = lexicalCandidates.get(j);
                int rank = j + 1;
                FusionAccumulator acc = map.computeIfAbsent(lc.id(), id -> new FusionAccumulator(lc));
                acc.lexicalRank = rank;
                acc.lexicalScore = lc.lexicalScore();
                acc.lexicalScoreContribution = lexicalWeight / (k + rank);
            }
        }

        List<RetrievalCandidate> fused = new ArrayList<>(map.size());
        for (FusionAccumulator acc : map.values()) {
            double totalRrf = acc.denseScoreContribution + acc.lexicalScoreContribution;
            RetrievalCandidate candidate = acc.baseCandidate.withFusion(
                    acc.denseRank,
                    acc.denseSimilarity,
                    acc.lexicalRank,
                    acc.lexicalScore,
                    totalRrf
            );
            fused.add(candidate);
        }

        // 稳定排序比较器：rrfScore 降序 -> 最高相似度降序 -> chunkId 升序
        fused.sort((a, b) -> {
            int scoreCmp = Double.compare(b.rrfScore(), a.rrfScore());
            if (scoreCmp != 0) {
                return scoreCmp;
            }

            double simA = Math.max(
                    a.denseSimilarity() != null ? a.denseSimilarity() : 0.0,
                    a.lexicalScore() != null ? a.lexicalScore() : 0.0
            );
            double simB = Math.max(
                    b.denseSimilarity() != null ? b.denseSimilarity() : 0.0,
                    b.lexicalScore() != null ? b.lexicalScore() : 0.0
            );
            if (Math.abs(simA - simB) > EPSILON) {
                return Double.compare(simB, simA);
            }

            return a.id().compareTo(b.id());
        });

        if (topK > 0 && fused.size() > topK) {
            return new ArrayList<>(fused.subList(0, topK));
        }
        return fused;
    }

    private static class FusionAccumulator {
        private final RetrievalCandidate baseCandidate;
        private Integer denseRank;
        private Double denseSimilarity;
        private double denseScoreContribution = 0.0;
        private Integer lexicalRank;
        private Double lexicalScore;
        private double lexicalScoreContribution = 0.0;

        private FusionAccumulator(RetrievalCandidate baseCandidate) {
            this.baseCandidate = baseCandidate;
        }
    }
}
