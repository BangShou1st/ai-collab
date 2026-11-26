package com.shitulelv.aicollab.internal.ai.usage;

import com.shitulelv.aicollab.internal.ai.InternalAiClaims;
import com.shitulelv.aicollab.internal.ai.credential.CredentialLeaseRepository;
import com.shitulelv.aicollab.internal.ai.credential.LeasePurpose;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 用量回报（契约 §3、R1 §9.5）。
 *
 * <p>逐条判定、逐条返回：每个 callId 都有一条结果；重复的 callId 算 {@code accepted=true}。
 * 某条的 leaseId 不存在，或 lease 的 user/project 与令牌 {@code uid/pid} 不一致 → 该条拒收。
 * {@code errorCode} 原样落库（可能是小写的 {@code provider_*}），不做校验。</p>
 */
@Service
public class UsageReportService {

    private static final Logger log = LoggerFactory.getLogger(UsageReportService.class);

    static final int MAX_BATCH = 100;
    static final Set<String> STATUSES = Set.of(
            "SUCCESS", "TIMEOUT", "QUOTA_EXCEEDED", "PROVIDER_ERROR", "INVALID_OUTPUT", "CANCELED");
    static final String METRIC_ITEMS = "ai.usage.report.items";

    private static final int MAX_FEATURE_CHARS = 40;
    private static final int MAX_PROVIDER_CHARS = 80;
    private static final int MAX_MODEL_CHARS = 120;
    private static final int MAX_ERROR_CODE_CHARS = 80;

    private final CredentialLeaseRepository leases;
    private final UsageReportRepository usage;
    private final MeterRegistry meterRegistry;

    public UsageReportService(CredentialLeaseRepository leases, UsageReportRepository usage,
            MeterRegistry meterRegistry) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.meterRegistry = meterRegistry;
    }

    /** 一条上报（数值已由 JSON 解析为整数；缺失为 null）。 */
    public record Item(String callId, String leaseId, String purpose, String model, Long inputTokens,
            Long outputTokens, Long latencyMs, String status, String errorCode) {
    }

    /** 一条结果；{@code reason} 只在拒收时出现。 */
    public record Result(String callId, boolean accepted, String reason) {
    }

    /** 整批格式错误（控制器映射为 400，整批不落库）。 */
    public static final class BatchRejectedException extends RuntimeException {
        private final String reason;

        BatchRejectedException(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /**
     * 整批校验：1–{@value #MAX_BATCH} 条、每条非空且带非空 callId。
     * 缺 callId 的条目无法按"每个 callId 一条结果"回执（R1 §9.5），因此整批拒绝，而不是回一条 callId=null 的结果。
     */
    static void requireValidBatch(List<Item> items) {
        if (items == null || items.isEmpty() || items.size() > MAX_BATCH) {
            throw new BatchRejectedException("batch_size_invalid");
        }
        for (Item item : items) {
            if (item == null) {
                throw new BatchRejectedException("item_invalid");
            }
            if (item.callId() == null || item.callId().isBlank()) {
                throw new BatchRejectedException("call_id_missing");
            }
        }
    }

    public List<Result> report(List<Item> items, InternalAiClaims claims) {
        requireValidBatch(items);
        List<Result> results = new ArrayList<>(items.size());
        Map<UUID, Optional<CredentialLeaseRepository.LeaseRecord>> leaseCache = new HashMap<>();
        boolean tokenUsable = claims != null && claims.hasScope(InternalTokenIssuer.SCOPE_USAGE_REPORT)
                && claims.userId() != null && claims.projectId() != null;
        for (Item item : items) {
            Result result = tokenUsable ? one(item, claims, leaseCache)
                    : new Result(item.callId(), false, "token_identity_missing");
            results.add(result);
            count(item, result);
        }
        return results;
    }

    private Result one(Item item, InternalAiClaims claims,
            Map<UUID, Optional<CredentialLeaseRepository.LeaseRecord>> leaseCache) {
        UUID callId = uuid(item.callId());
        if (callId == null) {
            return new Result(item.callId(), false, "call_id_invalid");
        }
        UUID leaseId = uuid(item.leaseId());
        if (leaseId == null) {
            return new Result(item.callId(), false, "lease_id_invalid");
        }
        if (item.status() == null || !STATUSES.contains(item.status())) {
            return new Result(item.callId(), false, "status_invalid");
        }
        Integer inputTokens = nonNegativeInt(item.inputTokens());
        Integer outputTokens = nonNegativeInt(item.outputTokens());
        Integer latencyMs = nonNegativeInt(item.latencyMs());
        if ((item.inputTokens() != null && inputTokens == null)
                || (item.outputTokens() != null && outputTokens == null)
                || (item.latencyMs() != null && latencyMs == null)) {
            return new Result(item.callId(), false, "number_invalid");
        }
        Optional<CredentialLeaseRepository.LeaseRecord> lease =
                leaseCache.computeIfAbsent(leaseId, leases::find);
        if (lease.isEmpty()) {
            return new Result(item.callId(), false, "lease_not_found");
        }
        CredentialLeaseRepository.LeaseRecord found = lease.get();
        if (!claims.userId().equals(found.userId()) || !claims.projectId().equals(found.projectId())) {
            return new Result(item.callId(), false, "lease_identity_mismatch");
        }
        String feature = LeasePurpose.parse(item.purpose()).map(Enum::name).orElse(found.purpose().name());
        String model = item.model() == null || item.model().isBlank() ? found.model() : item.model();
        boolean inserted = usage.insertIfAbsent(new UsageReportRepository.UsageRow(callId, leaseId,
                found.userId(), found.projectId(), truncate(feature, MAX_FEATURE_CHARS),
                truncate(found.providerType(), MAX_PROVIDER_CHARS), truncate(model, MAX_MODEL_CHARS),
                item.status(), latencyMs, inputTokens, outputTokens,
                truncate(item.errorCode(), MAX_ERROR_CODE_CHARS)));
        if (!inserted) {
            log.debug("usage report duplicate callId accepted: leaseId={}", leaseId);
        }
        return new Result(item.callId(), true, null);
    }

    private void count(Item item, Result result) {
        if (meterRegistry == null) {
            return;
        }
        String purpose = LeasePurpose.parse(item.purpose()).map(Enum::name).orElse("unknown");
        Counter.builder(METRIC_ITEMS)
                .tag("purpose", purpose)
                .tag("caller_type", UsageReportRepository.CALLER_TYPE_PYTHON)
                .tag("outcome", result.accepted() ? "accepted" : "rejected")
                .register(meterRegistry)
                .increment();
    }

    private static UUID uuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Integer nonNegativeInt(Long value) {
        if (value == null || value < 0 || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
