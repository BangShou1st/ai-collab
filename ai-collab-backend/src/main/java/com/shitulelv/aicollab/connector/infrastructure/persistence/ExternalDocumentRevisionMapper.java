package com.shitulelv.aicollab.connector.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface ExternalDocumentRevisionMapper {
    @Insert("""
            INSERT INTO external_document_revision (id, external_document_id, revision_no, latest_modify_time,
                content_hash, document_id, status, created_at)
            VALUES (#{id}::uuid, #{externalDocumentId}::uuid, #{revisionNo}, #{latestModifyTime},
                #{contentHash}, #{documentId}::uuid, #{status}, now())
            """)
    int insert(@Param("id") UUID id, @Param("externalDocumentId") UUID externalDocumentId,
               @Param("revisionNo") int revisionNo, @Param("latestModifyTime") Long latestModifyTime,
               @Param("contentHash") String contentHash, @Param("documentId") UUID documentId,
               @Param("status") String status);

    @Select("SELECT COALESCE(MAX(revision_no),0) FROM external_document_revision "
            + "WHERE external_document_id=#{id}::uuid")
    int maxRevisionNo(@Param("id") UUID id);

    @Update("""
            UPDATE external_document_revision SET status='SUPERSEDED'
            WHERE external_document_id=#{id}::uuid AND status='ACTIVE'
            """)
    int supersedeActive(@Param("id") UUID id);
}
