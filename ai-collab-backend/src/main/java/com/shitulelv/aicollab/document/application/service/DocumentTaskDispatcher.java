package com.shitulelv.aicollab.document.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文档处理派发器：进程内路径使用有界线程池，MQ 消费路径同步调用共享执行方法。
 *
 * <p>重复派发和并发消费由数据库状态机仲裁：</p>
 * <ol>
 *   <li>{@code DocumentProcessingService.claim} 的 CAS 条件更新（UPLOADED→PARSING）只允许
 *       一次认领成功；</li>
 *   <li>处理心跳和处理代际（processing_token）拒绝失效处理者的后续写入；</li>
 *   <li>恢复 Job 重派仍为 UPLOADED 的文档，并处理过期的 PARSING / INDEXING 代次。</li>
 * </ol>
 *
 * <p>重复任务可能排入有界线程池，但不会产生重复处理副作用。线程池饱和时提交被拒绝，
 * 文档仍为 UPLOADED，由恢复 Job 再次派发；同一 claim CAS 覆盖 MQ 与回落模式。</p>
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
     *
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
     * 本方法不持有外部锁，所有调用方均由处理服务的数据库 claim CAS 仲裁。
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
