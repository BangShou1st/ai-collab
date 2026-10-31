package com.shitulelv.aicollab.connector.infrastructure.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface ConnectorConnectionMapper {
    @Insert("""
            INSERT INTO connector_connection (id, provider, name, app_id, app_secret_cipher, status,
                last_verified_at, created_by, created_at, updated_at, visibility, owner_project_id)
            VALUES (#{id}::uuid, #{provider}, #{name}, #{appId}, #{secretCipher}, #{status},
                #{verifiedAt}, #{createdBy}, now(), now(), #{visibility}, #{ownerProjectId}::uuid)
            """)
    int insert(@Param("id") UUID id, @Param("provider") String provider, @Param("name") String name,
               @Param("appId") String appId, @Param("secretCipher") String secretCipher,
               @Param("status") String status, @Param("verifiedAt") OffsetDateTime verifiedAt,
               @Param("createdBy") UUID createdBy, @Param("visibility") String visibility,
               @Param("ownerProjectId") UUID ownerProjectId);

    @Select("SELECT id, provider, name, app_id, status, last_error, last_verified_at, created_by, created_at, "
            + "visibility, owner_project_id FROM connector_connection "
            + "WHERE visibility = 'SYSTEM' ORDER BY created_at")
    List<ConnectorRows.ConnectionRow> listSystem();

    @Select("SELECT id, provider, name, app_id, status, last_error, last_verified_at, created_by, created_at, "
            + "visibility, owner_project_id FROM connector_connection "
            + "WHERE status = 'ACTIVE' AND (visibility = 'SYSTEM' OR owner_project_id = #{projectId}::uuid) "
            + "ORDER BY created_at")
    List<ConnectorRows.ConnectionRow> listAvailableForProject(@Param("projectId") UUID projectId);

    @Select("SELECT id, provider, name, app_id, app_secret_cipher, status, last_error, last_verified_at, created_at "
            + "FROM connector_connection WHERE id=#{id}::uuid")
    ConnectorRows.ConnectionRow find(@Param("id") UUID id);

    @Update("""
            UPDATE connector_connection SET status=#{status}, last_error=#{lastError},
                last_verified_at=#{verifiedAt}, updated_at=now() WHERE id=#{id}::uuid
            """)
    int updateStatus(@Param("id") UUID id, @Param("status") String status,
                     @Param("lastError") String lastError, @Param("verifiedAt") OffsetDateTime verifiedAt);

    @Delete("DELETE FROM connector_connection WHERE id=#{id}::uuid")
    int delete(@Param("id") UUID id);
}
