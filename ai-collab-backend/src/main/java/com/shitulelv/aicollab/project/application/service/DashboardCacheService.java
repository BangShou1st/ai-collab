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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 项目看板聚合数据的 Redis 缓存（Cache Aside），覆盖缓存治理四件套：
 * 防雪崩（TTL 抖动）、防穿透（空里程碑列表短 TTL 标记）、防击穿（热点 key 逻辑过期 + 冷 key 互斥回源）、
 * 防不一致（事务提交后失效 + 有限 TTL 兜底）。
 *
 * <h3>缓存范围（阶段 C 决策）</h3>
 * 仅缓存与用户无关的项目级聚合：
 * <ul>
 *   <li>{@code DashboardTaskStatsView}（任务状态统计）</li>
 *   <li>{@code List<DashboardMilestoneProgressView>}（里程碑进度）</li>
 * </ul>
 * 含用户上下文的 {@code findProjectInfo}、最近任务、最近文档、活动列表一律不缓存，
 * 避免 redis 中出现用户维度数据或越权串用。权限校验（{@code ProjectAccessGuard.requireMember}）
 * 在调用方位于缓存读取之前，命中缓存不会绕过鉴权。
 *
 * <h3>防雪崩：TTL 随机抖动（±10%）</h3>
 * 大量项目键常在服务启动预热、批量回填或整点刷新等同一批次内写入，若 TTL 完全相同，
 * 它们会在同一时刻集体过期，瞬时全部回源数据库形成雪崩。物理 TTL 在逻辑 TTL
 * （默认 90s）基础上叠加 ±{@value #TTL_JITTER_PERCENT}% 随机抖动，
 * 把同批重建的 key 的过期时间点打散到 [90%, 110%] 区间，任何时刻过期的 key 数量
 * 与 key 总量成正比而非集中在某一点。
 *
 * <h3>空里程碑列表使用短 TTL</h3>
 * 空里程碑列表写入短 TTL（默认 60s）的标记，与非空列表共用缓存键，写入提交后统一失效。
 * 任务统计的聚合查询始终返回一行，零任务的全零统计走常规缓存路径。
 *
 * <h3>防击穿 A：热点 key 逻辑过期（读路径不停摆）</h3>
 * 看板是项目级热点 key。逻辑过期模式下缓存值是信封（见 {@link CacheEnvelope}）：
 * 物理 TTL = 逻辑 TTL + {@value #PHYSICAL_TTL_GRACE_SECONDS}s 宽限，值内嵌逻辑过期时间戳。
 * 命中逻辑过期 → 立即返回旧值 + 异步单飞重建（同 key 进程内互斥，保证只有一个线程在重建），
 * 读请求永远不等重建完成，消除"TTL 到点瞬间 N 个请求同时回源并阻塞"的击穿。
 * 逻辑 TTL 是触发刷新所需的条目年龄。刷新失败时，旧条目可以保留至物理过期。
 *
 * <p><b>逻辑过期模式下 evict 的语义决策：仍是物理 DEL，而非"标记逻辑过期"</b>。
 * 理由：写失效要的是"立即可见"，DEL 简单、原子、跨实例经共享 Redis 立即生效；
 * "标记逻辑过期"需要读改写信封（自带竞态）或 Lua 脚本，且标记后旧值仍会被服务到
 * 重建完成，反而拉长写后不可见窗口。两条路径分工明确——<b>失效（写）走删除，
 * 刷新（TTL 到期）走逻辑过期</b>。删除后首次读是冷 miss，走下面的互斥回源。</p>
 *
 * <h3>防击穿 B：冷 key miss 同 key 互斥回源（进程内单飞）</h3>
 * miss 时同 key 并发请求只有一个线程真正回源（leader），其余线程共享同一份 future 结果。
 * 回源是只读 DB 查询，同 key 重复回源的代价
 * 只是少数几次带索引的聚合查询（毫秒级），重复执行无害；而跨进程互斥锁（SETNX +
 * 看门狗续期 + 崩溃释放）引入的复杂度、故障面（锁丢失导致死等）远超收益。
 * 进程内单飞已把瞬时并发从 N 次回源收敛为 1 次回源 + N 次内存等待。
 *
 * <h3>一致性边界（如实声明：有界最终一致，非强一致）</h3>
 * <ul>
 *   <li>失效统一走 {@link #evictAfterCommit}：事务成功提交后才 DEL，回滚不触发失效。</li>
 *   <li>提交后 DEL 与并发回填之间存在竞态。读者可能在写入提交后才把此前的查询结果写入缓存。
 *       从回填起，默认 81~99s 后的首次读取触发异步重建。重建或失效持续失败时，
 *       该条目从回填起最多保留 399s。</li>
 *   <li>Redis 故障时全部操作静默降级为直读数据库，缓存层不参与可用性关键路径；
 *       降级期间单飞仍然生效，同 key 并发读合并为一次 DB 查询。</li>
 * </ul>
 *
 * <h3>轻量熔断（纯 JVM 内存态，无新依赖、无分布式组件）</h3>
 * <ul>
 *   <li>任一 Redis 操作失败即打开熔断并进入冷却窗口（默认 30s，可配置）：
 *       同一次 {@code getDashboard} 请求内的其余 Redis 操作在毫秒级内全部短路跳过，
 *       直接走数据库回源。</li>
 *   <li>冷却到期后半开：放行一次试探操作，成功则自动恢复；失败则重新打开并重新计时。</li>
 *   <li>多实例各自维护独立熔断是<b>可接受的边界</b>：实例熔断期间最多退化为直读数据库；
 *       写失效失败残留的旧值由有限 TTL 兜底。</li>
 *   <li>跳过的操作仅记 {@code skip} 指标，不打日志，避免 Redis 停机期间的日志洪水。</li>
 * </ul>
 *
 * <p>指标（Micrometer，低基数标签）：{@code dashboard.cache.operations} 的 {@code result}
 * 标签取值 {@code hit|miss}（Redis 层读写）、{@code fill|empty_fill}（回填真值/空标记）、
 * {@code empty_hit}（空标记命中，未查库）、{@code stale}（逻辑过期返回旧值）、
 * {@code rebuild}（触发异步重建）、{@code evict|error|open|half_open|skip|recover}（失效与熔断），
 * 不携带 projectId/userId。</p>
 */
@Service
public class DashboardCacheService {

    private static final Logger log = LoggerFactory.getLogger(DashboardCacheService.class);

    static final String STATS_KEY_PREFIX = "ai-collab:dashboard:stats:v1:";
    static final String MILESTONES_KEY_PREFIX = "ai-collab:dashboard:milestones:v1:";

    /** TTL 随机抖动幅度（百分比）：防雪崩，同批写入的 key 过期点打散在 ±10% 区间。 */
    static final int TTL_JITTER_PERCENT = 10;

    /** 逻辑过期模式下物理 TTL 在逻辑 TTL 之上追加的宽限秒数，给异步重建留足完成时间。 */
    static final long PHYSICAL_TTL_GRACE_SECONDS = 300;

    private static final String METRIC_NAME = "dashboard.cache.operations";
    private static final TypeReference<List<com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView>>
            MILESTONE_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<CacheEnvelope> ENVELOPE_TYPE = new TypeReference<>() { };

    /**
     * 缓存值信封（JSON 存储）：真实负载 + 空标记 + 逻辑过期时间戳。
     *
     * <p>{@code data} 存视图序列化后的 JSON 字符串（二次编码），换取信封解析与视图解析
     * 各自独立容错；{@code nullMarker=true} 表示空值标记（{@code data} 为 null）；
     * {@code logicalExpireAtMillis} 为逻辑过期时间戳（0 表示无逻辑过期语义，仅空标记使用）。</p>
     */
    record CacheEnvelope(String data, boolean nullMarker, long logicalExpireAtMillis) {
        static final CacheEnvelope NULL_MARKER = new CacheEnvelope(null, true, 0L);
    }

    /** 熔断状态：关闭（正常读写）→ 打开（冷却期全跳过）→ 半开（放行一次试探）。 */
    private enum BreakerState { CLOSED, OPEN, HALF_OPEN }

    /** 熔断快照：状态 + 进入该状态的单调时刻（nanoTime，不受系统时钟回拨影响）。 */
    private record BreakerSnapshot(BreakerState state, long sinceNanos) { }

    /**
     * 同 key 单飞槽位：{@code future} 承载回源结果（leader 完成后其余线程共享），
     * {@code leaderClaimed} 保证同 key 只有一个线程真正执行回源/重建。
     * 读写路径（冷 miss 同步回源）与异步重建共用同一槽位，天然互斥。
     * 两条路径都必须完成 {@code future}：冷 miss 的跟随线程会 {@code join} 该槽位，
     * 漏完成会让跟随线程永久等待。
     */
    private static final class InFlight {
        private final CompletableFuture<Object> future = new CompletableFuture<>();
        private final AtomicBoolean leaderClaimed = new AtomicBoolean(false);
    }

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    /** 逻辑 TTL 基础值，用于确定何时触发按需刷新。 */
    private final Duration baseTtl;
    /** 里程碑空列表标记 TTL。 */
    private final Duration nullTtl;
    private final long breakerCooldownNanos;
    /** 时钟可注入（测试确定性验证逻辑过期），生产为系统 UTC 时钟。 */
    private final Clock clock;
    /** 异步重建执行器：虚拟线程（Java 21），重建为短暂阻塞 IO，不为读请求占住平台线程。 */
    private final Executor rebuildExecutor;
    private final AtomicReference<BreakerSnapshot> breaker =
            new AtomicReference<>(new BreakerSnapshot(BreakerState.CLOSED, System.nanoTime()));
    /** 同 key 单飞注册表：冷 miss 回源与逻辑过期异步重建共用。 */
    private final ConcurrentHashMap<String, InFlight> inFlightLoads = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public DashboardCacheService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${dashboard.cache.enabled:true}") boolean enabled,
            @Value("${dashboard.cache.ttl-seconds:90}") long ttlSeconds,
            @Value("${dashboard.cache.null-ttl-seconds:60}") long nullTtlSeconds,
            @Value("${dashboard.cache.failure-cooldown:30s}") Duration failureCooldown) {
        this(redisTemplateProvider, objectMapper, meterRegistry, enabled, ttlSeconds, nullTtlSeconds,
                failureCooldown, Clock.systemUTC(), Executors.newVirtualThreadPerTaskExecutor());
    }

    /** 测试专用构造器：注入可控时钟与重建执行器，便于确定性地验证逻辑过期与异步重建。 */
    DashboardCacheService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            boolean enabled,
            long ttlSeconds,
            long nullTtlSeconds,
            Duration failureCooldown,
            Clock clock,
            Executor rebuildExecutor) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.baseTtl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.nullTtl = Duration.ofSeconds(Math.max(1, nullTtlSeconds));
        // 冷却窗口下限 100ms，避免配置为 0 时熔断完全失效（退化成每次请求都试探）
        this.breakerCooldownNanos = Math.max(100_000_000L, failureCooldown.toNanos());
        this.clock = clock;
        this.rebuildExecutor = rebuildExecutor;
    }

    /** 读取任务状态统计，未命中时经同 key 互斥回源。统计查询始终返回一行。 */
    public com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView getOrLoadStats(
            UUID projectId, Supplier<com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView> loader) {
        String key = STATS_KEY_PREFIX + projectId;
        CacheEnvelope envelope = readEnvelope(key);
        // 滚动升级期间把缓存里旧版写入的任务统计空标记视为未命中。
        if (envelope == null || envelope.nullMarker()) {
            return loadAndStoreStats(key, loader);
        }
        if (isLogicallyExpired(envelope)) {
            // 防击穿：立即返回旧值，重建交给异步单飞（同 key 只有一个重建在飞）
            record("stale");
            triggerAsyncRebuild(key, loader, value -> storeStatsValue(key, value));
        }
        var decoded = decodeStats(envelope, key);
        if (decoded != null) {
            return decoded;
        }
        // 信封合法但负载损坏：按 miss 处理并同步互斥回源，绝不把 null 直接透给调用方
        return loadAndStoreStats(key, loader);
    }

    /**
     * 读取里程碑进度列表，语义同 {@link #getOrLoadStats}。
     *
     * <p>空列表（无里程碑）也视为"空"写空标记：命中标记返回不可变空列表，
     * 不查库；写路径（里程碑增删改）提交后 {@code evictAfterCommit} 删除该 key，
     * 空标记不会挡住刚创建的里程碑。</p>
     */
    public List<com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView> getOrLoadMilestones(
            UUID projectId,
            Supplier<List<com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView>> loader) {
        String key = MILESTONES_KEY_PREFIX + projectId;
        CacheEnvelope envelope = readEnvelope(key);
        if (envelope == null) {
            return loadAndStoreMilestones(key, loader);
        }
        if (envelope.nullMarker()) {
            record("empty_hit");
            return List.of();
        }
        if (isLogicallyExpired(envelope)) {
            record("stale");
            triggerAsyncRebuild(key, loader, value -> storeMilestonesValue(key, value));
        }
        var decoded = decodeMilestones(envelope, key);
        if (decoded != null && !decoded.isEmpty()) {
            return decoded;
        }
        // 信封合法但负载损坏（或解码为空，理论边界）：按 miss 处理并同步互斥回源
        return loadAndStoreMilestones(key, loader);
    }

    /** 冷 miss / 负载损坏兜底：同 key 互斥回源并回填。 */
    private com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView loadAndStoreStats(
            String key, Supplier<com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView> loader) {
        return singleFlight(key, () -> {
            var loaded = loader.get();
            storeStatsValue(key, loaded);
            return loaded;
        });
    }

    /** 语义同 {@link #loadAndStoreStats}；loader 返回 null 时对调用方表现为空列表。 */
    private List<com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView> loadAndStoreMilestones(
            String key,
            Supplier<List<com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView>> loader) {
        return singleFlight(key, () -> {
            var loaded = loader.get();
            storeMilestonesValue(key, loaded);
            return loaded == null
                    ? List.<com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView>of()
                    : loaded;
        });
    }

    /**
     * 事务提交后失效项目看板缓存。
     *
     * <p>当前存在事务同步时注册 {@code afterCommit} 回调（回滚不失效）；无事务时立即失效。
     * 失效失败时，由按需刷新或物理 TTL 清理旧条目，业务事务的结果不受影响。
     * 逻辑过期模式下本方法语义仍为<b>物理 DEL</b>（立即可见、跨实例生效），
     * 逻辑过期只负责"TTL 到期后的刷新"路径，二者分工见类注释。</p>
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

    /** 立即失效项目看板缓存（stats + milestones 两个键一起删，含空标记）。 */
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
            log.warn("看板缓存失效失败，旧条目将按需刷新或过期：projectId={}", projectId, evictionFailure);
        }
    }

    // ---------- 读路径核心：信封解析 / 逻辑过期判定 / 解码容错 ----------

    /** 读取并解析信封；键不存在或信封损坏返回 null（调用方按 miss 处理并回源）。 */
    private CacheEnvelope readEnvelope(String key) {
        String json = get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ENVELOPE_TYPE);
        } catch (Exception envelopeCorrupted) {
            // 信封结构损坏（非法 JSON / 旧格式残留）：视同未命中并删除，绝不把异常抛给业务读路径
            record("error");
            deleteKey(key);
            log.warn("看板缓存信封解析失败，已删除脏数据：key={}", key, envelopeCorrupted);
            return null;
        }
    }

    /** 逻辑过期判定：信封带时间戳且已越过即视为"逻辑过期"（空标记恒为 0，不参与）。 */
    private boolean isLogicallyExpired(CacheEnvelope envelope) {
        return envelope.logicalExpireAtMillis() > 0 && clock.millis() >= envelope.logicalExpireAtMillis();
    }

    private com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView decodeStats(
            CacheEnvelope envelope, String key) {
        try {
            return objectMapper.readValue(envelope.data(),
                    com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView.class);
        } catch (Exception parseFailure) {
            record("error");
            deleteKey(key);
            log.warn("看板任务统计缓存负载反序列化失败，已删除脏数据：key={}", key, parseFailure);
            return null;
        }
    }

    private List<com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView> decodeMilestones(
            CacheEnvelope envelope, String key) {
        try {
            return objectMapper.readValue(envelope.data(), MILESTONE_LIST_TYPE);
        } catch (Exception parseFailure) {
            record("error");
            deleteKey(key);
            log.warn("看板里程碑进度缓存负载反序列化失败，已删除脏数据：key={}", key, parseFailure);
            return null;
        }
    }

    // ---------- 写路径核心：真值回填 / 空标记 / TTL 计算 ----------

    /** 回填任务统计，包括全零统计。 */
    private void storeStatsValue(String key, com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView value) {
        storeValue(key, Objects.requireNonNull(value, "Task statistics query must return a row"));
    }

    /** 回填里程碑进度，空列表使用短 TTL 标记。 */
    private void storeMilestonesValue(String key,
                                      List<com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView> value) {
        if (value == null || value.isEmpty()) {
            storeNullMarker(key);
            return;
        }
        storeValue(key, value);
    }

    private void storeValue(String key, Object value) {
        try {
            String data = objectMapper.writeValueAsString(value);
            long logicalTtlMillis = nextLogicalTtlMillis();
            CacheEnvelope envelope = new CacheEnvelope(data, false, clock.millis() + logicalTtlMillis);
            Duration physicalTtl = Duration.ofMillis(logicalTtlMillis).plusSeconds(PHYSICAL_TTL_GRACE_SECONDS);
            if (writeEnvelope(key, envelope, physicalTtl)) {
                record("fill");
            }
        } catch (Exception fillFailure) {
            record("error");
            log.warn("看板缓存回填失败：key={}", key, fillFailure);
        }
    }

    /** 写入空值标记（短 TTL，默认 60s）：与真实值同 key，写路径 evict 时一并删除。 */
    private void storeNullMarker(String key) {
        if (writeEnvelope(key, CacheEnvelope.NULL_MARKER, nullTtl)) {
            record("empty_fill");
        }
    }

    private boolean writeEnvelope(String key, CacheEnvelope envelope, Duration ttl) {
        if (!cacheAvailable() || !breakerAllows()) {
            // 熔断打开/半开期被跳过：已在 breakerAllows 记 skip 指标，不打日志
            return false;
        }
        try {
            String json = objectMapper.writeValueAsString(envelope);
            redisTemplateProvider.getObject().opsForValue().set(key, json, ttl);
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
     * 逻辑 TTL = 基础值 ± {@value #TTL_JITTER_PERCENT}% 随机抖动（防雪崩：
     * 同批回填的 key 过期点打散在 [81s, 99s]，不会在同一点集体过期）。
     */
    private long nextLogicalTtlMillis() {
        long baseMillis = baseTtl.toMillis();
        long spread = baseMillis * TTL_JITTER_PERCENT / 100;
        return baseMillis + ThreadLocalRandom.current().nextLong(-spread, spread + 1);
    }

    // ---------- 单飞：冷 miss 互斥回源 + 逻辑过期异步重建 ----------

    /**
     * 同 key 互斥回源（进程内单飞）：首个到达的线程成为 leader 并同步执行
     * {@code loadAndStore}（含回填），并发线程共享 leader 的 future 结果。
     *
     * <p>不跨进程加锁：回源是只读 DB 查询，重复执行无害且代价小（毫秒级聚合查询），
     * 跨进程互斥锁的复杂度与故障面（SETNX + 看门狗 + 崩溃释放）不匹配其收益，
     * 见类注释"防击穿 B"。leader 抛出异常时向所有共享线程传播同一异常并清理槽位，
     * 下一次调用可重新回源。</p>
     */
    @SuppressWarnings("unchecked")
    private <T> T singleFlight(String key, Supplier<T> loadAndStore) {
        InFlight flight = inFlightLoads.computeIfAbsent(key, k -> new InFlight());
        if (flight.leaderClaimed.compareAndSet(false, true)) {
            try {
                T value = loadAndStore.get();
                flight.future.complete(value);
                return value;
            } catch (RuntimeException | Error failure) {
                flight.future.completeExceptionally(failure);
                throw failure;
            } finally {
                inFlightLoads.remove(key, flight);
            }
        }
        try {
            return (T) flight.future.join();
        } catch (CompletionException joinFailure) {
            Throwable cause = joinFailure.getCause();
            if (cause instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            throw joinFailure;
        }
    }

    /**
     * 触发逻辑过期后的异步单飞重建：同 key 已有回源/重建在飞则直接跳过
     * （共享同一 InFlight 槽位，天然去重）。重建在虚拟线程上执行，失败仅降级为
     * 保留旧值（物理 TTL 兜底，下次逻辑过期判定会再次触发重建），绝不影响已返回的旧值。
     *
     * <p>异步重建是单飞槽位的拥有者：无论成功、失败还是执行器拒绝，都必须完成
     * {@code future} 并移除槽位，否则随后因写失效进入冷 miss 的线程会永久阻塞。</p>
     */
    private <T> void triggerAsyncRebuild(String key, Supplier<T> loader, Consumer<T> storer) {
        InFlight flight = inFlightLoads.computeIfAbsent(key, k -> new InFlight());
        if (!flight.leaderClaimed.compareAndSet(false, true)) {
            return;
        }
        record("rebuild");
        try {
            rebuildExecutor.execute(() -> {
                try {
                    T value = loader.get();
                    storer.accept(value);
                    flight.future.complete(value);
                } catch (RuntimeException rebuildFailure) {
                    // 重建失败不升级日志级别：旧值仍在且由物理 TTL 兜底，DB 故障会在业务读路径显式暴露
                    flight.future.completeExceptionally(rebuildFailure);
                    log.debug("看板缓存异步重建失败，保留旧值待下次重建：key={}", key, rebuildFailure);
                } catch (Error rebuildError) {
                    // Error 仍向执行器传播，但必须先唤醒等待同一槽位的冷 miss 线程
                    flight.future.completeExceptionally(rebuildError);
                    throw rebuildError;
                } finally {
                    inFlightLoads.remove(key, flight);
                }
            });
        } catch (RuntimeException rejected) {
            // 执行器已关闭/拒绝任务：任务不会运行，必须在这里完成槽位，避免永久等待
            flight.future.completeExceptionally(rejected);
            inFlightLoads.remove(key, flight);
            log.debug("看板缓存异步重建任务提交失败，等待下次重建：key={}", key, rejected);
        }
    }

    // ---------- Redis 原语与熔断（沿用既有机制） ----------

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

    private void deleteKey(String key) {
        if (!cacheAvailable() || !breakerAllows()) {
            return;
        }
        try {
            redisTemplateProvider.getObject().delete(key);
            onRedisOperationSuccess();
        } catch (Exception deleteFailure) {
            onRedisOperationFailure();
            record("error");
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

    private boolean cacheAvailable() {
        return enabled && redisTemplateProvider.getIfAvailable() != null;
    }

    private void record(String result) {
        meterRegistry.counter(METRIC_NAME, "result", result).increment();
    }
}
