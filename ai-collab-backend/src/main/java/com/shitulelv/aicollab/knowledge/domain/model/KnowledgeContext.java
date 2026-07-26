package com.shitulelv.aicollab.knowledge.domain.model;

import java.util.List;

public record KnowledgeContext(List<KnowledgeSource> sources, String promptSources) {
}
