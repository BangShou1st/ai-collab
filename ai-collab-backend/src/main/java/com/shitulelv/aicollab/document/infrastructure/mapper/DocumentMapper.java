package com.shitulelv.aicollab.document.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRecoveryCandidate;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentProcessingAttempt;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface DocumentMapper extends BaseMapper<DocumentEntity> {
    String VIEW = """
            SELECT d.*, u.display_name AS uploaded_by_display_name
            FROM project_document d
            JOIN app_user u ON u.id=d.uploaded_by
            """;

    @Select(VIEW + " WHERE d.project_id=#{projectId} ORDER BY d.created_at DESC")
    List<DocumentEntity> listScoped(@Param("projectId") UUID projectId);

    @Select(VIEW + " WHERE d.project_id=#{projectId} AND d.id=#{documentId}")
    Optional<DocumentEntity> findScoped(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId);

    @Select("SELECT count(*) FROM project_document WHERE project_id=#{projectId} AND status<>'DELETING'")
    int countActive(@Param("projectId") UUID projectId);

    @Update("""
            UPDATE project_document
            SET status='PARSING', processing_token=#{processingToken},
                processing_heartbeat_at=now(), error_message=NULL, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId} AND status='UPLOADED'
            """)
    int claim(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId,
              @Param("processingToken") UUID processingToken);

    @Update("""
            UPDATE project_document
            SET processing_heartbeat_at=now(), updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId}
              AND status=#{status} AND processing_token=#{processingToken}
            """)
    int heartbeat(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId,
                  @Param("processingToken") UUID processingToken, @Param("status") DocumentStatus status);

    @Update("""
            UPDATE project_document
            SET status='INDEXING', parser_type=#{parserType}, error_message=NULL,
                processing_heartbeat_at=now(), updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId} AND status='PARSING'
              AND processing_token=#{processingToken}
            """)
    int markIndexing(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId,
                     @Param("processingToken") UUID processingToken,
                     @Param("parserType") String parserType);

    @Update("""
            UPDATE project_document
            SET status='READY', chunk_count=#{chunkCount}, embedding_provider=#{provider},
                embedding_model=#{model}, embedding_dimension=#{dimension}, indexed_at=now(),
                error_message=NULL, processing_token=NULL, processing_heartbeat_at=NULL, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId} AND status='INDEXING'
              AND processing_token=#{processingToken}
            """)
    int markReady(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId,
                  @Param("processingToken") UUID processingToken,
                  @Param("chunkCount") int chunkCount, @Param("provider") String provider,
                  @Param("model") String model, @Param("dimension") int dimension);

    @Update("""
            UPDATE project_document SET status='FAILED', error_message=#{message},
                processing_token=NULL, processing_heartbeat_at=NULL, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId} AND status IN ('PARSING','INDEXING')
              AND processing_token=#{processingToken}
            """)
    int markFailed(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId,
                   @Param("processingToken") UUID processingToken,
                   @Param("message") String message);

    @Update("""
            UPDATE project_document SET status='UPLOADED', error_message=NULL, chunk_count=0,
                parser_type=NULL, embedding_provider=NULL, embedding_model=NULL,
                embedding_dimension=NULL, indexed_at=NULL, processing_token=NULL,
                processing_heartbeat_at=NULL, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId} AND status='FAILED'
            """)
    int resetFailed(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId);

    @Update("""
            UPDATE project_document SET status='DELETING', processing_token=NULL,
                processing_heartbeat_at=NULL, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId} AND status<>'DELETING'
            """)
    int markDeleting(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId);

    @Delete("DELETE FROM document_chunk WHERE project_id=#{projectId} AND document_id=#{documentId}")
    int deleteChunks(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId);

    @Delete("DELETE FROM project_document WHERE project_id=#{projectId} AND id=#{documentId} AND status='DELETING'")
    int deleteDocument(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId);

    @Insert("""
            INSERT INTO document_chunk(
              id, project_id, document_id, chunk_no, heading, content, content_hash,
              token_estimate, metadata, embedding_provider, embedding_model,
              embedding_dimension, embedding)
            VALUES(
              #{id}, #{projectId}, #{documentId}, #{chunk.chunkNo}, #{chunk.heading},
              #{chunk.content}, #{chunk.contentHash}, #{chunk.tokenEstimate},
              CAST(#{metadata} AS jsonb), #{provider}, #{model}, #{dimension},
              CAST(#{embedding} AS vector))
            """)
    int insertChunk(@Param("id") UUID id, @Param("projectId") UUID projectId,
                    @Param("documentId") UUID documentId, @Param("chunk") DocumentChunk chunk,
                    @Param("metadata") String metadata, @Param("provider") String provider,
                    @Param("model") String model, @Param("dimension") int dimension,
                    @Param("embedding") String embedding);

    @Select("""
            SELECT status, processing_token AS processingToken, uploaded_by AS uploadedBy
            FROM project_document
            WHERE project_id=#{projectId} AND id=#{documentId}
            FOR UPDATE
            """)
    Optional<DocumentProcessingAttempt> lockAttempt(@Param("projectId") UUID projectId,
                                                    @Param("documentId") UUID documentId);

    @Select("""
            SELECT project_id AS projectId, id AS documentId, status,
                   processing_token AS processingToken
            FROM project_document
            WHERE status='UPLOADED'
               OR (status IN ('PARSING','INDEXING')
                   AND processing_token IS NOT NULL
                   AND processing_heartbeat_at < #{staleBefore})
            ORDER BY CASE WHEN status IN ('PARSING','INDEXING') THEN 0 ELSE 1 END, created_at
            LIMIT 100
            """)
    List<DocumentRecoveryCandidate> recoverable(@Param("staleBefore") OffsetDateTime staleBefore);

    @Update("""
            UPDATE project_document SET status='FAILED',
                error_message='文档处理心跳超时，请重试', processing_token=NULL,
                processing_heartbeat_at=NULL, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId}
              AND status=#{status} AND processing_token=#{processingToken}
              AND processing_heartbeat_at < #{staleBefore}
            """)
    int failStale(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId,
                  @Param("status") DocumentStatus status,
                  @Param("processingToken") UUID processingToken,
                  @Param("staleBefore") OffsetDateTime staleBefore);

    @Select("""
            <script>
            SELECT c.id, c.document_id AS documentId,
                   d.original_filename AS originalFilename,
                   c.heading, c.content, c.content_hash AS contentHash,
                   1 - (c.embedding &lt;=> CAST(#{embedding} AS vector)) AS similarity
            FROM document_chunk c
            JOIN project_document d ON d.id=c.document_id AND d.project_id=c.project_id
            WHERE c.project_id=#{projectId} AND d.status='READY'
              AND c.embedding_provider=#{provider} AND c.embedding_model=#{model}
              AND c.embedding_dimension=#{dimension}
              <if test="documentIds != null and !documentIds.isEmpty()">
                AND d.id IN
                <foreach collection="documentIds" item="id" open="(" separator="," close=")">
                  #{id}
                </foreach>
              </if>
            ORDER BY similarity DESC
            LIMIT #{topK}
            </script>
            """)
    List<DocumentSearchHit> search(@Param("projectId") UUID projectId,
                                   @Param("embedding") String embedding,
                                   @Param("provider") String provider,
                                   @Param("model") String model,
                                   @Param("dimension") int dimension,
                                   @Param("documentIds") List<UUID> documentIds,
                                   @Param("topK") int topK);

    @Select("""
            <script>
            SELECT count(*) FROM project_document
            WHERE project_id=#{projectId} AND status='READY' AND id IN
            <foreach collection="documentIds" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            </script>
            """)
    int countReadyDocuments(@Param("projectId") UUID projectId,
                            @Param("documentIds") List<UUID> documentIds);
}
