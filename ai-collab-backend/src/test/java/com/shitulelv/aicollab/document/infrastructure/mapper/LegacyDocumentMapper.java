package com.shitulelv.aicollab.document.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.common.persistence.PostgresJsonMapTypeHandler;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRecoveryCandidate;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentProcessingAttempt;
import com.shitulelv.aicollab.retrieval.query.DocumentFilterCriteria;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface LegacyDocumentMapper {
    default int insertChunk(UUID id, UUID projectId, UUID documentId, DocumentChunk chunk, String metadata,
            String provider, String model, int dimension, String fingerprint, String embedding) {
        return insertChunk(id, projectId, documentId, chunk, metadata, provider, model, dimension, fingerprint, embedding, null);
    }

    default List<DocumentSearchHit> search(UUID projectId, String embedding, String provider, String model,
            int dimension, String fingerprint, List<UUID> documentIds, int topK) {
        return search(projectId, embedding, provider, model, dimension, fingerprint, documentIds, topK, null, null);
    }

    default List<DocumentSearchHit> search(UUID projectId, String embedding, String provider, String model,
            int dimension, String fingerprint, List<UUID> documentIds, int topK, UUID generationId) {
        return search(projectId, embedding, provider, model, dimension, fingerprint, documentIds, topK, generationId, null);
    }

    default List<DocumentSearchHit> searchLexical(
            UUID projectId, String query, List<UUID> documentIds, int topK, UUID generationId) {
        return searchLexical(projectId, query, documentIds, topK, generationId, null);
    }

    @Delete("DELETE FROM document_chunk WHERE project_id=#{projectId} AND document_id=#{documentId}")
    int deleteChunks(@Param("projectId") UUID projectId, @Param("documentId") UUID documentId);

    @Insert("""
            INSERT INTO document_chunk(
              id, project_id, document_id, chunk_no, heading, content, content_hash,
              token_estimate, metadata, embedding_provider, embedding_model,
              embedding_dimension, embedding_fingerprint, embedding, generation_id,
              parent_chunk_id, chunk_type)
            VALUES(
              #{id}, #{projectId}, #{documentId}, #{chunk.chunkNo}, #{chunk.heading},
              #{chunk.content}, #{chunk.contentHash}, #{chunk.tokenEstimate},
              CAST(#{metadata} AS jsonb), #{provider}, #{model}, #{dimension}, #{fingerprint},
              CAST(#{embedding} AS vector), #{generationId},
              #{chunk.parentChunkId}, #{chunk.chunkType})
            """)
    int insertChunk(@Param("id") UUID id, @Param("projectId") UUID projectId,
                    @Param("documentId") UUID documentId, @Param("chunk") DocumentChunk chunk,
                    @Param("metadata") String metadata, @Param("provider") String provider,
                    @Param("model") String model, @Param("dimension") int dimension,
                    @Param("fingerprint") String fingerprint,
                    @Param("embedding") String embedding, @Param("generationId") UUID generationId);

    @Select("""
            <script>
            SELECT c.id, c.document_id AS "documentId",
                   d.original_filename AS "originalFilename",
                   c.heading, c.content, c.content_hash AS "contentHash",
                   1 - (c.embedding &lt;=&gt; CAST(#{embedding} AS vector)) AS similarity,
                   c.metadata
            FROM document_chunk c
            JOIN project_document d ON d.id=c.document_id AND d.project_id=c.project_id
            WHERE c.project_id=#{projectId} AND d.status='READY' AND NOT d.access_blocked
              AND NOT EXISTS (SELECT 1 FROM external_document e
                JOIN connector_source s ON s.id=e.source_id AND s.project_id=d.project_id
                LEFT JOIN connector_oauth_authorization a ON a.id=s.user_authorization_id AND a.project_id=s.project_id
                WHERE e.document_id=d.id AND (e.status&lt;&gt;'ACTIVE' OR s.status&lt;&gt;'ACTIVE'
                  OR (s.user_authorization_id IS NOT NULL AND (a.id IS NULL OR a.status&lt;&gt;'ACTIVE' OR a.access_expires_at&lt;=now()))))
              AND c.embedding_provider=#{provider} AND c.embedding_model=#{model}
              AND c.embedding_dimension=#{dimension}
              AND c.embedding_fingerprint=#{fingerprint}
              AND c.generation_id IS NOT DISTINCT FROM #{generationId,jdbcType=OTHER}
              AND (c.chunk_type IS NULL OR c.chunk_type &lt;&gt; 'PARENT')
              <if test="documentIds != null and !documentIds.isEmpty()">
                AND d.id IN
                <foreach collection="documentIds" item="id" open="(" separator="," close=")">
                  #{id}
                </foreach>
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
                <if test="criteria.exactHeading != null and criteria.exactHeading != ''">
                  AND c.heading = #{criteria.exactHeading}
                </if>
                <if test="criteria.headingContains != null and criteria.headingContains != ''">
                  AND c.heading ILIKE ('%' || #{criteria.headingContains} || '%')
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
                <if test="criteria.tagContains != null and criteria.tagContains != ''">
                  AND (c.metadata @&gt; jsonb_build_object('tags', jsonb_build_array(#{criteria.tagContains}))
                       OR c.metadata-&gt;&gt;'tags' ILIKE ('%' || #{criteria.tagContains} || '%')
                       OR c.metadata-&gt;&gt;'tag' ILIKE ('%' || #{criteria.tagContains} || '%'))
                </if>
              </if>
            ORDER BY similarity DESC, c.id ASC
            LIMIT #{topK}
            </script>
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class, id = true),
        @Arg(column = "documentId", javaType = UUID.class),
        @Arg(column = "originalFilename", javaType = String.class),
        @Arg(column = "heading", javaType = String.class),
        @Arg(column = "content", javaType = String.class),
        @Arg(column = "contentHash", javaType = String.class),
        @Arg(column = "similarity", javaType = double.class),
        @Arg(
                column = "metadata",
                javaType = Map.class,
                typeHandler = PostgresJsonMapTypeHandler.class)
    })
    List<DocumentSearchHit> search(@Param("projectId") UUID projectId,
                                   @Param("embedding") String embedding,
                                   @Param("provider") String provider,
                                   @Param("model") String model,
                                   @Param("dimension") int dimension,
                                   @Param("fingerprint") String fingerprint,
                                   @Param("documentIds") List<UUID> documentIds,
                                   @Param("topK") int topK,
                                   @Param("generationId") UUID generationId,
                                   @Param("criteria") DocumentFilterCriteria criteria);

    @Select("""
            <script>
            SELECT c.id, c.document_id AS "documentId",
                   d.original_filename AS "originalFilename",
                   c.heading, c.content, c.content_hash AS "contentHash",
                   GREATEST(
                       COALESCE(similarity(c.content, #{query}), 0),
                       CASE WHEN c.heading IS NOT NULL AND c.heading ILIKE ('%' || #{query} || '%') THEN 0.8 ELSE 0 END,
                       CASE WHEN c.content ILIKE ('%' || #{query} || '%') THEN 0.6 ELSE 0 END
                   ) AS similarity,
                   c.metadata
            FROM document_chunk c
            JOIN project_document d ON d.id=c.document_id AND d.project_id=c.project_id
            WHERE c.project_id=#{projectId} AND d.status='READY' AND NOT d.access_blocked
              AND NOT EXISTS (SELECT 1 FROM external_document e
                JOIN connector_source s ON s.id=e.source_id AND s.project_id=d.project_id
                LEFT JOIN connector_oauth_authorization a ON a.id=s.user_authorization_id AND a.project_id=s.project_id
                WHERE e.document_id=d.id AND (e.status&lt;&gt;'ACTIVE' OR s.status&lt;&gt;'ACTIVE'
                  OR (s.user_authorization_id IS NOT NULL AND (a.id IS NULL OR a.status&lt;&gt;'ACTIVE' OR a.access_expires_at&lt;=now()))))
              AND c.generation_id IS NOT DISTINCT FROM #{generationId,jdbcType=OTHER}
              AND (c.chunk_type IS NULL OR c.chunk_type &lt;&gt; 'PARENT')
              <if test="documentIds != null and !documentIds.isEmpty()">
                AND d.id IN
                <foreach collection="documentIds" item="id" open="(" separator="," close=")">
                  #{id}
                </foreach>
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
                <if test="criteria.exactHeading != null and criteria.exactHeading != ''">
                  AND c.heading = #{criteria.exactHeading}
                </if>
                <if test="criteria.headingContains != null and criteria.headingContains != ''">
                  AND c.heading ILIKE ('%' || #{criteria.headingContains} || '%')
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
                <if test="criteria.tagContains != null and criteria.tagContains != ''">
                  AND (c.metadata @&gt; jsonb_build_object('tags', jsonb_build_array(#{criteria.tagContains}))
                       OR c.metadata-&gt;&gt;'tags' ILIKE ('%' || #{criteria.tagContains} || '%')
                       OR c.metadata-&gt;&gt;'tag' ILIKE ('%' || #{criteria.tagContains} || '%'))
                </if>
              </if>
              <!-- 阶段 4：similarity()>0.03 谓词已被 % @0.03（连接初始化 GUC）子集覆盖，
                   移除该不可索引分支使 BitmapOr 走 GIN trgm 索引（语义等价 diff=0 验证） -->
              AND (
                  c.content % #{query}
                  OR c.content ILIKE ('%' || #{query} || '%')
                  OR (c.heading IS NOT NULL AND (c.heading % #{query} OR c.heading ILIKE ('%' || #{query} || '%')))
              )
            ORDER BY similarity DESC, c.id ASC
            LIMIT #{topK}
            </script>
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class, id = true),
        @Arg(column = "documentId", javaType = UUID.class),
        @Arg(column = "originalFilename", javaType = String.class),
        @Arg(column = "heading", javaType = String.class),
        @Arg(column = "content", javaType = String.class),
        @Arg(column = "contentHash", javaType = String.class),
        @Arg(column = "similarity", javaType = double.class),
        @Arg(
                column = "metadata",
                javaType = Map.class,
                typeHandler = PostgresJsonMapTypeHandler.class)
    })
    List<DocumentSearchHit> searchLexical(
            @Param("projectId") UUID projectId,
            @Param("query") String query,
            @Param("documentIds") List<UUID> documentIds,
            @Param("topK") int topK,
            @Param("generationId") UUID generationId,
            @Param("criteria") DocumentFilterCriteria criteria);

    @Select("""
            SELECT c.id, c.document_id AS "documentId",
                   d.original_filename AS "originalFilename",
                   c.heading, c.content, c.content_hash AS "contentHash",
                   1.0 AS similarity,
                   c.metadata
            FROM document_chunk c
            JOIN project_document d ON d.id=c.document_id AND d.project_id=c.project_id
            WHERE c.project_id=#{projectId} AND c.id=#{chunkId}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class, id = true),
        @Arg(column = "documentId", javaType = UUID.class),
        @Arg(column = "originalFilename", javaType = String.class),
        @Arg(column = "heading", javaType = String.class),
        @Arg(column = "content", javaType = String.class),
        @Arg(column = "contentHash", javaType = String.class),
        @Arg(column = "similarity", javaType = double.class),
        @Arg(
                column = "metadata",
                javaType = Map.class,
                typeHandler = PostgresJsonMapTypeHandler.class)
    })
    Optional<DocumentSearchHit> findChunkById(@Param("projectId") UUID projectId, @Param("chunkId") UUID chunkId);

    @Select("SELECT count(*) FROM document_chunk WHERE embedding_fingerprint IS DISTINCT FROM #{fingerprint} AND generation_id IS NOT DISTINCT FROM (SELECT generation_id FROM system_embedding_config WHERE enabled)")
    long countChunksWithOtherFingerprint(@Param("fingerprint") String fingerprint);

}
