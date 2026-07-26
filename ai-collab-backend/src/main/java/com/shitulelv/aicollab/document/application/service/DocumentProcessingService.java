package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingGateway;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.parser.DocumentParser;
import com.shitulelv.aicollab.document.infrastructure.parser.ParsedDocument;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentProcessingService {
    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);
    private static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;
    private final DocumentRepository documents;
    private final DocumentStorageGateway storage;
    private final DocumentParser parser;
    private final DocumentChunker chunker;
    private final EmbeddingGateway embeddingGateway;
    private final DocumentIndexWriter indexWriter;
    private final DocumentFailureRecorder failures;

    public DocumentProcessingService(DocumentRepository documents, DocumentStorageGateway storage,
                                     DocumentParser parser, DocumentChunker chunker,
                                     EmbeddingGateway embeddingGateway, DocumentIndexWriter indexWriter,
                                     DocumentFailureRecorder failures) {
        this.documents = documents;
        this.storage = storage;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingGateway = embeddingGateway;
        this.indexWriter = indexWriter;
        this.failures = failures;
    }

    public void process(UUID projectId, UUID documentId) {
        UUID processingToken = UUID.randomUUID();
        if (!documents.claim(projectId, documentId, processingToken)) return;
        try {
            DocumentEntity document = documents.find(projectId, documentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
            requireHeartbeat(projectId, documentId, processingToken, DocumentStatus.PARSING);
            byte[] source = readSource(document.getObjectKey());
            requireHeartbeat(projectId, documentId, processingToken, DocumentStatus.PARSING);
            ParsedDocument parsed = parser.parse(
                    source, document.getOriginalFilename(), document.getMimeType());
            List<DocumentChunk> chunks = chunker.split(parsed.text());
            if (chunks.isEmpty()) throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED);
            if (!documents.markIndexing(
                    projectId, documentId, processingToken, parsed.parserType())) return;
            EmbeddingBatch embeddings = embeddingGateway.embed(
                    chunks.stream().map(DocumentChunk::content).toList(),
                    () -> requireHeartbeat(
                            projectId, documentId, processingToken, DocumentStatus.INDEXING));
            indexWriter.replaceAndComplete(
                    projectId, documentId, processingToken,
                    chunks, embeddings, document.getOriginalFilename());
        } catch (BusinessException exception) {
            failures.record(projectId, documentId, processingToken, safeMessage(exception));
            log.warn("文档处理失败，documentId={}，code={}", documentId, exception.getErrorCode().name());
        } catch (Exception exception) {
            failures.record(projectId, documentId, processingToken, "文档处理失败，请重试");
            log.error("文档处理发生内部异常，documentId={}，type={}",
                    documentId, exception.getClass().getSimpleName());
        }
    }

    private byte[] readSource(String objectKey) throws IOException {
        try (InputStream input = storage.open(objectKey)) {
            byte[] content = input.readNBytes(MAX_SOURCE_BYTES + 1);
            if (content.length > MAX_SOURCE_BYTES) {
                throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE);
            }
            return content;
        }
    }

    private void requireHeartbeat(UUID projectId, UUID documentId, UUID processingToken,
                                  DocumentStatus status) {
        if (!documents.heartbeat(projectId, documentId, processingToken, status)) {
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT);
        }
    }

    private static String safeMessage(BusinessException exception) {
        return switch (exception.getErrorCode()) {
            case DOCUMENT_EMBEDDING_FAILED -> "文档向量化失败，请检查模型配置后重试";
            case DOCUMENT_STORAGE_UNAVAILABLE -> "文件存储服务暂时不可用，请重试";
            case DOCUMENT_PARSE_FAILED -> exception.getMessage();
            default -> "文档处理失败，请重试";
        };
    }
}
