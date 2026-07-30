package com.shitulelv.aicollab.work.application.view;

import java.util.List;
import java.util.UUID;

public record DependencyGraphView(
        List<GraphNode> nodes,
        List<GraphEdge> edges
) {
    public record GraphNode(
            UUID id,
            String title,
            String status,
            String assigneeName
    ) {}

    public record GraphEdge(
            UUID source,
            UUID target
    ) {}
}
