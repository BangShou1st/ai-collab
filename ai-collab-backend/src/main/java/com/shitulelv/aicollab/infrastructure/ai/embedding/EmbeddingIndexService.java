package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.shitulelv.aicollab.common.exception.*;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** Build into a separate generation; activation is explicit and never part of remote inference. */
@Service
public class EmbeddingIndexService {
    public record BuildRequested(UUID generationId) {}
    private final JdbcTemplate jdbc;
    private final SystemEmbeddingConfigRepository configs;
    private final ProjectEmbeddingGateway embeddings;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), r -> { Thread t = new Thread(r, "embedding-index"); t.setDaemon(true); return t; });

    public EmbeddingIndexService(JdbcTemplate jdbc, SystemEmbeddingConfigRepository configs,
            ProjectEmbeddingGateway embeddings, ApplicationEventPublisher events, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.configs = configs; this.embeddings = embeddings;
        this.events = events; this.transactions = new TransactionTemplate(manager);
    }

    @Transactional
    public int start(SystemEmbeddingConfig candidate) {
        configs.lockInfrastructure();
        if (jdbc.queryForObject("SELECT count(*) FROM embedding_index_generation WHERE state IN ('RETIRED','FAILED')", Integer.class) >= 3)
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请先清理旧候选索引，最多保留三代退役或失败索引");
        if (jdbc.queryForObject("SELECT count(*) FROM embedding_index_generation WHERE state IN ('QUEUED','BUILDING','READY')", Integer.class) > 0)
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "已有候选索引正在构建或等待激活");
        UUID generation = UUID.randomUUID();
        SystemEmbeddingConfig active = configs.findActive().orElse(null);
        jdbc.update("INSERT INTO embedding_index_generation(id,config_id,base_config_id,base_fingerprint,base_generation_id,state) VALUES (?,?,?,?,?,'QUEUED')",
                generation, candidate.id(), active == null ? null : active.id(), active == null ? null : active.fingerprint(), active == null ? null : active.generationId());
        jdbc.update("""
                INSERT INTO embedding_index_document(generation_id,document_id,source_version,source_indexed_at,state)
                SELECT ?,id,version,indexed_at,'QUEUED' FROM project_document WHERE status='READY'
                """, generation);
        int total = jdbc.queryForObject("SELECT count(*) FROM embedding_index_document WHERE generation_id=?", Integer.class, generation);
        jdbc.update("UPDATE embedding_index_generation SET total=? WHERE id=?", total, generation);
        events.publishEvent(new BuildRequested(generation));
        return total;
    }

    @TransactionalEventListener
    public void schedule(BuildRequested requested) {
        try { executor.submit(() -> build(requested.generationId())); }
        catch (RejectedExecutionException busy) { fail(requested.generationId(), "INDEX_EXECUTOR_BUSY"); }
    }

    public void build(UUID generation) {
        if (jdbc.update("UPDATE embedding_index_generation SET state='BUILDING',updated_at=now() WHERE id=? AND state='QUEUED'", generation) != 1) return;
        try {
            UUID configId = jdbc.queryForObject("SELECT config_id FROM embedding_index_generation WHERE id=?", UUID.class, generation);
            SystemEmbeddingConfig config = configs.findById(configId).orElseThrow();
            UUID sourceGeneration = jdbc.queryForObject("SELECT base_generation_id FROM embedding_index_generation WHERE id=?", UUID.class, generation);
            List<UUID> documents = jdbc.queryForList("SELECT document_id FROM embedding_index_document WHERE generation_id=? ORDER BY document_id", UUID.class, generation);
            for (UUID document : documents) {
                if (Thread.currentThread().isInterrupted()) throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
                jdbc.update("UPDATE embedding_index_document SET state='BUILDING' WHERE generation_id=? AND document_id=?", generation, document);
                List<Map<String,Object>> chunks = jdbc.queryForList("SELECT id,content FROM document_chunk WHERE document_id=? AND generation_id IS NOT DISTINCT FROM ? ORDER BY chunk_no", document, sourceGeneration);
                if (chunks.isEmpty()) throw new BusinessException(ErrorCode.DOCUMENT_NOT_READY);
                var batch = embeddings.embedWithConfig(ProjectEmbeddingGateway.asProjectConfig(config),
                        chunks.stream().map(row -> (String)row.get("content")).toList(), EmbeddingProgressListener.NONE);
                transactions.executeWithoutResult(tx -> {
                    String state = jdbc.queryForObject("SELECT state FROM embedding_index_generation WHERE id=? FOR UPDATE", String.class, generation);
                    if (!"BUILDING".equals(state)) throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT);
                    // A deleted or reprocessed document cannot enter the candidate as a completed snapshot.
                    List<UUID> current = jdbc.queryForList("""
                            SELECT d.id FROM project_document d JOIN embedding_index_document i ON i.document_id=d.id
                            WHERE i.generation_id=? AND d.id=? AND d.status='READY'
                              AND d.version=i.source_version AND d.indexed_at IS NOT DISTINCT FROM i.source_indexed_at FOR UPDATE OF d
                            """, UUID.class, generation, document);
                    if (current.isEmpty()) throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT);
                    for (int n = 0; n < chunks.size(); n++) {
                        int inserted = jdbc.update("""
                                INSERT INTO document_chunk(id,project_id,document_id,chunk_no,heading,content,content_hash,token_estimate,
                                    metadata,embedding_provider,embedding_model,embedding_dimension,embedding_fingerprint,embedding,generation_id)
                                SELECT ?,project_id,document_id,chunk_no,heading,content,content_hash,token_estimate,metadata,?,?,?,?,?::vector,?
                                FROM document_chunk WHERE id=?
                                """, UUID.randomUUID(), config.provider(), config.modelName(), config.dimensions(), config.fingerprint(),
                                batch.vectors().get(n).toString(), generation, chunks.get(n).get("id"));
                        if (inserted != 1) throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT);
                    }
                    jdbc.update("UPDATE embedding_index_document SET state='COMPLETED' WHERE generation_id=? AND document_id=?", generation, document);
                    jdbc.update("UPDATE embedding_index_generation SET completed=completed+1,updated_at=now() WHERE id=?", generation);
                });
            }
            jdbc.update("UPDATE embedding_index_generation SET state='READY',updated_at=now() WHERE id=? AND completed=total AND failed=0", generation);
        } catch (RuntimeException failure) {
            fail(generation, failure instanceof BusinessException b ? b.getErrorCode().name() : "INDEX_BUILD_FAILED");
        }
    }

    private void fail(UUID generation, String code) {
        jdbc.update("UPDATE embedding_index_document SET state='FAILED' WHERE generation_id=? AND state='BUILDING'", generation);
        jdbc.update("UPDATE embedding_index_generation SET state='FAILED',failed=GREATEST(1,total-completed),error_code=?,updated_at=now() WHERE id=?", code, generation);
    }

    public List<Map<String,Object>> list() {
        return jdbc.queryForList("""
                SELECT g.id,g.state,g.total,g.completed,g.failed,g.error_code AS "errorCode",g.created_at AS "createdAt",
                       c.provider,c.model_name AS "modelName",c.dimensions
                FROM embedding_index_generation g JOIN system_embedding_config c ON c.id=g.config_id
                ORDER BY g.created_at DESC LIMIT 10
                """);
    }

    @Transactional
    public void activate(UUID generation) {
        configs.lockInfrastructure();
        var rows = jdbc.queryForList("SELECT * FROM embedding_index_generation WHERE id=? FOR UPDATE", generation);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "候选索引不存在");
        Map<String,Object> row = rows.getFirst();
        if ("ACTIVE".equals(row.get("state"))) return;
        if (!"READY".equals(row.get("state"))) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "候选索引尚未完成");
        // Lock all documents while checking coverage; new uploads use the same infrastructure lock on index commit.
        jdbc.queryForList("SELECT id FROM project_document ORDER BY id FOR UPDATE");
        int changed = jdbc.queryForObject("""
                SELECT count(*) FROM project_document d LEFT JOIN embedding_index_document i
                  ON i.document_id=d.id AND i.generation_id=?
                WHERE (d.status='READY' AND (i.document_id IS NULL OR i.state<>'COMPLETED'
                    OR d.version<>i.source_version OR d.indexed_at IS DISTINCT FROM i.source_indexed_at))
                   OR d.status IN ('UPLOADED','PARSING','INDEXING')
                """, Integer.class, generation);
        if (changed > 0) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文档已变化或仍在处理中，请重新构建候选索引");
        SystemEmbeddingConfig active = configs.findActive().orElse(null);
        if (!Objects.equals(active == null ? null : active.id(), row.get("base_config_id")))
            throw new BusinessException(ErrorCode.EMBEDDING_REINDEX_REQUIRED, "活动配置已变化，请重新构建");
        if (!Objects.equals(active == null ? null : active.fingerprint(), row.get("base_fingerprint"))
                || !Objects.equals(active == null ? null : active.generationId(), row.get("base_generation_id")))
            throw new BusinessException(ErrorCode.EMBEDDING_REINDEX_REQUIRED, "索引空间已变化，请重新构建");
        jdbc.update("UPDATE embedding_index_generation SET state='RETIRED',updated_at=now() WHERE state='ACTIVE'");
        if (active!=null && active.generationId()==null) {
            jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state,total,completed) SELECT ?,?,'RETIRED',count(DISTINCT document_id),count(DISTINCT document_id) FROM document_chunk WHERE generation_id IS NULL AND embedding_fingerprint=?",
                    UUID.randomUUID(),active.id(),active.fingerprint());
        }
        configs.disableAll();
        jdbc.update("UPDATE system_embedding_config SET enabled=true,generation_id=?,updated_at=now() WHERE id=?", generation, row.get("config_id"));
        jdbc.update("UPDATE embedding_index_generation SET state='ACTIVE',updated_at=now() WHERE id=?", generation);
    }

    @Transactional
    public void discard(UUID generation) {
        configs.lockInfrastructure();
        String state = jdbc.queryForObject("SELECT state FROM embedding_index_generation WHERE id=? FOR UPDATE", String.class, generation);
        if (!List.of("READY","FAILED","RETIRED").contains(state))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "仅可清理待激活、失败或退役索引");
        jdbc.update("DELETE FROM document_chunk WHERE generation_id=? OR (generation_id IS NULL AND embedding_fingerprint=(SELECT c.fingerprint FROM embedding_index_generation g JOIN system_embedding_config c ON c.id=g.config_id WHERE g.id=? AND g.state='RETIRED'))", generation,generation);
        jdbc.update("UPDATE system_embedding_config SET generation_id=NULL WHERE generation_id=? AND NOT enabled", generation);
        jdbc.update("DELETE FROM embedding_index_generation WHERE id=?", generation);
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    public void recoverAbandonedBuilds() {
        jdbc.queryForList("SELECT id FROM embedding_index_generation WHERE state='BUILDING' AND updated_at<now()-interval '10 minutes'", UUID.class)
                .forEach(id -> jdbc.update("UPDATE embedding_index_generation SET state='FAILED',failed=GREATEST(1,total-completed),error_code='INDEX_BUILD_INTERRUPTED',updated_at=now() WHERE id=? AND state='BUILDING' AND updated_at<now()-interval '10 minutes'", id));
        jdbc.queryForList("SELECT id FROM embedding_index_generation WHERE state='QUEUED'", UUID.class)
                .forEach(id -> schedule(new BuildRequested(id)));
    }
    @PreDestroy void close() { executor.shutdownNow(); }
}
