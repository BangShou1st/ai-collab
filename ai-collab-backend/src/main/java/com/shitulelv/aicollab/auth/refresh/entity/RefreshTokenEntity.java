package com.shitulelv.aicollab.auth.refresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shitulelv.aicollab.auth.refresh.model.RefreshTokenRevokeReason;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * refresh_token 表的纯持久化映射。
 *
 * <p>实体只保存 SHA-256 摘要，不得增加 Refresh Token 原文或可反推原文的字段；原文的生命周期仅限 HTTP Cookie 和短暂的业务值对象。</p>
 */
@TableName("refresh_token")
public class RefreshTokenEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID userId;
    private String tokenHash;
    private OffsetDateTime expiresAt;
    private OffsetDateTime revokedAt;
    private OffsetDateTime createdAt;
    private UUID sessionId;
    private OffsetDateTime sessionExpiresAt;
    private RefreshTokenRevokeReason revokeReason;
    private UUID replacedByTokenId;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public OffsetDateTime getSessionExpiresAt() {
        return sessionExpiresAt;
    }

    public void setSessionExpiresAt(OffsetDateTime sessionExpiresAt) {
        this.sessionExpiresAt = sessionExpiresAt;
    }

    public RefreshTokenRevokeReason getRevokeReason() {
        return revokeReason;
    }

    public void setRevokeReason(RefreshTokenRevokeReason revokeReason) {
        this.revokeReason = revokeReason;
    }

    public UUID getReplacedByTokenId() {
        return replacedByTokenId;
    }

    public void setReplacedByTokenId(UUID replacedByTokenId) {
        this.replacedByTokenId = replacedByTokenId;
    }
}
