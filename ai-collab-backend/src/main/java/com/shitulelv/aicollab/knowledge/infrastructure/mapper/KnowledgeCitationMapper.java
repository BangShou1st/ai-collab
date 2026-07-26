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
              id, message_id, chunk_id, rank, similarity, quote_text)
            SELECT #{id}, #{messageId}, c.id, #{rank}, #{similarity}, #{quote}
            FROM document_chunk c
            JOIN project_document d ON d.id=c.document_id AND d.project_id=c.project_id
            JOIN knowledge_message m ON m.id=#{messageId}
            JOIN knowledge_session s ON s.id=m.session_id AND s.project_id=c.project_id
            WHERE c.project_id=#{projectId} AND c.id=#{chunkId} AND d.id=#{documentId}
            """)
    int insertScoped(
            @Param("id") UUID id,
            @Param("projectId") UUID projectId,
            @Param("messageId") UUID messageId,
            @Param("chunkId") UUID chunkId,
            @Param("documentId") UUID documentId,
            @Param("rank") int rank,
            @Param("similarity") double similarity,
            @Param("quote") String quote);

    @Select("""
            SELECT kc.id, kc.message_id, kc.chunk_id, dc.document_id,
                   pd.original_filename AS filename, dc.heading, kc.rank,
                   kc.similarity, kc.quote_text AS quote
            FROM knowledge_session ks
            JOIN knowledge_message km ON km.session_id=ks.id
            JOIN knowledge_citation kc ON kc.message_id=km.id
            JOIN document_chunk dc ON dc.id=kc.chunk_id AND dc.project_id=ks.project_id
            JOIN project_document pd ON pd.id=dc.document_id AND pd.project_id=ks.project_id
            WHERE ks.project_id=#{projectId} AND ks.id=#{sessionId} AND ks.user_id=#{userId}
            ORDER BY km.created_at ASC, km.id ASC, kc.rank ASC
            """)
    List<KnowledgeCitationEntity> listOwn(
            @Param("projectId") UUID projectId,
            @Param("sessionId") UUID sessionId,
            @Param("userId") UUID userId);
}
