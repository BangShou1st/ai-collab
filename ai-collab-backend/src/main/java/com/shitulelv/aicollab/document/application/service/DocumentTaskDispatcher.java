package com.shitulelv.aicollab.document.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文档处理派发器（M1 迁移后为"共享执行路径 + 线程池包装"；Redis 深化 P2 后回落模式
 * 增加跨实例互斥）。
 *
 * <p>M1 变更：进程内 {@code inFlight} 去重集合（ConcurrentHashMap.newKeySet）已删除 ——
 * 它是 JVM 内的去重边界，注释自认"不是跨进程锁"，是系统绑定单实例的最硬阻塞点。
 * 删除后的并发正确性由三层既有机制保障：</p>
 * <ol>
 *   <li>MQ 模式下消费幂等三重保障（状态前置校验 / idempotency_record / 状态机 CAS，
 *       见 {@code DocumentProcessingMqHandler}）；</li>
 *   <li>{@code DocumentProcessingService.claim} 的 CAS 条件更新（UPLOADED→PARSING）：
 *       重复派发只有第一个成功，其余立即空返回，重复解析不会发生；</li>
 *   <li>处理中的心跳与处理代际（processing_token）防止旧代际写覆盖。</li>
 * </ol>
 *
 * <p>Redis 深化 P2（回落模式跨实例互斥）：进程内去重集合删除后，回落模式
 * （{@code MQ_ENABLED=false}）多实例部署会重复派发（重复提交线程池任务），虽由
 * claim CAS 兜底不至于重复解析，但会造成无效负载。因此 dispatch 执行前先经
 * {@link DocumentDispatchLock}（Redisson watchdog 锁，见实现类注释）做
 * {@code tryLock(0)} 跨实例互斥：拿不到锁直接跳过 —— 锁只是减少无谓竞争的效率
 * 优化，正确性最后防线始终是 claim CAS（双层兜底分工详见锁实现类注释）。
 * <b>MQ 模式（总闸开）不加锁</b>：幂等三重保障已覆盖，锁 Bean 也不装配，两模式
 * 通过 {@link ObjectProvider} 条件隔离 —— Bean 缺省时零开销直通。</p>
 */
@Component
public class DocumentTaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(DocumentTaskDispatcher.class);
    private final TaskExecutor executor;
    private final DocumentProcessingService processing;
    private final ObjectProvider<DocumentDispatchLock> dispatchLock;

    public DocumentTaskDispatcher(
            @Qualifier("documentTaskExecutor") TaskExecutor executor,
            DocumentProcessingService processing,
            ObjectProvider<DocumentDispatchLock> dispatchLock) {
        this.executor = executor;
        this.processing = processing;
        this.dispatchLock = dispatchLock;
    }

    /**
     * 异步派发（进程内路径：上传回落派发 / 恢复 Job / 连接器同步）。
     * 不使用无界等待，不阻塞调度线程；提交被拒绝时不标记 FAILED，由恢复 Job 重试。
     *
     * <p>回落模式下执行线程先经跨实例互斥锁（tryLock(0) 拿不到直接跳过，状态行
     * 保持 UPLOADED 由恢复 Job 重新派发），锁随处理结束在 finally 中释放。</p>
     */
    public void dispatch(UUID projectId, UUID documentId) {
        // Bean 缺省（MQ 模式）时为 null：零开销直通，不加锁
        DocumentDispatchLock lock = dispatchLock.getIfAvailable();
        try {
            executor.execute(() -> {
                if (lock != null && !lock.tryLockForDispatch(projectId, documentId)) {
                    // 其他实例正处理同一文档：跳过本次派发，避免无效竞争；
                    // 文档状态行仍是 UPLOADED，恢复 Job（5 分钟对账）会重新派发
                    log.info("文档派发跨实例互斥锁未获取（同文档已被其他实例受理），跳过本次派发，projectId={}，documentId={}",
                            projectId, documentId);
                    return;
                }
                try {
                    runOnce(projectId, documentId);
                } finally {
                    if (lock != null) {
                        lock.unlockAfterDispatch(projectId, documentId);
                    }
                }
            });
        } catch (TaskRejectedException exception) {
            // 提交被拒绝：文档数据库状态保持可恢复，不在此标记 FAILED。
            log.warn("文档调度暂时被拒绝，将由恢复任务重试，projectId={}，documentId={}",
                    projectId, documentId);
        }
    }

    /**
     * 共享执行方法（同步执行）：进程内路径（executor / 恢复 Job）与 MQ 消费路径共用。
     * 异常向上抛出 —— MQ 消费路径依赖它感知系统级异常以触发 RECONSUME_LATER；
     * 进程内 executor 线程对未捕获异常有默认处理，语义不变。
     * <b>本方法不加锁</b>：MQ 消费路径的互斥由幂等三重保障覆盖，进程内路径的互斥
     * 在 {@link #dispatch} 的执行线程内完成。
     */
    public void runOnce(UUID projectId, UUID documentId) {
        try {
            processing.process(projectId, documentId);
        } catch (RuntimeException exception) {
            log.error("文档异步任务发生未预期异常，projectId={}，documentId={}，type={}",
                    projectId, documentId, exception.getClass().getSimpleName());
            throw exception;
        }
    }
}
