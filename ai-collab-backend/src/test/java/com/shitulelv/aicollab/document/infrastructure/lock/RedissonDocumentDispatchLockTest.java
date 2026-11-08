package com.shitulelv.aicollab.document.infrastructure.lock;

import com.shitulelv.aicollab.document.application.service.DocumentDispatchLock;
import com.shitulelv.aicollab.document.application.service.DocumentProcessingService;
import com.shitulelv.aicollab.document.application.service.DocumentTaskDispatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Redisson 文档派发锁语义验证（Redis 深化 P2）。
 *
 * <p>核心断言：同 documentId 跨线程互斥（tryLock(0) 拿不到直接失败）、释放后可重新
 * 获取、不同文档互不干扰；Redis 不可用时降级放行（claim CAS 兜底，不阻断主流程）。
 * 另覆盖 dispatcher 集成视角：并发派发同一文档时处理并发度被锁收敛到 1（抢不到锁的
 * 派发被跳过，状态行保持 UPLOADED 由恢复 Job 重新派发 —— 与 tryLock(0) 跳过语义一致）。</p>
 */
class RedissonDocumentDispatchLockTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    private static RedissonDocumentDispatchLock newLock() {
        RedissonDocumentDispatchLock lock = new RedissonDocumentDispatchLock(
                REDIS.getHost(), REDIS.getMappedPort(6379), "", 0);
        // 预热客户端：让建连失败留在专属的降级用例里，主用例直接走加锁路径
        UUID probe = UUID.randomUUID();
        assertThat(lock.tryLockForDispatch(probe, probe)).isTrue();
        lock.unlockAfterDispatch(probe, probe);
        return lock;
    }

    @Test
    void sameDocumentLockIsMutuallyExclusiveAcrossThreads() throws Exception {
        try (RedissonDocumentDispatchLock lock = newLock()) {
            UUID projectId = UUID.randomUUID();
            UUID documentId = UUID.randomUUID();

            AtomicReference<Boolean> secondOutcome = new AtomicReference<>();
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch secondAttempted = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                executor.submit(() -> {
                    assertThat(lock.tryLockForDispatch(projectId, documentId)).isTrue();
                    held.countDown();
                    try {
                        secondAttempted.await(5, TimeUnit.SECONDS);
                        // 持有期间不做任何操作，让第二个线程先撞锁
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    lock.unlockAfterDispatch(projectId, documentId);
                    return null;
                });
                executor.submit(() -> {
                    try {
                        held.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    secondOutcome.set(lock.tryLockForDispatch(projectId, documentId));
                    secondAttempted.countDown();
                    return null;
                });
            } finally {
                executor.shutdown();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(secondOutcome.get()).isFalse();
        }
    }

    @Test
    void lockIsReacquirableAfterRelease() {
        try (RedissonDocumentDispatchLock lock = newLock()) {
            UUID projectId = UUID.randomUUID();
            UUID documentId = UUID.randomUUID();

            assertThat(lock.tryLockForDispatch(projectId, documentId)).isTrue();
            lock.unlockAfterDispatch(projectId, documentId);

            // 释放后同实例（或任意实例）可重新获锁：watchdog 锁随处理结束即时归还
            assertThat(lock.tryLockForDispatch(projectId, documentId)).isTrue();
            lock.unlockAfterDispatch(projectId, documentId);
        }
    }

    @Test
    void differentDocumentsDoNotInterfere() {
        try (RedissonDocumentDispatchLock lock = newLock()) {
            UUID projectId = UUID.randomUUID();
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();

            assertThat(lock.tryLockForDispatch(projectId, first)).isTrue();
            // 不同文档的锁键不同：互不干扰
            assertThat(lock.tryLockForDispatch(projectId, second)).isTrue();

            lock.unlockAfterDispatch(projectId, first);
            lock.unlockAfterDispatch(projectId, second);
        }
    }

    @Test
    void degradedToPassThroughWhenRedisUnavailable() {
        // 指向必然拒绝连接的端口：客户端建连失败 → 降级放行（效率组件不阻断正确性路径）
        try (RedissonDocumentDispatchLock lock = new RedissonDocumentDispatchLock("127.0.0.1", 1, "", 0)) {
            UUID projectId = UUID.randomUUID();
            UUID documentId = UUID.randomUUID();

            assertThat(lock.tryLockForDispatch(projectId, documentId)).isTrue();
            // 降级路径下释放为无害空操作
            lock.unlockAfterDispatch(projectId, documentId);
        }
    }

    @Test
    void concurrentDispatchOfSameDocumentIsSerializedByLock() throws Exception {
        RedissonDocumentDispatchLock lock = newLock();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();
        DocumentProcessingService processing = mock(DocumentProcessingService.class);
        doAnswer(invocation -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            processed.incrementAndGet();
            active.decrementAndGet();
            return null;
        }).when(processing).process(any(), any());

        // 16 线程并发派发同一文档：锁把处理并发度收敛到 1
        ExecutorService pool = Executors.newFixedThreadPool(16);
        DocumentTaskDispatcher dispatcher = new DocumentTaskDispatcher(
                (TaskExecutor) pool::execute, processing, singletonProvider(lock));
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Void>> futures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 16; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                dispatcher.dispatch(projectId, documentId);
                return null;
            }));
        }
        try {
            start.countDown();
            for (var future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
            // 等待所有已获锁的处理真正跑完
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (active.get() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        } finally {
            pool.shutdownNow();
            lock.shutdown();
        }

        // 锁保证同一文档不会并发处理（正确性由锁 + claim CAS 双层兜底，此处锁是效率优化）
        assertThat(maxActive.get()).isEqualTo(1);
        // 抢不到锁的派发被跳过（tryLock(0) 语义），部分派发未处理属预期：
        // 状态行保持 UPLOADED，恢复 Job 会重新派发
        assertThat(processed.get()).isGreaterThanOrEqualTo(1);
        assertThat(processed.get()).isLessThanOrEqualTo(16);
    }

    /** 单元素 ObjectProvider：模拟回落模式下锁 Bean 已装配。 */
    private static ObjectProvider<DocumentDispatchLock> singletonProvider(DocumentDispatchLock lock) {
        return new ObjectProvider<>() {
            @Override
            public DocumentDispatchLock getIfAvailable() {
                return lock;
            }
        };
    }
}
