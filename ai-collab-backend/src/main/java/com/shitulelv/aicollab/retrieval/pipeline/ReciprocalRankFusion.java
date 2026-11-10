package com.shitulelv.aicollab.retrieval.pipeline;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Reciprocal Rank Fusion (RRF) 融合策略实现。
 *
 * <p>公式：RRF_Score = sum(w / (k + rank))，默认常数 k = 60。
 * <p>去重规则：严格基于 Chunk ID 去重，严禁仅按文本哈希去重（防止不同文档的相同文本被错误合并，抹杀来源身份）。
 * <p>稳定排序：按 rrfScore 降序，相同时双路召回者优先、再按最优排名升序，最后按 chunkId 排序
 * （tie-break 全部使用排名空间证据，不比较 Dense/Lexical 两类不同分数体系）。
 */
@Component
public class ReciprocalRankFusion implements FusionStrategy {

    public static final int DEFAULT_K = 60;
    public static final double DEFAULT_DENSE_WEIGHT = 1.0;
    public static final double DEFAULT_LEXICAL_WEIGHT = 1.0;

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

        // 稳定排序比较器：rrfScore 降序 -> 双路召回优先 -> 最优排名更小者优先 -> chunkId 升序。
        // tie-break 一律使用排名空间证据（排名/路数），严禁比较 Dense 余弦与 Lexical 匹配强度
        // 这两类不同分数体系（历史缺陷：混用导致 RRF 平分时按不可比分数决定次序）。
        fused.sort((a, b) -> {
            int scoreCmp = Double.compare(b.rrfScore(), a.rrfScore());
            if (scoreCmp != 0) {
                return scoreCmp;
            }

            int routesA = (a.denseRank() != null ? 1 : 0) + (a.lexicalRank() != null ? 1 : 0);
            int routesB = (b.denseRank() != null ? 1 : 0) + (b.lexicalRank() != null ? 1 : 0);
            if (routesA != routesB) {
                return Integer.compare(routesB, routesA);
            }

            int bestRankA = bestRank(a);
            int bestRankB = bestRank(b);
            if (bestRankA != bestRankB) {
                return Integer.compare(bestRankA, bestRankB);
            }

            return a.id().compareTo(b.id());
        });

        if (topK > 0 && fused.size() > topK) {
            return new ArrayList<>(fused.subList(0, topK));
        }
        return fused;
    }

    /** 最优排名（两路中更靠前的名次）；两路都缺失时返回 Integer.MAX_VALUE，仅作 tie-break 用。 */
    private static int bestRank(RetrievalCandidate candidate) {
        int dense = candidate.denseRank() != null ? candidate.denseRank() : Integer.MAX_VALUE;
        int lexical = candidate.lexicalRank() != null ? candidate.lexicalRank() : Integer.MAX_VALUE;
        return Math.min(dense, lexical);
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
