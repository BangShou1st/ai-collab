package com.shitulelv.aicollab.retrieval.query;

import java.util.List;
import java.util.Objects;

/**
 * Encapsulates the clean semantic query string and extracted structured metadata filters.
 */
public record StructuredQuery(
        String semanticQuery,
        List<MetadataFilter> filters) {

    public StructuredQuery {
        semanticQuery = semanticQuery == null ? "" : semanticQuery.strip();
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    public boolean hasFilters() {
        return !filters.isEmpty();
    }

    public List<MetadataFilter> getFiltersForField(String field) {
        if (field == null) return List.of();
        return filters.stream()
                .filter(f -> field.equalsIgnoreCase(f.field()))
                .toList();
    }

    public static StructuredQuery of(String semanticQuery) {
        return new StructuredQuery(semanticQuery, List.of());
    }

    public static StructuredQuery of(String semanticQuery, List<MetadataFilter> filters) {
        return new StructuredQuery(semanticQuery, filters);
    }

    public StructuredQuery withSemanticQuery(String newSemanticQuery) {
        return new StructuredQuery(newSemanticQuery, this.filters);
    }
}
