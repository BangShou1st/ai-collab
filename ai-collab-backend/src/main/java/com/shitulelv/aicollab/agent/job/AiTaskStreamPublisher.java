package com.shitulelv.aicollab.agent.job;

import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Stream 生产端（P1 契约 §4.1/§4.2、R1 §9.2）。
 *
 * <p>{@link AiJobQueuedEvent} 在业务事务 {@code AFTER_COMMIT} 后才 XADD（没有事务时立即执行），
 * 回滚的任务绝不会被派发。XADD 失败只记日志与指标 {@code ai_task_dispatch_failures_total}，
 * 不回滚业务，由补偿扫描兜底重派。</p>
 *
 * <p>消息字段四个都是字符串：{@code taskType/taskId/epoch/token}。{@code token} 是只对本任务、本 epoch
 * 有效的 {@code task:{taskId}} 令牌（exp ≤ 300s）；日志只记 taskId/epoch，<b>绝不记令牌</b>。
 * apiKey 永远不进 Redis。</p>
 *
 * <p>消费组 {@code ai-worker} 在首次 XADD 前用 {@code XGROUP CREATE … $ MKSTREAM} 幂等创建
 * （BUSYGROUP 视为成功），保证组建立之前不会有消息落在 {@code $} 之前被组错过。</p>
 */
@Component
public class AiTaskStreamPublisher {

    public static final String STREAM_KEY = "aicollab:ai:tasks";
    public static final String CONSUMER_GROUP = "ai-worker";
    public static final long MAX_LEN = 10_000L;
    /** Prometheus 导出名 {@code ai_task_dispatch_failures_total}。 */
    public static final String METRIC_DISPATCH_FAILURES = "ai_task_dispatch_failures";
    public static final List<String> MEMORY_PURPOSES = List.of("MEMORY_EXTRACTION");

    private static final Logger log = LoggerFactory.getLogger(AiTaskStreamPublisher.class);

    private final StringRedisTemplate redis;
    private final InternalTokenIssuer issuer;
    private final MeterRegistry meters;
    private final AtomicBoolean groupReady = new AtomicBoolean();

    public AiTaskStreamPublisher(StringRedisTemplate redis, InternalTokenIssuer issuer,
            MeterRegistry meters) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQueued(AiJobQueuedEvent event) {
        publish(event.job());
    }

    /** 派发一条任务消息；失败返回 false（已记日志与指标），从不抛出。 */
    public boolean publish(AiJobRecord job) {
        try {
            if (job.createdBy() == null && !job.indexTask()) {
                // 记忆任务的令牌必须带 uid；没有归属用户的记忆任务无法派发（补偿扫描会持续重试并记指标）。
                // 索引任务是系统任务，令牌不带 uid（P2-R4 R4-1、R1-3）。
                throw new IllegalStateException("task_owner_missing");
            }
            ensureGroup();
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("taskType", job.jobType());
            fields.put("taskId", job.id().toString());
            fields.put("epoch", Long.toString(job.leaseEpoch()));
            fields.put("token", taskToken(job));
            MapRecord<String, String, String> record = StreamRecords.newRecord()
                    .in(STREAM_KEY).ofMap(fields);
            redis.opsForStream().add(record, XAddOptions.maxlen(MAX_LEN).approximateTrimming(true));
            return true;
        } catch (RuntimeException failure) {
            groupReady.set(false);
            log.warn("ai task dispatch failed task_id={} epoch={} error={}",
                    job.id(), job.leaseEpoch(), failure.getClass().getSimpleName());
            try {
                meters.counter(METRIC_DISPATCH_FAILURES).increment();
            } catch (RuntimeException ignored) {
                // 指标失败不影响业务。
            }
            return false;
        }
    }

    /**
     * Stream 消息、claim 与 heartbeat 响应共用的任务令牌。按任务类型签发：记忆任务沿用 P1 令牌；
     * 索引任务用索引任务令牌（{@code purp=["EMBEDDING"]}，{@code INDEX_DOCUMENT} 另带
     * {@code index:document:{docId}}；P2 契约 §4.1、§4.2）。
     */
    public String taskToken(AiJobRecord job) {
        if (job.indexTask()) {
            UUID documentId = AiJobTypes.INDEX_DOCUMENT.equals(job.jobType()) ? job.documentId() : null;
            return issuer.issueIndexTaskToken(job.id(), job.leaseEpoch(), job.projectId(), documentId);
        }
        return issuer.issueTaskToken(job.id(), job.leaseEpoch(), job.createdBy(), job.projectId(),
                MEMORY_PURPOSES);
    }

    private void ensureGroup() {
        if (groupReady.get()) {
            return;
        }
        byte[] key = STREAM_KEY.getBytes(StandardCharsets.UTF_8);
        try {
            redis.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(key, CONSUMER_GROUP, ReadOffset.latest(), true));
        } catch (RuntimeException failure) {
            if (!isBusyGroup(failure)) {
                throw failure;
            }
        }
        groupReady.set(true);
    }

    static boolean isBusyGroup(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
