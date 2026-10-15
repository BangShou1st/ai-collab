package com.shitulelv.aicollab.document.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档处理派发器。
 *
 * <p>上传事务提交后的事件派发与定时恢复派发共用这一条路径，因此也共用同一个进程内
 * inFlight 去重边界（身份键 = projectId + documentId）。集合覆盖"已提交等待执行"与
 * "正在执行"两种状态：同一身份只提交一次，任务结束（正常或异常）后在 finally 中清理。</p>
 *
 * <p>该集合仅用于避免进程内重复派发，不是跨进程锁，也不替代
 * {@code DocumentRepository.claim} 的处理代际与幂等保护；不使用无界等待，
 * 不阻塞调度线程，也不回退到调用方线程执行。</p>
 */
@Component
public class DocumentTaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(DocumentTaskDispatcher.class);
    private final TaskExecutor executor;
    private final DocumentProcessingService processing;
    private final Set<DocumentKey> inFlight = ConcurrentHashMap.newKeySet();

    public DocumentTaskDispatcher(
            @Qualifier("documentTaskExecutor") TaskExecutor executor,
            DocumentProcessingService processing) {
        this.executor = executor;
        this.processing = processing;
    }

    public void dispatch(UUID projectId, UUID documentId) {
        DocumentKey key = new DocumentKey(projectId, documentId);
        // 先原子占位：已有同身份任务在排队或执行中时不再重复提交，避免重复解析与 Embedding。
        if (!inFlight.add(key)) {
            return;
        }
        try {
            executor.execute(() -> processSafely(key, projectId, documentId));
        } catch (TaskRejectedException exception) {
            // 提交被拒绝：立即释放去重键，让后续 tick 能再次派发；
            // 文档数据库状态保持可恢复，不在此标记 FAILED。
            inFlight.remove(key);
            log.warn("文档调度暂时被拒绝，将由恢复任务重试，projectId={}，documentId={}",
                    projectId, documentId);
        }
    }

    private void processSafely(DocumentKey key, UUID projectId, UUID documentId) {
        try {
            processing.process(projectId, documentId);
        } catch (RuntimeException exception) {
            log.error("文档异步任务发生未预期异常，projectId={}，documentId={}，type={}",
                    projectId, documentId, exception.getClass().getSimpleName());
        } finally {
            // 正常结束或异常退出都要释放去重键，否则该文档将永久无法再次派发。
            inFlight.remove(key);
        }
    }

    private record DocumentKey(UUID projectId, UUID documentId) {
    }
}
