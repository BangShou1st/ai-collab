package com.shitulelv.aicollab.connector.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ExternalDocumentMapper {
    @Insert("""
            INSERT INTO external_document (id, source_id, external_key, doc_type, title, source_url,
                latest_modify_time, content_hash, document_id, status, created_at, updated_at)
            VALUES (#{id}::uuid, #{sourceId}::uuid, #{externalKey}, #{docType}, #{title}, #{sourceUrl},
                #{latestModifyTime}, #{contentHash}, #{documentId}::uuid, 'ACTIVE', now(), now())
            """)
    int insert(@Param("id") UUID id, @Param("sourceId") UUID sourceId, @Param("externalKey") String externalKey,
               @Param("docType") String docType, @Param("title") String title, @Param("sourceUrl") String sourceUrl,
               @Param("latestModifyTime") Long latestModifyTime, @Param("contentHash") String contentHash,
               @Param("documentId") UUID documentId);

    @Select("""
            SELECT e.id, e.source_id, s.project_id, e.external_key, e.doc_type, e.title, e.source_url,
                   e.latest_modify_time, e.content_hash, e.document_id, e.status, d.status AS document_status
            FROM external_document e JOIN connector_source s ON s.id=e.source_id
            LEFT JOIN project_document d ON d.id=e.document_id
            WHERE e.source_id=#{sourceId}::uuid ORDER BY e.created_at
            """)
    List<ConnectorRows.ExternalDocRow> listBySource(@Param("sourceId") UUID sourceId);

    @Select("""
            SELECT e.id, e.source_id, s.project_id, e.external_key, e.doc_type, e.title, e.source_url,
                   e.latest_modify_time, e.content_hash, e.document_id, e.status, d.status AS document_status
            FROM external_document e JOIN connector_source s ON s.id=e.source_id
            LEFT JOIN project_document d ON d.id=e.document_id
            WHERE e.id=#{id}::uuid
            """)
    ConnectorRows.ExternalDocRow find(@Param("id") UUID id);

    @Select("""
            SELECT e.id, e.source_id, s.project_id, e.external_key, e.doc_type, e.title, e.source_url,
                   e.latest_modify_time, e.content_hash, e.document_id, e.status, d.status AS document_status
            FROM external_document e JOIN connector_source s ON s.id=e.source_id
            LEFT JOIN project_document d ON d.id=e.document_id
            WHERE s.connection_id=#{connectionId}::uuid AND e.external_key=#{externalKey}
            """)
    ConnectorRows.ExternalDocRow findByConnectionAndKey(@Param("connectionId") UUID connectionId,
                                                        @Param("externalKey") String externalKey);

    @Update("""
            UPDATE external_document SET title=#{title}, source_url=#{sourceUrl},
                latest_modify_time=#{latestModifyTime}, content_hash=#{contentHash}, updated_at=now()
            WHERE id=#{id}::uuid
            """)
    int updateContent(@Param("id") UUID id, @Param("title") String title, @Param("sourceUrl") String sourceUrl,
                      @Param("latestModifyTime") Long latestModifyTime, @Param("contentHash") String contentHash);

    @Update("""
            UPDATE external_document SET status=#{status}, updated_at=now() WHERE id=#{id}::uuid
            """)
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    @Update("""
            UPDATE external_document SET status='BLOCKED', updated_at=now()
            WHERE source_id=#{sourceId}::uuid AND status='ACTIVE'
            """)
    int blockAllOfSource(@Param("sourceId") UUID sourceId);
}
