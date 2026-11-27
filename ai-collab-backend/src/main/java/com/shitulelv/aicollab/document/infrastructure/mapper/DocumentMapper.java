package com.shitulelv.aicollab.document.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.common.persistence.PostgresJsonMapTypeHandler;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRecoveryCandidate;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentProcessingAttempt;
import com.shitulelv.aicollab.retrieval.query.DocumentFilterCriteria;
import org.apache.ibatis.annotations.Delete;
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
    String PROCESSABLE = """
              AND NOT access_blocked
              AND NOT EXISTS (SELECT 1 FROM external_document e
                JOIN connector_source s ON s.id=e.source_id AND s.project_id=project_document.project_id
                LEFT JOIN connector_oauth_authorization a ON a.id=s.user_authorization_id AND a.project_id=s.project_id
                WHERE e.document_id=project_document.id AND (e.status<>'ACTIVE' OR s.status<>'ACTIVE'
                  OR (s.user_authorization_id IS NOT NULL AND
                    (a.id IS NULL OR a.status<>'ACTIVE' OR a.access_expires_at<=now()))))
            """;

    @Select("""
            <script>
            SELECT d.id, d.original_filename
            FROM project_document d
            JOIN document_body b ON b.document_id=d.id
            JOIN connector_document_visibility v ON v.id=d.id AND v.project_id=d.project_id
            WHERE d.project_id=#{projectId} AND d.status='READY' AND NOT d.access_blocked AND v.readable
              <if test="requested != null and !requested.isEmpty()">
                AND d.id IN
                <foreach collection="requested" item="id" open="(" separator="," close=")">#{id}</foreach>
              </if>
              <if test="planned != null and !planned.isEmpty()">
                AND d.id IN
                <foreach collection="planned" item="id" open="(" separator="," close=")">#{id}</foreach>
              </if>
              <if test="criteria != null">
                <if test="criteria.exactFilename != null and criteria.exactFilename != ''">
                  AND (d.original_filename = #{criteria.exactFilename} OR d.display_name = #{criteria.exactFilename})
                </if>
                <if test="criteria.filenameContains != null and criteria.filenameContains != ''">
                  AND (d.original_filename ILIKE ('%' || #{criteria.filenameContains} || '%') OR d.display_name ILIKE ('%' || #{criteria.filenameContains} || '%'))
                </if>
                <if test="criteria.filenameIn != null and !criteria.filenameIn.isEmpty()">
                  AND (d.original_filename IN
                  <foreach collection="criteria.filenameIn" item="fn" open="(" separator="," close=")">
                    #{fn}
                  </foreach>
                  OR d.display_name IN
                  <foreach collection="criteria.filenameIn" item="fn" open="(" separator="," close=")">
                    #{fn}
                  </foreach>)
                </if>
                <if test="criteria.createdAfter != null">
                  AND d.created_at &gt;= #{criteria.createdAfter}
                </if>
                <if test="criteria.createdBefore != null">
                  AND d.created_at &lt;= #{criteria.createdBefore}
                </if>
                <if test="criteria.updatedAfter != null">
                  AND d.updated_at &gt;= #{criteria.updatedAfter}
                </if>
                <if test="criteria.updatedBefore != null">
                  AND d.updated_at &lt;= #{criteria.updatedBefore}
                </if>
                <if test="criteria.status != null and criteria.status != ''">
                  AND d.status = #{criteria.status}
                </if>
                <if test="criteria.statusIn != null and !criteria.statusIn.isEmpty()">
                  AND d.status IN
                  <foreach collection="criteria.statusIn" item="st" open="(" separator="," close=")">
                    #{st}
                  </foreach>
                </if>
                <if test="criteria.mimeType != null and criteria.mimeType != ''">
                  AND d.mime_type = #{criteria.mimeType}
                </if>
              </if>
              <if test="criteria != null and criteria.tagContains != null">
                AND EXISTS (SELECT 1 FROM document_body_chunk c WHERE c.document_id=d.id
                  AND (c.metadata @&gt; jsonb_build_object('tags', jsonb_build_array(#{criteria.tagContains}))
                    OR c.metadata-&gt;&gt;'tags' ILIKE ('%' || #{criteria.tagContains} || '%')
                    OR c.metadata-&gt;&gt;'tag' ILIKE ('%' || #{criteria.tagContains} || '%')))
              </if>
            ORDER BY d.id
            LIMIT #{limit}
            </script>
            """)
    List<DocumentEntity> listVisibleForRetrieval(@Param("projectId") UUID projectId,
            @Param("requested") List<UUID> requested, @Param("planned") List<UUID> planned,
            @Param("criteria") DocumentFilterCriteria criteria, @Param("limit") int limit);

    @Select("""
            SELECT count(*) FROM project_document d
            JOIN document_body b ON b.document_id=d.id
            JOIN connector_document_visibility v ON v.id=d.id AND v.project_id=d.project_id
            WHERE d.status='READY' AND NOT d.access_blocked AND v.readable
            """)
    int countReadyForIndex();

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
            """ + PROCESSABLE)
    int claim(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId,
              @Param("processingToken") UUID processingToken);

    @Update("""
            UPDATE project_document
            SET processing_heartbeat_at=now(), updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId}
              AND status=#{status} AND processing_token=#{processingToken}
            """ + PROCESSABLE)
    int heartbeat(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId,
                  @Param("processingToken") UUID processingToken, @Param("status") DocumentStatus status);

    @Update("""
            UPDATE project_document
            SET status='INDEXING', parser_type=#{parserType}, error_message=NULL,
                processing_token=NULL, processing_heartbeat_at=NULL, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId} AND status='PARSING'
              AND processing_token=#{processingToken}
            """ + PROCESSABLE)
    int markIndexing(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId,
                     @Param("processingToken") UUID processingToken,
                     @Param("parserType") String parserType);

    @Update("""
            UPDATE project_document
            SET status='READY', chunk_count=#{chunkCount}, embedding_provider=#{provider},
                embedding_model=#{model}, embedding_dimension=#{dimension},
                embedding_fingerprint=#{fingerprint}, indexed_at=now(),
                error_message=NULL, processing_token=NULL, processing_heartbeat_at=NULL, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId} AND status='INDEXING'
              AND processing_token=#{processingToken}
            """ + PROCESSABLE)
    int markReady(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId,
                  @Param("processingToken") UUID processingToken,
                  @Param("chunkCount") int chunkCount, @Param("provider") String provider,
                  @Param("model") String model, @Param("dimension") int dimension,
                  @Param("fingerprint") String fingerprint);

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
                processing_heartbeat_at=NULL, version=version+1, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId} AND status='FAILED'
            """)
    int resetFailed(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId);

    @Update("""
            UPDATE project_document SET status='UPLOADED', error_message=NULL, chunk_count=0,
                parser_type=NULL, embedding_provider=NULL, embedding_model=NULL,
                embedding_dimension=NULL, embedding_fingerprint=NULL, indexed_at=NULL, processing_token=NULL,
                processing_heartbeat_at=NULL, version=version+1, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId} AND status='READY'
            """)
    int resetForReindex(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId);

    @Update("""
            UPDATE project_document SET status='UPLOADED', error_message=NULL, chunk_count=0,
                parser_type=NULL, embedding_provider=NULL, embedding_model=NULL,
                embedding_dimension=NULL, embedding_fingerprint=NULL, indexed_at=NULL, processing_token=NULL,
                processing_heartbeat_at=NULL, object_key=#{objectKey}, size_bytes=#{sizeBytes},
                version=version+1, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId} AND status='READY'
            """)
    int resetForReindexWithSource(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId,
                                  @Param("objectKey") String objectKey, @Param("sizeBytes") long sizeBytes);

    @Update("""
            UPDATE project_document SET status='DELETING', processing_token=NULL,
                processing_heartbeat_at=NULL, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{documentId} AND status<>'DELETING'
            """)
    int markDeleting(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId);

    @Delete("DELETE FROM project_document WHERE project_id=#{projectId} AND id=#{documentId} AND status='DELETING'")
    int deleteDocument(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId);

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

    @Select("SELECT DISTINCT project_id FROM project_document WHERE status='READY'")
    List<UUID> projectIdsWithReadyDocuments();

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

    @Select("""
            <script>
            SELECT count(*) FROM project_document
            WHERE project_id=#{projectId} AND id IN
            <foreach collection="documentIds" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            </script>
            """)
    int countDocuments(@Param("projectId") UUID projectId,
                       @Param("documentIds") List<UUID> documentIds);
}
