package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.view.DocumentView;
import com.shitulelv.aicollab.document.application.view.DownloadUrlView;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.domain.service.DocumentFilePolicy;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.policy.ProjectWriteGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentApplicationService {
    private static final Logger log = LoggerFactory.getLogger(DocumentApplicationService.class);
    private final DocumentRepository documents;
    private final DocumentStorageGateway storage;
    private final DocumentFilePolicy filePolicy;
    private final ProjectAccessGuard accessGuard;
    private final ProjectWriteGuard writeGuard;
    private final DocumentRegistrationService registration;
    private final DocumentRetryService retryService;
    private final DocumentDeletionService deletionService;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public DocumentApplicationService(DocumentRepository documents, DocumentStorageGateway storage,
                                      DocumentFilePolicy filePolicy, ProjectAccessGuard accessGuard,
                                      ProjectWriteGuard writeGuard,
                                      DocumentRegistrationService registration,
                                      DocumentRetryService retryService,
                                      DocumentDeletionService deletionService,
                                      AuditService audit,
                                      ApplicationEventPublisher events) {
        this.documents = documents;
        this.storage = storage;
        this.filePolicy = filePolicy;
        this.accessGuard = accessGuard;
        this.writeGuard = writeGuard;
        this.registration = registration;
        this.retryService = retryService;
        this.deletionService = deletionService;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<DocumentView> list(UUID projectId, UUID userId) {
        accessGuard.requireMember(projectId, userId);
        return documents.list(projectId).stream().map(DocumentApplicationService::view).toList();
    }

    @Transactional(readOnly = true)
    public DocumentView get(UUID projectId, UUID documentId, UUID userId) {
        accessGuard.requireMember(projectId, userId);
        return view(requireDocument(projectId, documentId));
    }

    public DocumentView upload(UUID projectId, MultipartFile file, String displayName, UUID userId) {
        accessGuard.requireAdmin(projectId, userId);
        writeGuard.requireWritable(projectId);
        DocumentFilePolicy.ValidatedFile valid = filePolicy.validate(file, displayName);
        UUID documentId = UUID.randomUUID();
        String objectKey = "projects/" + projectId + "/documents/" + documentId
                + "/source." + valid.extension().toLowerCase(Locale.ROOT);
        try (InputStream input = file.getInputStream()) {
            storage.put(objectKey, input, file.getSize(), valid.mimeType());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE);
        }

        DocumentEntity entity = new DocumentEntity();
        entity.setId(documentId);
        entity.setProjectId(projectId);
        entity.setDisplayName(valid.displayName());
        entity.setOriginalFilename(valid.originalFilename());
        entity.setMimeType(valid.mimeType());
        entity.setSizeBytes(file.getSize());
        entity.setObjectKey(objectKey);
        entity.setStatus(DocumentStatus.UPLOADED);
        entity.setChunkCount(0);
        entity.setUploadedBy(userId);
        try {
            registration.registerUploadedDocument(entity);
        } catch (RuntimeException exception) {
            compensateObject(objectKey);
            throw exception;
        }
        return view(requireDocument(projectId, documentId));
    }

    public DocumentView retry(UUID projectId, UUID documentId, UUID userId) {
        accessGuard.requireAdmin(projectId, userId);
        writeGuard.requireWritable(projectId);
        DocumentEntity document = requireDocument(projectId, documentId);
        if (document.getStatus() != DocumentStatus.FAILED) {
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT);
        }
        try (var ignored = storage.open(document.getObjectKey())) {
            // 仅确认原文件仍然存在，不读取或记录正文。
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE);
        }
        retryService.request(projectId, documentId, userId);
        return view(requireDocument(projectId, documentId));
    }

    public DownloadUrlView downloadUrl(UUID projectId, UUID documentId, UUID userId) {
        accessGuard.requireMember(projectId, userId);
        DocumentEntity document = requireDocument(projectId, documentId);
        if (document.getStatus() == DocumentStatus.DELETING) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_READY);
        }
        return storage.presign(document.getObjectKey(), document.getOriginalFilename(), Duration.ofMinutes(5));
    }

    public void delete(UUID projectId, UUID documentId, UUID userId) {
        accessGuard.requireAdmin(projectId, userId);
        writeGuard.requireWritable(projectId);
        DocumentEntity document = requireDocument(projectId, documentId);
        if (document.getStatus() != DocumentStatus.DELETING
                && !documents.markDeleting(projectId, documentId)) {
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT);
        }
        storage.delete(document.getObjectKey());
        deletionService.deleteRows(projectId, documentId, userId);
    }

    @Transactional
    public DocumentView reindex(UUID projectId, UUID documentId, UUID userId) {
        accessGuard.requireAdmin(projectId, userId);
        writeGuard.requireWritable(projectId);
        DocumentEntity document = requireDocument(projectId, documentId);
        if (document.getStatus() != DocumentStatus.READY) {
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT,
                    "只有已完成索引的文档可以重新索引");
        }
        if (!documents.resetForReindex(projectId, documentId)) {
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT);
        }
        audit.write(projectId, userId, "DOCUMENT_REINDEX_REQUESTED", "PROJECT_DOCUMENT", documentId,
                Map.of("originalFilename", document.getOriginalFilename()));
        events.publishEvent(new DocumentUploadedEvent(projectId, documentId));
        return view(documents.find(projectId, documentId).orElse(document));
    }

    private DocumentEntity requireDocument(UUID projectId, UUID documentId) {
        return documents.find(projectId, documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    private void compensateObject(String objectKey) {
        try {
            storage.delete(objectKey);
        } catch (RuntimeException exception) {
            log.error("文档注册失败后对象补偿删除失败，objectKeyDigest={}", shortDigest(objectKey));
        }
    }

    private static String shortDigest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private static DocumentView view(DocumentEntity item) {
        return new DocumentView(item.getId(), item.getProjectId(), item.getDisplayName(),
                item.getOriginalFilename(), item.getMimeType(), item.getSizeBytes(), item.getStatus(),
                item.getParserType(), item.getChunkCount(), item.getEmbeddingProvider(),
                item.getEmbeddingModel(), item.getEmbeddingDimension(), item.getErrorMessage(),
                item.getUploadedBy(), item.getUploadedByDisplayName(), item.getIndexedAt(),
                item.getVersion(), item.getCreatedAt(), item.getUpdatedAt());
    }
}
