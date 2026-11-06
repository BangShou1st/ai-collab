package com.shitulelv.aicollab.retrieval.pipeline;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 透传重排器，保持原 RRF 排序与原始证据身份不变。
 */
@Component
public class PassThroughReranker implements Reranker {

    public static final String NAME = "pass-through";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RetrievalCandidate> target = candidates;
        if (topN > 0 && candidates.size() > topN) {
            target = candidates.subList(0, topN);
        }

        List<RetrievalCandidate> result = new ArrayList<>(target.size());
        for (RetrievalCandidate candidate : target) {
            result.add(candidate.withRerank(candidate.rrfScore(), name()));
        }
        return result;
    }
}
