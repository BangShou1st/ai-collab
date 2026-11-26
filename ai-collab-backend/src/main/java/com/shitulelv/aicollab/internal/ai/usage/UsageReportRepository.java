package com.shitulelv.aicollab.internal.ai.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 把 Python 上报的用量写进 {@code public.ai_call_log}（V104 新增 call_id/lease_id/caller_type 列）。
 *
 * <p>幂等键是 {@code call_id}：{@code ON CONFLICT (call_id) DO NOTHING}，重复上报不会插入第二行。
 * {@code feature} 写 purpose，{@code request_id} 写 callId，{@code provider} 用 lease 表里的 provider_type 回填，
 * {@code caller_type} 固定为 {@code PYTHON}（R1 §9.5：请求里没有 callerType）。</p>
 */
@Repository
public class UsageReportRepository {

    static final String CALLER_TYPE_PYTHON = "PYTHON";

    private final JdbcTemplate jdbc;

    public UsageReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return {@code true} 表示新插入；{@code false} 表示 call_id 已存在（重复上报）。 */
    public boolean insertIfAbsent(UsageRow row) {
        int inserted = jdbc.update("""
                INSERT INTO ai_call_log (id, user_id, project_id, feature, provider, model, status,
                                         latency_ms, prompt_tokens, completion_tokens, error_code,
                                         request_id, call_id, lease_id, caller_type, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (call_id) DO NOTHING
                """, UUID.randomUUID(), row.userId(), row.projectId(), row.feature(), row.provider(),
                row.model(), row.status(), row.latencyMs(), row.inputTokens(), row.outputTokens(),
                row.errorCode(), row.callId(), row.callId(), row.leaseId(), CALLER_TYPE_PYTHON);
        return inserted > 0;
    }

    /** 一行用量（列名对应 ai_call_log）。 */
    public record UsageRow(
            UUID callId,
            UUID leaseId,
            UUID userId,
            UUID projectId,
            String feature,
            String provider,
            String model,
            String status,
            Integer latencyMs,
            Integer inputTokens,
            Integer outputTokens,
            String errorCode) {
    }
}
