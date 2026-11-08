package com.shitulelv.aicollab.document.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文档处理派发器（M1 迁移后为"共享执行路径 + 线程池包装"）。
 *
 * <p>M1 变更：进程内 {@code inFlight} 去重集合（ConcurrentHashMap.newKeySet）已删除 ——
 * 它是 JVM 内的去重边界，注释自认"不是跨进程锁"，是系统绑定单实例的最硬阻塞点。</p>
 *
 * <p>删除后的并发正确性由三层既有机制保障：</p>
 * <ol>
 *   <li>MQ 模式下消费幂等三重保障（状态前置校验 / idempotency_record / 状态机 CAS，
 *       见 {@code DocumentProcessingMqHandler}）；</li>
 *   <li>{@code DocumentProcessingService.claim} 的 CAS 条件更新（UPLOADED→PARSING）：
 *       重复派发只有第一个成功，其余立即空返回，重复解析不会发生；</li>
 *   <li>处理中的心跳与处理代际（processing_token）防止旧代际写覆盖。</li>
 * </ol>
 *
 * <p>回落模式代价（总闸 {@code MQ_ENABLED=false} 的已知取舍）：多实例部署会重复
 * 派发（重复提交线程池任务），由 claim CAS 兜底不至于重复解析，但会造成无效负载
 * —— <b>回落模式仅限单实例/本地部署</b>，已写入 .env.example 说明。</p>
 */
@Component
public class DocumentTaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(DocumentTaskDispatcher.class);
    private final TaskExecutor executor;
    private final DocumentProcessingService processing;

    public DocumentTaskDispatcher(
            @Qualifier("documentTaskExecutor") TaskExecutor executor,
            DocumentProcessingService processing) {
        this.executor = executor;
        this.processing = processing;
    }

    /**
     * 异步派发（进程内路径：上传回落派发 / 恢复 Job / 连接器同步）。
     * 不使用无界等待，不阻塞调度线程；提交被拒绝时不标记 FAILED，由恢复 Job 重试。
     */
    public void dispatch(UUID projectId, UUID documentId) {
        try {
            executor.execute(() -> runOnce(projectId, documentId));
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
