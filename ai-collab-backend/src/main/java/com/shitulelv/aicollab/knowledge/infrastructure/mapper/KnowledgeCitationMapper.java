package com.shitulelv.aicollab.knowledge.infrastructure.mapper;

import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeCitationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface KnowledgeCitationMapper {
    @Insert("""
            INSERT INTO knowledge_citation(
              id, message_id, document_id, chunk_no, content_hash, body_snapshot_id,
              rank, similarity, quote_text)
            SELECT #{id}, #{messageId}, c.document_id, c.chunk_no, c.content_hash, b.snapshot_id,
                   #{rank}, #{similarity}, #{quote}
            FROM document_body_chunk c
            JOIN document_body b ON b.document_id=c.document_id
            JOIN project_document d ON d.id=c.document_id
            JOIN knowledge_message m ON m.id=#{messageId}
            JOIN knowledge_session s ON s.id=m.session_id AND s.project_id=d.project_id
            WHERE d.project_id=#{projectId} AND d.id=#{documentId} AND c.chunk_no=#{chunkNo}
                AND c.content_hash=#{contentHash} AND b.snapshot_id=#{bodySnapshotId}
                AND d.status='READY' AND NOT d.access_blocked
                AND NOT EXISTS (SELECT 1 FROM external_document e JOIN connector_source cs
                  ON cs.id=e.source_id AND cs.project_id=d.project_id
                  LEFT JOIN connector_oauth_authorization a ON a.id=cs.user_authorization_id AND a.project_id=cs.project_id
                  WHERE e.document_id=d.id AND (e.status<>'ACTIVE' OR cs.status<>'ACTIVE'
                    OR (cs.user_authorization_id IS NOT NULL AND (a.id IS NULL OR a.status<>'ACTIVE' OR a.access_expires_at<=now()))))
                FOR SHARE OF d,b,c
            """)
    int insertScoped(
            @Param("id") UUID id,
            @Param("projectId") UUID projectId,
            @Param("messageId") UUID messageId,
            @Param("documentId") UUID documentId,
            @Param("chunkNo") Integer chunkNo,
            @Param("contentHash") String contentHash,
            @Param("bodySnapshotId") UUID bodySnapshotId,
            @Param("rank") int rank,
            @Param("similarity") double similarity,
            @Param("quote") String quote);

    @Select("""
            SELECT kc.id, kc.message_id, dc.id AS chunk_id, dc.document_id,
                   pd.original_filename AS filename, dc.heading, kc.rank,
                   kc.similarity, kc.quote_text AS quote
            FROM knowledge_session ks
            JOIN knowledge_message km ON km.session_id=ks.id
            JOIN knowledge_citation kc ON kc.message_id=km.id
            JOIN document_body b ON b.document_id=kc.document_id
                 AND (kc.body_snapshot_id IS NULL OR b.snapshot_id=kc.body_snapshot_id)
            JOIN document_body_chunk dc ON dc.document_id=kc.document_id
                 AND dc.chunk_no=kc.chunk_no AND dc.content_hash=kc.content_hash
            JOIN project_document pd ON pd.id=dc.document_id AND pd.project_id=ks.project_id
            WHERE ks.project_id=#{projectId} AND ks.id=#{sessionId} AND ks.user_id=#{userId}
                AND pd.status='READY' AND NOT pd.access_blocked
                AND NOT EXISTS (SELECT 1 FROM connector_hidden_answer h WHERE h.project_id=ks.project_id AND h.message_id=km.id AND h.cleared_at IS NULL)
                AND NOT EXISTS (SELECT 1 FROM connector_source cs LEFT JOIN connector_oauth_authorization a
                  ON a.id=cs.user_authorization_id AND a.project_id=cs.project_id
                  WHERE cs.project_id=ks.project_id AND (cs.status<>'ACTIVE' OR (cs.user_authorization_id IS NOT NULL
                    AND (a.id IS NULL OR a.status<>'ACTIVE' OR a.access_expires_at<=now()))))
                AND NOT EXISTS (SELECT 1 FROM external_document e JOIN connector_source cs
                  ON cs.id=e.source_id AND cs.project_id=pd.project_id
                  LEFT JOIN connector_oauth_authorization a ON a.id=cs.user_authorization_id AND a.project_id=cs.project_id
                  WHERE e.document_id=pd.id AND (e.status<>'ACTIVE' OR cs.status<>'ACTIVE'
                    OR (cs.user_authorization_id IS NOT NULL AND (a.id IS NULL OR a.status<>'ACTIVE' OR a.access_expires_at<=now()))))
            ORDER BY km.created_at ASC, km.id ASC, kc.rank ASC
            """)
    List<KnowledgeCitationEntity> listOwn(
            @Param("projectId") UUID projectId,
            @Param("sessionId") UUID sessionId,
            @Param("userId") UUID userId);
}
