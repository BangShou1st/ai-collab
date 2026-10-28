package com.shitulelv.aicollab.connector.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface ConnectorSourceMapper {
    @Insert("""
            INSERT INTO connector_source (id, project_id, connection_id, folder_token, name, status,
                authorization_declared_by, authorization_declared_at, last_verified_at, created_at, updated_at)
            VALUES (#{id}::uuid, #{projectId}::uuid, #{connectionId}::uuid, #{folderToken}, #{name}, #{status},
                #{declaredBy}::uuid, now(), #{verifiedAt}, now(), now())
            """)
    int insert(@Param("id") UUID id, @Param("projectId") UUID projectId,
               @Param("connectionId") UUID connectionId, @Param("folderToken") String folderToken,
               @Param("name") String name, @Param("status") String status,
               @Param("declaredBy") UUID declaredBy, @Param("verifiedAt") OffsetDateTime verifiedAt);

    @Select("SELECT id, project_id, connection_id, folder_token, name, status, authorization_declared_by, "
            + "authorization_declared_at, last_verified_at, last_synced_at, created_at "
            + "FROM connector_source WHERE project_id=#{projectId}::uuid ORDER BY created_at")
    List<ConnectorRows.SourceRow> listByProject(@Param("projectId") UUID projectId);

    @Select("SELECT id, project_id, connection_id, folder_token, name, status, authorization_declared_by, "
            + "authorization_declared_at, last_verified_at, last_synced_at, created_at "
            + "FROM connector_source WHERE id=#{id}::uuid")
    ConnectorRows.SourceRow find(@Param("id") UUID id);

    @Select("""
            SELECT id, project_id, connection_id, folder_token, name, status, authorization_declared_by,
                   authorization_declared_at, last_verified_at, last_synced_at, created_at
            FROM connector_source WHERE status='ACTIVE'
            """)
    List<ConnectorRows.SourceRow> listActive();

    @Update("""
            UPDATE connector_source SET status=#{status}, last_verified_at=#{verifiedAt}, updated_at=now()
            WHERE id=#{id}::uuid
            """)
    int updateStatus(@Param("id") UUID id, @Param("status") String status,
                     @Param("verifiedAt") OffsetDateTime verifiedAt);

    @Update("UPDATE connector_source SET last_synced_at=now(), updated_at=now() WHERE id=#{id}::uuid")
    int markSynced(@Param("id") UUID id);

    @Select("SELECT COUNT(*) FROM connector_source WHERE connection_id=#{connectionId}::uuid")
    int countByConnection(@Param("connectionId") UUID connectionId);
}
