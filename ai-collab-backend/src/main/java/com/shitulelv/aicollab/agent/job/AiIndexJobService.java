package com.shitulelv.aicollab.agent.job;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * P2 三类索引任务的统一入队服务（契约 §4.2、R4-1、R5-1）。
 *
 * <p>在业务事务内创建 {@code ai_job(status='QUEUED')} 并发布 {@link AiJobQueuedEvent}，
 * 由 {@link AiTaskStreamPublisher} 在 {@code AFTER_COMMIT} 后执行 {@code XADD}。</p>
 */
@Service
public class AiIndexJobService {

    private final AiJobRepository repository;
    private final AiJobProperties properties;
    private final ApplicationEventPublisher events;

    public AiIndexJobService(AiJobRepository repository, AiJobProperties properties,
            ApplicationEventPublisher events) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** 常规文档索引任务（{@code generationId=null}，由 Python 同时写入 ACTIVE 与 BUILDING）。 */
    @Transactional
    public AiJobRecord enqueueIndexDocument(UUID projectId, UUID documentId) {
        return enqueueIndexDocument(projectId, documentId, null);
    }

    /** 文档索引任务；指定 {@code generationId} 时只写入该 generation。 */
    @Transactional
    public AiJobRecord enqueueIndexDocument(UUID projectId, UUID documentId, UUID generationId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(documentId, "documentId");
        AiJobRecord job = repository.createIndexJob(
                AiJobTypes.INDEX_DOCUMENT, projectId, documentId, generationId, null, properties.maxAttempts());
        events.publishEvent(new AiJobQueuedEvent(job));
        return job;
    }

    /** 文档索引删除任务（覆盖该文档在所有 generation 下的索引行）。 */
    @Transactional
    public AiJobRecord enqueueDeleteDocumentIndex(UUID projectId, UUID documentId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(documentId, "documentId");
        AiJobRecord job = repository.createIndexJob(
                AiJobTypes.DELETE_DOCUMENT_INDEX, projectId, documentId, null, null, properties.maxAttempts());
        events.publishEvent(new AiJobQueuedEvent(job));
        return job;
    }

    /** 全站重建 generation 任务（{@code project_id=null}，由 Python 创建 BUILDING generation 后扇出）。 */
    @Transactional
    public AiJobRecord enqueueReindexGeneration() {
        AiJobRecord job = repository.createIndexJob(
                AiJobTypes.REINDEX_GENERATION, null, null, null, null, properties.maxAttempts());
        events.publishEvent(new AiJobQueuedEvent(job));
        return job;
    }

    /** 重建对账补派子任务：在同一事务内批量入队 {@code INDEX_DOCUMENT}，提交后由监听方 XADD。 */
    @Transactional
    public List<AiJobRecord> enqueueReindexChildren(UUID parentJobId, UUID generationId,
            List<AiIndexJobStore.DocumentRef> documents) {
        Objects.requireNonNull(parentJobId, "parentJobId");
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(documents, "documents");
        if (documents.isEmpty()) {
            return List.of();
        }
        List<AiJobRecord> created = new ArrayList<>(documents.size());
        for (AiIndexJobStore.DocumentRef doc : documents) {
            AiJobRecord child = repository.createIndexJob(
                    AiJobTypes.INDEX_DOCUMENT, doc.projectId(), doc.documentId(),
                    generationId, parentJobId, properties.maxAttempts());
            events.publishEvent(new AiJobQueuedEvent(child));
            created.add(child);
        }
        return List.copyOf(created);
    }
}
