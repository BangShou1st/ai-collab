package com.shitulelv.aicollab.document.infrastructure.parser;

import java.util.List;

public record ParsedDocument(String parserType, String text, List<PageBoundary> pageBoundaries) {

    public ParsedDocument(String parserType, String text) {
        this(parserType, text, List.of());
    }

    public record PageBoundary(int pageNumber, int charOffset) {
    }
}
