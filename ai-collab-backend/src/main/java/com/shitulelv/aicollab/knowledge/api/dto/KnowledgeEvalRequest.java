package com.shitulelv.aicollab.knowledge.api.dto;

import java.util.List;

public record KnowledgeEvalRequest(List<TestCase> testCases) {

    public record TestCase(String question, List<String> expectedDocumentIds) {
    }
}
