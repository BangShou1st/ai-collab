package com.shitulelv.aicollab.project.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView;
import com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * DashboardCacheService 缓存治理单元测试（不依赖 Spring 容器与真实 Redis）。
 *
 * <p>覆盖：TTL ±10% 抖动范围（防雪崩）、空值标记写入/命中/写失效解除（防穿透）、
 * 逻辑过期返回旧值 + 异步单飞重建（防击穿）、冷 miss 同 key 互斥回源单飞、
 * 回源异常传播与重试、Redis 故障熔断降级回归。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardCacheServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    /** 进程内 Redis 替身：key → 信封 JSON，另记录每个 key 最近一次写入的 TTL。 */
    private final Map<String, String> store = new HashMap<>();
    private final Map<String, Duration> ttls = new HashMap<>();

    private DashboardCacheService service;

    /** 逻辑过期的旧值/重建后的新值，用可区分的两个统计视图。 */
    private static DashboardTaskStatsView stats(int total) {
        return new DashboardTaskStatsView(total, total, 0, 0, 0, 0, 0, 0.0);
    }

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0, String.class)));
        // ValueOperations.set(K, V, Duration) 返回 void，必须用 doAnswer 打桩
        org.mockito.Mockito.doAnswer(inv -> {
            String key = inv.getArgument(0, String.class);
            store.put(key, inv.getArgument(1, String.class));
            ttls.put(key, inv.getArgument(2, Duration.class));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.delete(anyCollection())).thenAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            return keys.stream().filter(k -> store.remove(k) != null).count();
        });
        when(redisTemplate.delete(anyString())).thenAnswer(inv -> store.remove(inv.getArgument(0, String.class)) != null);
        // 默认同步执行器：异步重建在 triggerAsyncRebuild 内联完成，便于确定性断言
        service = new DashboardCacheService(provider(), objectMapper, meterRegistry,
                true, 90, 60, Duration.ofSeconds(30), clock, Runnable::run);
    }

    private ObjectProvider<StringRedisTemplate> provider() {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject() {
                return redisTemplate;
            }

            @Override
            public StringRedisTemplate getIfAvailable() {
                return redisTemplate;
            }
        };
    }

    // ---------- 防雪崩：TTL ±10% 抖动 ----------

    /** 物理 TTL = 逻辑 TTL(90s ±10% = 81~99s) + 300s 宽限，落点应散布而非集中。 */
    @Test
    void physicalTtlCarriesJitterWithinBoundedRange() {
        for (int i = 0; i < 60; i++) {
            UUID projectId = UUID.randomUUID();
            service.getOrLoadStats(projectId, () -> stats(1));
            Duration ttl = ttls.get(DashboardCacheService.STATS_KEY_PREFIX + projectId);
            assertThat(ttl).as("第 %d 次写入的物理 TTL 应在 [381s, 399s]", i).isNotNull();
            assertThat(ttl.toMillis()).isBetween(381_000L, 399_000L);
        }
        List<Long> distinct = new ArrayList<>(ttls.values().stream().map(Duration::toMillis).distinct().toList());
        assertThat(distinct.size()).as("抖动应打散过期点（60 次写入出现大量不同 TTL）").isGreaterThan(10);
    }

    // ---------- 常规读路径与回填 ----------

    @Test
    void missLoadsBackfillsThenHitServesCacheWithoutReloading() {
        AtomicInteger loaderCount = new AtomicInteger();
        Supplier<DashboardTaskStatsView> loader = () -> {
            loaderCount.incrementAndGet();
            return stats(3);
        };
        UUID projectId = UUID.randomUUID();

        assertThat(service.getOrLoadStats(projectId, loader)).isEqualTo(stats(3));
        assertThat(loaderCount).hasValue(1);
        assertThat(meterRegistry.find("dashboard.cache.operations").tag("result", "miss").counter().count())
                .isEqualTo(1.0);

        // 第二次读命中缓存：不再回源
        assertThat(service.getOrLoadStats(projectId, loader)).isEqualTo(stats(3));
        assertThat(loaderCount).hasValue(1);
        assertThat(meterRegistry.find("dashboard.cache.operations").tag("result", "hit").counter().count())
                .isEqualTo(1.0);
    }

    // ---------- 防穿透：空值标记 ----------

    /** 回源为 null：写 60s 空标记；后续命中标记不再查库；evict 后恢复回源（防"空标记挡住新建数据"）。 */
    @Test
    void nullLoadStoresShortTtlMarkerAndEvictReleasesIt() {
        AtomicInteger loaderCount = new AtomicInteger();
        Supplier<DashboardTaskStatsView> loader = () -> {
            loaderCount.incrementAndGet();
            return null;
        };
        UUID projectId = UUID.randomUUID();
        String key = DashboardCacheService.STATS_KEY_PREFIX + projectId;

        assertThat(service.getOrLoadStats(projectId, loader)).isNull();
        assertThat(ttls.get(key)).as("空标记 TTL 应为短值 60s").isEqualTo(Duration.ofSeconds(60));
        DashboardCacheService.CacheEnvelope marker =
                readEnvelope(DashboardCacheService.STATS_KEY_PREFIX + projectId);
        assertThat(marker.nullMarker()).isTrue();
        assertThat(meterRegistry.find("dashboard.cache.operations").tag("result", "empty_fill").counter().count())
                .isEqualTo(1.0);

        // 命中空标记：直接返回空，不回源
        assertThat(service.getOrLoadStats(projectId, loader)).isNull();
        assertThat(loaderCount).hasValue(1);
        assertThat(meterRegistry.find("dashboard.cache.operations").tag("result", "empty_hit").counter().count())
                .isEqualTo(1.0);

        // 写路径 evict（同 key 删除）后立即恢复回源——空标记不会挡住刚创建的数据
        service.evictNow(projectId);
        assertThat(store).doesNotContainKey(key);
        assertThat(service.getOrLoadStats(projectId, loader)).isNull();
        assertThat(loaderCount).hasValue(2);
    }

    /** 里程碑空列表视为"空"：写空标记，命中返回不可变空列表且不回源。 */
    @Test
    void emptyMilestoneListStoresMarkerAndMarkerHitReturnsEmptyList() {
        AtomicInteger loaderCount = new AtomicInteger();
        Supplier<List<DashboardMilestoneProgressView>> loader = () -> {
            loaderCount.incrementAndGet();
            return List.of();
        };
        UUID projectId = UUID.randomUUID();

        assertThat(service.getOrLoadMilestones(projectId, loader)).isEmpty();
        assertThat(loaderCount).hasValue(1);

        assertThat(service.getOrLoadMilestones(projectId, loader)).isEmpty();
        assertThat(loaderCount).hasValue(1);
        assertThat(meterRegistry.find("dashboard.cache.operations").tag("result", "empty_hit").counter().count())
                .isEqualTo(1.0);
    }

    // ---------- 防击穿 A：逻辑过期返回旧值 + 异步单飞重建 ----------

    /** 命中逻辑过期：立即返回旧值；同步执行器下重建内联完成，下一次读拿到新值。 */
    @Test
    void logicallyExpiredValueServesStaleThenRebuilds() {
        AtomicInteger loaderCount = new AtomicInteger();
        UUID projectId = UUID.randomUUID();
        String key = DashboardCacheService.STATS_KEY_PREFIX + projectId;
        Supplier<DashboardTaskStatsView> loader = () -> stats(loaderCount.incrementAndGet());

        assertThat(service.getOrLoadStats(projectId, loader)).isEqualTo(stats(1));

        // 时钟越过逻辑 TTL 上界（99s），物理 TTL 仍宽裕（381s+）
        clock.advanceSeconds(120);

        // 命中逻辑过期：返回旧值 + 触发重建（rebuild 指标 +1），重建用同一 loader 回源新值
        assertThat(service.getOrLoadStats(projectId, loader)).isEqualTo(stats(1));
        assertThat(loaderCount).hasValue(2);
        assertThat(meterRegistry.find("dashboard.cache.operations").tag("result", "stale").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("dashboard.cache.operations").tag("result", "rebuild").counter().count())
                .isEqualTo(1.0);

        // 重建已回填新信封：下一次读拿到新值且不再回源
        assertThat(service.getOrLoadStats(projectId, loader)).isEqualTo(stats(2));
        assertThat(loaderCount).hasValue(2);
        DashboardCacheService.CacheEnvelope rebuilt = readEnvelope(key);
        assertThat(rebuilt.logicalExpireAtMillis()).as("重建后逻辑过期时间戳应刷新").isGreaterThan(clock.millis());
    }

    /** 逻辑过期读必须先返回旧值，重建在后台完成：用排队执行器证明"不等重建"。 */
    @Test
    void staleReadDoesNotWaitForBackgroundRebuild() {
        AtomicInteger loaderCount = new AtomicInteger();
        UUID projectId = UUID.randomUUID();
        Supplier<DashboardTaskStatsView> loader = () -> stats(loaderCount.incrementAndGet());

        assertThat(service.getOrLoadStats(projectId, loader)).isEqualTo(stats(1));
        clock.advanceSeconds(120);

        List<Runnable> queued = new ArrayList<>();
        DashboardCacheService queuedService = new DashboardCacheService(provider(), objectMapper,
                meterRegistry, true, 90, 60, Duration.ofSeconds(30), clock, queued::add);

        // 旧值先返回，重建任务只是入队未执行
        assertThat(queuedService.getOrLoadStats(projectId, loader)).isEqualTo(stats(1));
        assertThat(loaderCount).hasValue(1);
        assertThat(queued).hasSize(1);

        // 后台重建完成后，下一次读才看到新值
        queued.forEach(Runnable::run);
        assertThat(queuedService.getOrLoadStats(projectId, loader)).isEqualTo(stats(2));
        assertThat(loaderCount).hasValue(2);
    }

    // ---------- 防击穿 B：冷 miss 同 key 互斥回源 ----------

    /** 同 key 并发 miss：只有 leader 回源一次，其余线程共享同一结果。 */
    @Test
    void concurrentMissesLoadDatabaseOnlyOncePerKey() throws Exception {
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loaderCount = new AtomicInteger();
        UUID projectId = UUID.randomUUID();
        Supplier<DashboardTaskStatsView> loader = () -> {
            loaderCount.incrementAndGet();
            loaderEntered.countDown();
            try {
                releaseLoader.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return stats(7);
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DashboardTaskStatsView> leader = pool.submit(() -> service.getOrLoadStats(projectId, loader));
            assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<DashboardTaskStatsView> joiner = pool.submit(() -> service.getOrLoadStats(projectId, loader));
            // 等 joiner 也走到 miss 判定（已记 miss 指标）但仍未回源
            awaitCounterAtLeast("miss", 2.0);
            assertThat(loaderCount).as("joiner 不应重复回源").hasValue(1);

            releaseLoader.countDown();
            assertThat(leader.get(5, TimeUnit.SECONDS)).isEqualTo(stats(7));
            assertThat(joiner.get(5, TimeUnit.SECONDS)).isEqualTo(stats(7));
            assertThat(loaderCount).as("全流程只回源一次").hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 回源异常向调用方传播，且单飞槽位被清理，下一次调用可重新回源。 */
    @Test
    void loaderFailurePropagatesThenNextCallRetries() {
        AtomicInteger loaderCount = new AtomicInteger();
        UUID projectId = UUID.randomUUID();
        Supplier<DashboardTaskStatsView> failing = () -> {
            if (loaderCount.incrementAndGet() == 1) {
                throw new IllegalStateException("数据库抖动");
            }
            return stats(5);
        };

        assertThatThrownBy(() -> service.getOrLoadStats(projectId, failing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.getOrLoadStats(projectId, failing)).isEqualTo(stats(5));
        assertThat(loaderCount).hasValue(2);
    }

    // ---------- 熔断降级回归 ----------

    /** Redis 读失败触发熔断后，读请求降级直读数据库且结果不受影响。 */
    @Test
    void redisFailureDegradesToDatabaseLoad() {
        when(valueOps.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        AtomicInteger loaderCount = new AtomicInteger();
        UUID projectId = UUID.randomUUID();

        assertThat(service.getOrLoadStats(projectId, () -> stats(loaderCount.incrementAndGet())))
                .isEqualTo(stats(1));
        assertThat(loaderCount).hasValue(1);
        assertThat(meterRegistry.find("dashboard.cache.operations").tag("result", "open").counter().count())
                .isEqualTo(1.0);
    }

    // ---------- 助手 ----------

    private DashboardCacheService.CacheEnvelope readEnvelope(String key) {
        try {
            return objectMapper.readValue(store.get(key), DashboardCacheService.CacheEnvelope.class);
        } catch (Exception e) {
            throw new IllegalStateException("信封解析失败: " + store.get(key), e);
        }
    }

    /** 有界轮询等待指定指标达到阈值（并发场景下 joiner 到达时序不确定）。 */
    private void awaitCounterAtLeast(String result, double expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var counter = meterRegistry.find("dashboard.cache.operations").tag("result", result).counter();
            if (counter != null && counter.count() >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        org.assertj.core.api.Assertions.fail("指标 %s 未在 5s 内达到 %s", result, expected);
    }

    /** 可手动推进的测试时钟。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
