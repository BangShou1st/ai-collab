package com.shitulelv.aicollab.knowledge.application.view;

import java.util.List;

public record KnowledgeEvalRunDetailView(
        KnowledgeEvalRunView run,
        List<KnowledgeEvalResultView> results) {
}
