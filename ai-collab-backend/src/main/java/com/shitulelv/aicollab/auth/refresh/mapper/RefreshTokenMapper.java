package com.shitulelv.aicollab.auth.refresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.auth.refresh.entity.RefreshTokenEntity;
import com.shitulelv.aicollab.auth.refresh.model.RefreshTokenRevokeReason;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Refresh Token 的 PostgreSQL 持久化入口。
 *
 * <p>轮换调用方必须在同一个事务中依次执行普通 hash 查询、{@link #lockSession(UUID)}、
 * {@link #selectByTokenHashForUpdate(String)}，从而同时串行同 session 的不同代记录并原子更新当前行。</p>
 */
@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshTokenEntity> {

    @Select("""
            SELECT id, user_id AS userId, token_hash AS tokenHash, expires_at AS expiresAt,
                   revoked_at AS revokedAt, created_at AS createdAt, session_id AS sessionId,
                   session_expires_at AS sessionExpiresAt, revoke_reason AS revokeReason,
                   replaced_by_token_id AS replacedByTokenId
            FROM refresh_token
            WHERE token_hash = #{tokenHash}
            """)
    Optional<RefreshTokenEntity> selectByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * PostgreSQL 事务级 advisory lock：同一 session 的所有代际共用一个稳定键，锁随事务结束自动释放。
     * 必须先用无锁查询得到 session_id，再调用本方法，最后重新按 hash 加行锁和校验，避免锁顺序反转造成死锁。
     */
    @Select("""
            SELECT pg_advisory_xact_lock(
                hashtextextended(CAST(#{sessionId} AS text), 0)
            )
            """)
    String lockSession(@Param("sessionId") UUID sessionId);

    /**
     * PostgreSQL 行锁重查；调用前必须已取得 session 事务锁，普通查询只负责确定统一的锁键。
     */
    @Select("""
            SELECT id, user_id AS userId, token_hash AS tokenHash, expires_at AS expiresAt,
                   revoked_at AS revokedAt, created_at AS createdAt, session_id AS sessionId,
                   session_expires_at AS sessionExpiresAt, revoke_reason AS revokeReason,
                   replaced_by_token_id AS replacedByTokenId
            FROM refresh_token
            WHERE token_hash = #{tokenHash}
            FOR UPDATE
            """)
    Optional<RefreshTokenEntity> selectByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Update("""
            UPDATE refresh_token
            SET revoked_at = #{rotatedAt},
                revoke_reason = 'ROTATED',
                replaced_by_token_id = #{replacedByTokenId}
            WHERE id = #{tokenId}
              AND revoked_at IS NULL
            """)
    int markRotated(
            @Param("tokenId") UUID tokenId,
            @Param("replacedByTokenId") UUID replacedByTokenId,
            @Param("rotatedAt") OffsetDateTime rotatedAt);

    /**
     * 只撤销仍有效的记录，既避免覆盖首次失效原因，也让重复退出操作保持幂等。
     */
    @Update("""
            UPDATE refresh_token
            SET revoked_at = #{revokedAt},
                revoke_reason = #{revokeReason}
            WHERE session_id = #{sessionId}
              AND revoked_at IS NULL
            """)
    int revokeActiveTokensBySessionId(
            @Param("sessionId") UUID sessionId,
            @Param("revokeReason") RefreshTokenRevokeReason revokeReason,
            @Param("revokedAt") OffsetDateTime revokedAt);
}
