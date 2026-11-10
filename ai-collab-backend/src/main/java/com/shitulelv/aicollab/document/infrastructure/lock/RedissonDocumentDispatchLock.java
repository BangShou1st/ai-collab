package com.shitulelv.aicollab.document.infrastructure.lock;

import com.shitulelv.aicollab.document.application.service.DocumentDispatchLock;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 文档派发跨实例互斥锁的 Redisson 实现（回落模式专用，Redis 深化 P2）。
 *
 * <p><b>为什么 Redisson 锁之上还要保留 DB claim CAS 双层兜底（面试必问点）</b>：</p>
 *
 * <p>Redis 主从异步复制下，分布式锁的<b>正确性</b>没有保证 —— 主节点写入锁后尚未同步
 * 到从节点就宕机，故障转移后新主上锁凭空丢失，另一实例即可再次获锁，同一文档被两个
 * 实例同时"持有"（Martin Kleppmann 对 Redlock 的经典批评）。因此这里的职责分工是：</p>
 * <ul>
 *   <li><b>锁 = 效率优化</b>：减少回落模式多实例下的无效重复派发与线程池竞争；
 *       锁丢失/Redis 不可用时最多退回"多一次无效竞争"，不产生任何错误状态；</li>
 *   <li><b>claim CAS = 正确性最后防线</b>：{@code DocumentProcessingService.claim} 的
 *       UPLOADED→PARSING 条件更新以数据库为唯一事实源，天然原子 —— 即使锁失效导致
 *       双实例并发进入，也只有第一个 claim 成功，其余立即空返回，重复解析/双重写不会发生。</li>
 * </ul>
 *
 * <p>这与 agent 运行时的租约 epoch fencing 是<b>同一思想的两处落点</b>：都承认协调层
 * （Redis 锁 / 运行租约）可能说谎或失忆，把最终正确性锚定在可串行化的存储层 ——
 * 这里是 DB 条件更新，agent 那边是 processing_token/epoch 校验拒绝旧代际写覆盖。
 * 协调层只做性能优化，永远不是正确性的依据。</p>
 *
 * <p><b>看门狗语义</b>：文档解析+向量化时长不确定（大文档可达数分钟），不预设
 * leaseTime，走 Redisson watchdog（默认 30s 持锁，每 10s 自动续期）—— 只要处理线程
 * 存活锁就不会中途过期被抢；处理结束 finally 释放。用 {@code tryLock(0, unit)}
 * （不传 leaseTime）触发 watchdog，而非 {@code tryLock(0, leaseTime, unit)}。</p>
 *
 * <p><b>惰性建连与降级</b>：RedissonClient 在首次使用时才创建（无 Redis 的测试环境
 * 不初始化连接）；创建/加锁失败时置降级标记并放行（见接口约定），每实例仅告警一次。</p>
 */
public class RedissonDocumentDispatchLock implements DocumentDispatchLock, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedissonDocumentDispatchLock.class);
    private static final String LOCK_KEY_PREFIX = "ai-collab:document:dispatch:lock:";

    private final String address;
    private final String password;
    private final int database;
    private final Object clientLock = new Object();

    /** 惰性创建的 Redisson 客户端；null = 尚未初始化。 */
    private volatile RedissonClient client;
    /** Redis 不可用降级标记：置位后所有加锁请求直接放行，不再重试建连。 */
    private volatile boolean degraded;

    public RedissonDocumentDispatchLock(String host, int port, String password, int database) {
        this.address = "redis://" + host + ":" + port;
        this.password = password;
        this.database = database;
    }

    @Override
    public boolean tryLockForDispatch(UUID projectId, UUID documentId) {
        RedissonClient redisson = clientOrNull();
        if (redisson == null) {
            // 降级放行：锁只是效率优化，正确性由 claim CAS 兜底（见类注释）
            return true;
        }
        try {
            RLock lock = redisson.getLock(LOCK_KEY_PREFIX + projectId + ":" + documentId);
            // waitTime=0：拿不到立即失败，调用方跳过派发；不传 leaseTime → watchdog 30s 续期
            return lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 与类注释契约一致：加锁失败（含中断）即置永久降级标记，后续直接放行
            degraded = true;
            return true;
        } catch (Exception e) {
            degraded = true;
            log.warn("文档派发锁获取失败，永久降级为放行模式（claim CAS 兜底）: documentId={}, cause={}",
                    documentId, e.getMessage());
            return true;
        }
    }

    @Override
    public void unlockAfterDispatch(UUID projectId, UUID documentId) {
        RedissonClient redisson = client;
        if (redisson == null) {
            return;
        }
        try {
            redisson.getLock(LOCK_KEY_PREFIX + projectId + ":" + documentId).unlock();
        } catch (IllegalMonitorStateException e) {
            // 未持有锁（锁过期被抢/降级路径未加锁）：无害，保留 watchdog 续期的安全语义
            log.debug("文档派发锁释放时已不持有（可能因 watchdog 续期失败过期）: documentId={}", documentId);
        } catch (Exception e) {
            log.warn("文档派发锁释放失败（watchdog 会在客户端存活期间继续续期，客户端关闭时自动清理）: documentId={}, cause={}",
                    documentId, e.getMessage());
        }
    }

    /** 测试可见：是否已进入降级放行模式（Redis 不可用后永久降级，见 degradedToPassThrough 语义）。 */
    boolean isDegraded() {
        return degraded;
    }

    private RedissonClient clientOrNull() {
        if (degraded) {
            return null;
        }
        RedissonClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (clientLock) {
            if (degraded) {
                return null;
            }
            if (client != null) {
                return client;
            }
            try {
                Config config = new Config();
                var server = config.useSingleServer()
                        .setAddress(address)
                        .setDatabase(database)
                        // 连接失败快速失败：锁是可降级的效率组件，不能拖慢派发主路径
                        .setConnectTimeout(2000)
                        .setTimeout(2000)
                        .setRetryAttempts(1);
                if (password != null && !password.isBlank()) {
                    server.setPassword(password);
                }
                client = Redisson.create(config);
                return client;
            } catch (Exception e) {
                degraded = true;
                log.warn("Redisson 客户端初始化失败，文档派发锁永久降级为放行模式（claim CAS 兜底）: {}", e.getMessage());
                return null;
            }
        }
    }

    /** Bean 销毁时释放客户端资源；未初始化（从未用过锁）则为空操作。 */
    public void shutdown() {
        RedissonClient existing = client;
        if (existing != null) {
            existing.shutdown();
        }
    }

    /** {@link AutoCloseable} 别名（try-with-resources / 通用资源管理），语义同 {@link #shutdown()}。 */
    @Override
    public void close() {
        shutdown();
    }
}
