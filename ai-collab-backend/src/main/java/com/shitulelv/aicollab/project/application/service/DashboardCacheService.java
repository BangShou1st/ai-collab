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
 * <p>指标（Micrometer，低基数标签）：{@code dashboard.cache.operations} 的
 * {@code result} 标签取值 {@code hit|miss|fill|evict|error}，不携带 projectId/userId。</p>
 */
@Service
public class DashboardCacheService {

    private static final Logger log = LoggerFactory.getLogger(DashboardCacheService.class);

    static final String STATS_KEY_PREFIX = "ai-collab:dashboard:stats:v1:";
    static final String MILESTONES_KEY_PREFIX = "ai-collab:dashboard:milestones:v1:";

    private static final String METRIC_NAME = "dashboard.cache.operations";
    private static final TypeReference<List<com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView>>
            MILESTONE_LIST_TYPE = new TypeReference<>() { };

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final Duration baseTtl;
    private final Duration ttlJitter;

    public DashboardCacheService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${dashboard.cache.enabled:true}") boolean enabled,
            @Value("${dashboard.cache.ttl-seconds:90}") long ttlSeconds,
            @Value("${dashboard.cache.ttl-jitter-seconds:30}") long ttlJitterSeconds) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.baseTtl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.ttlJitter = Duration.ofSeconds(Math.max(0, ttlJitterSeconds));
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
        if (!cacheAvailable()) {
            return;
        }
        try {
            StringRedisTemplate template = redisTemplateProvider.getObject();
            Long deleted = template.delete(List.of(STATS_KEY_PREFIX + projectId, MILESTONES_KEY_PREFIX + projectId));
            if (deleted != null && deleted > 0) {
                record("evict");
            }
        } catch (Exception evictionFailure) {
            record("error");
            log.warn("看板缓存失效失败，残留旧值将由 TTL 兜底：projectId={}", projectId, evictionFailure);
        }
    }

    private String get(String key) {
        if (!cacheAvailable()) {
            record("miss");
            return null;
        }
        try {
            String json = redisTemplateProvider.getObject().opsForValue().get(key);
            record(json == null ? "miss" : "hit");
            return json;
        } catch (Exception redisFailure) {
            record("error");
            log.warn("看板缓存读取失败，回源数据库：key={}", key, redisFailure);
            return null;
        }
    }

    private boolean set(String key, String json) {
        if (!cacheAvailable()) {
            return false;
        }
        try {
            redisTemplateProvider.getObject().opsForValue().set(key, json, nextTtl());
            return true;
        } catch (Exception redisFailure) {
            record("error");
            log.warn("看板缓存写入失败：key={}", key, redisFailure);
            return false;
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
