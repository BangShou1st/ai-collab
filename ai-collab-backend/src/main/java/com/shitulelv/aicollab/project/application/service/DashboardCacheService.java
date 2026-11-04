package com.shitulelv.aicollab.project.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 项目看板聚合数据的 Redis 缓存（Cache Aside）。
 *
 * <h3>缓存范围（阶段 C 决策）</h3>
 * 仅缓存与用户无关的项目级聚合：
 * <ul>
 *   <li>{@code DashboardTaskStatsView}（任务状态统计）</li>
 *   <li>{@code List<DashboardMilestoneProgressView>}（里程碑进度）</li>
 * </ul>
 * 含用户上下文的 {@code findProjectInfo}、最近任务、最近文档、活动列表一律不缓存，
 * 避免 redis 中出现用户维度数据或越权串用。
 *
 * <h3>一致性边界（如实声明：有界最终一致，非强一致）</h3>
 * <ul>
 *   <li>失效统一走 {@link #evictAfterCommit}：事务成功提交后才 DEL，回滚不触发失效。</li>
 *   <li>提交后 DEL 与并发回填之间存在经典竞态（读旧值→写者提交并 DEL→读者回填旧值），
 *       残留窗口由有限 TTL 兜底（默认 90s + 抖动）。看板为聚合统计场景，
 *       分钟级陈旧可接受；为消除该竞态引入版本令牌/分布式锁的复杂度不匹配其收益。</li>
 *   <li>Redis 故障时全部操作静默降级为直读数据库，缓存层不参与可用性关键路径。</li>
 * </ul>
 *
 * <h3>轻量熔断（纯 JVM 内存态，无新依赖、无分布式组件）</h3>
 * <ul>
 *   <li>任一 Redis 操作失败即打开熔断并进入冷却窗口（默认 30s，可配置）：
 *       同一次 {@code getDashboard} 请求内的其余 Redis 操作（如 loadStats 失败后的
 *       storeStats / loadMilestones / storeMilestones）在毫秒级内全部短路跳过，
 *       不再向已故障的 Redis 发起注定超时的网络请求，直接走数据库回源。</li>
 *   <li>冷却到期后半开：放行一次试探操作，成功则自动恢复缓存读写并关闭熔断；
 *       失败则重新打开并重新计时。</li>
 *   <li>多实例各自维护独立熔断（不共享状态）是<b>可接受的边界</b>：缓存层不是
 *       一致性关键路径，实例熔断期间最多退化为直读数据库；写失效失败残留的
 *       旧值由有限 TTL 兜底。</li>
 *   <li>跳过的操作仅记 {@code skip} 指标，不打日志，避免 Redis 停机期间的日志洪水。</li>
 * </ul>
 *
 * <p>指标（Micrometer，低基数标签）：{@code dashboard.cache.operations} 的
 * {@code result} 标签取值 {@code hit|miss|fill|evict|error|open|half_open|skip|recover}，
 * 不携带 projectId/userId。其中 {@code open}（熔断打开）、{@code half_open}（半开放行试探）、
 * {@code recover}（试探成功恢复）各在状态转移时记一次，{@code skip} 记每次被熔断跳过的操作。</p>
 */
@Service
public class DashboardCacheService {

    private static final Logger log = LoggerFactory.getLogger(DashboardCacheService.class);

    static final String STATS_KEY_PREFIX = "ai-collab:dashboard:stats:v1:";
    static final String MILESTONES_KEY_PREFIX = "ai-collab:dashboard:milestones:v1:";

    private static final String METRIC_NAME = "dashboard.cache.operations";
    private static final TypeReference<List<com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView>>
            MILESTONE_LIST_TYPE = new TypeReference<>() { };

    /** 熔断状态：关闭（正常读写）→ 打开（冷却期全跳过）→ 半开（放行一次试探）。 */
    private enum BreakerState { CLOSED, OPEN, HALF_OPEN }

    /** 熔断快照：状态 + 进入该状态的单调时刻（nanoTime，不受系统时钟回拨影响）。 */
    private record BreakerSnapshot(BreakerState state, long sinceNanos) { }

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final Duration baseTtl;
    private final Duration ttlJitter;
    private final long breakerCooldownNanos;
    private final AtomicReference<BreakerSnapshot> breaker =
            new AtomicReference<>(new BreakerSnapshot(BreakerState.CLOSED, System.nanoTime()));

    public DashboardCacheService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${dashboard.cache.enabled:true}") boolean enabled,
            @Value("${dashboard.cache.ttl-seconds:90}") long ttlSeconds,
            @Value("${dashboard.cache.ttl-jitter-seconds:30}") long ttlJitterSeconds,
            @Value("${dashboard.cache.failure-cooldown:30s}") Duration failureCooldown) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.baseTtl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.ttlJitter = Duration.ofSeconds(Math.max(0, ttlJitterSeconds));
        // 冷却窗口下限 100ms，避免配置为 0 时熔断完全失效（退化成每次请求都试探）
        this.breakerCooldownNanos = Math.max(100_000_000L, failureCooldown.toNanos());
    }

    /** 读取任务状态统计缓存；未命中、缓存关闭或 Redis 故障返回 empty（调用方回源数据库）。 */
    public com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView loadStats(UUID projectId) {
        String json = get(STATS_KEY_PREFIX + projectId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView.class);
        } catch (Exception parseFailure) {
            // 值损坏或结构不兼容：视同未命中并删除，绝不把异常抛给业务读路径
            record("error");
            evictNow(projectId);
            log.warn("看板任务统计缓存反序列化失败，已删除脏数据：projectId={}", projectId, parseFailure);
            return null;
        }
    }

    /** 读取里程碑进度缓存；语义同 {@link #loadStats}。 */
    public List<com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView> loadMilestones(UUID projectId) {
        String json = get(MILESTONES_KEY_PREFIX + projectId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MILESTONE_LIST_TYPE);
        } catch (Exception parseFailure) {
            record("error");
            evictNow(projectId);
            log.warn("看板里程碑进度缓存反序列化失败，已删除脏数据：projectId={}", projectId, parseFailure);
            return null;
        }
    }

    /** 回填任务状态统计缓存（仅缓存层性能优化，失败静默）。 */
    public void storeStats(UUID projectId, com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView stats) {
        if (stats == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(stats);
            if (set(STATS_KEY_PREFIX + projectId, json)) {
                record("fill");
            }
        } catch (Exception fillFailure) {
            record("error");
            log.warn("看板任务统计缓存回填失败：projectId={}", projectId, fillFailure);
        }
    }

    /** 回填里程碑进度缓存（仅缓存层性能优化，失败静默）。 */
    public void storeMilestones(
            UUID projectId,
            List<com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView> milestones) {
        if (milestones == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(milestones);
            if (set(MILESTONES_KEY_PREFIX + projectId, json)) {
                record("fill");
            }
        } catch (Exception fillFailure) {
            record("error");
            log.warn("看板里程碑进度缓存回填失败：projectId={}", projectId, fillFailure);
        }
    }

    /**
     * 事务提交后失效项目看板缓存。
     *
     * <p>当前存在事务同步时注册 {@code afterCommit} 回调（回滚不失效）；无事务时立即失效。
     * 失效失败不抛出——残留旧值由 TTL 兜底，业务事务的结果不受影响。</p>
     */
    public void evictAfterCommit(UUID projectId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictNow(projectId);
                }
            });
        } else {
            evictNow(projectId);
        }
    }

    /** 立即失效项目看板缓存（stats + milestones 两个键一起删）。 */
    public void evictNow(UUID projectId) {
        if (!cacheAvailable() || !breakerAllows()) {
            return;
        }
        try {
            StringRedisTemplate template = redisTemplateProvider.getObject();
            Long deleted = template.delete(List.of(STATS_KEY_PREFIX + projectId, MILESTONES_KEY_PREFIX + projectId));
            onRedisOperationSuccess();
            if (deleted != null && deleted > 0) {
                record("evict");
            }
        } catch (Exception evictionFailure) {
            onRedisOperationFailure();
            record("error");
            log.warn("看板缓存失效失败，残留旧值将由 TTL 兜底：projectId={}", projectId, evictionFailure);
        }
    }

    private String get(String key) {
        if (!cacheAvailable()) {
            record("miss");
            return null;
        }
        if (!breakerAllows()) {
            // 熔断打开/半开期被跳过：已在 breakerAllows 记 skip 指标，不打日志
            return null;
        }
        try {
            String json = redisTemplateProvider.getObject().opsForValue().get(key);
            onRedisOperationSuccess();
            record(json == null ? "miss" : "hit");
            return json;
        } catch (Exception redisFailure) {
            onRedisOperationFailure();
            record("error");
            log.warn("看板缓存读取失败，回源数据库：key={}", key, redisFailure);
            return null;
        }
    }

    private boolean set(String key, String json) {
        if (!cacheAvailable()) {
            return false;
        }
        if (!breakerAllows()) {
            // 熔断打开/半开期被跳过：已在 breakerAllows 记 skip 指标，不打日志
            return false;
        }
        try {
            redisTemplateProvider.getObject().opsForValue().set(key, json, nextTtl());
            onRedisOperationSuccess();
            return true;
        } catch (Exception redisFailure) {
            onRedisOperationFailure();
            record("error");
            log.warn("看板缓存写入失败：key={}", key, redisFailure);
            return false;
        }
    }

    /**
     * 熔断放行判定（线程安全，CAS 无锁）：
     * <ul>
     *   <li>CLOSED：放行。</li>
     *   <li>OPEN：冷却期内跳过（记 {@code skip} 指标）；冷却到期懒触发转入 HALF_OPEN，
     *       由完成该次状态转移的调用方担任试探者并放行。</li>
     *   <li>HALF_OPEN：仅试探者放行，其余并发调用跳过；若试探长时间未决（超过冷却窗口），
     *       重新开放试探资格，避免熔断永久卡死在半开态。</li>
     * </ul>
     */
    private boolean breakerAllows() {
        while (true) {
            BreakerSnapshot current = breaker.get();
            long elapsed = System.nanoTime() - current.sinceNanos();
            switch (current.state()) {
                case CLOSED -> {
                    return true;
                }
                case OPEN -> {
                    if (elapsed < breakerCooldownNanos) {
                        record("skip");
                        return false;
                    }
                    BreakerSnapshot halfOpen =
                            new BreakerSnapshot(BreakerState.HALF_OPEN, System.nanoTime());
                    if (breaker.compareAndSet(current, halfOpen)) {
                        record("half_open");
                        return true;
                    }
                    // CAS 失败说明其他线程已转移状态，重读后重试
                }
                case HALF_OPEN -> {
                    if (elapsed >= breakerCooldownNanos
                            && breaker.compareAndSet(current,
                                    new BreakerSnapshot(BreakerState.HALF_OPEN, System.nanoTime()))) {
                        record("half_open");
                        return true;
                    }
                    record("skip");
                    return false;
                }
            }
        }
    }

    /** Redis 操作成功：若当前为半开试探且由本调用完成，则关闭熔断并记 {@code recover} 指标。 */
    private void onRedisOperationSuccess() {
        BreakerSnapshot current = breaker.get();
        if (current.state() == BreakerState.HALF_OPEN
                && breaker.compareAndSet(current,
                        new BreakerSnapshot(BreakerState.CLOSED, System.nanoTime()))) {
            record("recover");
        }
    }

    /** Redis 操作失败：CLOSED → 打开熔断（记 {@code open} 指标）；半开试探失败 → 重新打开并重新计时。 */
    private void onRedisOperationFailure() {
        BreakerSnapshot current = breaker.get();
        switch (current.state()) {
            case CLOSED -> {
                if (breaker.compareAndSet(current,
                        new BreakerSnapshot(BreakerState.OPEN, System.nanoTime()))) {
                    record("open");
                }
            }
            case HALF_OPEN ->
                    breaker.compareAndSet(current,
                            new BreakerSnapshot(BreakerState.OPEN, System.nanoTime()));
            case OPEN -> {
                // 已处于打开态（如并发试探失败），无需重复转移
            }
        }
    }

    /** TTL = 基础值 + 随机抖动，避免大量项目键同时过期造成集体回源。 */
    private Duration nextTtl() {
        if (ttlJitter.isZero()) {
            return baseTtl;
        }
        long jitterMillis = ThreadLocalRandom.current().nextLong(ttlJitter.toMillis() + 1);
        return baseTtl.plusMillis(jitterMillis);
    }

    private boolean cacheAvailable() {
        return enabled && redisTemplateProvider.getIfAvailable() != null;
    }

    private void record(String result) {
        meterRegistry.counter(METRIC_NAME, "result", result).increment();
    }
}
