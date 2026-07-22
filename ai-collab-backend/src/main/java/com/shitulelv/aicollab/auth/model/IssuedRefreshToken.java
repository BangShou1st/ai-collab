package com.shitulelv.aicollab.auth.model;

import java.time.Instant;

/**
 * 刷新凭据刚签发时的短生命周期值对象。
 *
 * <p>原文只允许在本次应用调用链中暂存，用于写入 HttpOnly Cookie；持久化层只接收其哈希值。</p>
 */
public record IssuedRefreshToken(String value, Instant expiresAt) {
}
