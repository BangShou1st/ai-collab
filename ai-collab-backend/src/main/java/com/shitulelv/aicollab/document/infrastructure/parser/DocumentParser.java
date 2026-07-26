package com.shitulelv.aicollab.document.infrastructure.parser;

public interface DocumentParser {
    ParsedDocument parse(byte[] content, String filename, String mimeType);
}
