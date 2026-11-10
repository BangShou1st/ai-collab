package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRecoveryCandidate;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 文档恢复调度（M1 迁移后定位：<b>对账兜底</b>，不再是主派发通道）。
 *
 * <p>与 MQ 的分工：MQ 模式下消息即派发信号（秒级），本 Job 降频为 5 分钟对账，
 * 只负责兜住"消息丢失/消费失败耗尽"的残余场景 —— UPLOADED 重新派发、
 * PARSING/INDEXING 过期标记失败。消息送达正常时本 Job 扫不到候选，近乎空转。</p>
 *
 * <p>UPLOADED 分支只派发给 {@link DocumentTaskDispatcher}，不在此同步执行解析或 Embedding；
 * PARSING / INDEXING 过期候选仍沿用原有失败标记。</p>
 */
@Component
public class DocumentRecoveryService {
    private final DocumentRepository documents;
    private final DocumentTaskDispatcher dispatcher;
    private final DocumentFailureRecorder failures;

    public DocumentRecoveryService(DocumentRepository documents, DocumentTaskDispatcher dispatcher,
                                   DocumentFailureRecorder failures) {
        this.documents = documents;
        this.dispatcher = dispatcher;
        this.failures = failures;
    }

    // 30s → 300s（M1）：主通道交给 MQ 后，恢复轮询只承担对账兜底职责，5 分钟足够；
    // 测试可用 document.dispatch-interval 覆盖为本测试专用值。
    @Scheduled(fixedDelayString = "${document.dispatch-interval:300s}",
            initialDelayString = "${document.dispatch-initial-delay:0s}")
    public void dispatchRecoverableWork() {
        OffsetDateTime staleBefore = OffsetDateTime.now().minusMinutes(15);
        for (DocumentRecoveryCandidate candidate : documents.recoverable(staleBefore)) {
            if (candidate.status() == DocumentStatus.UPLOADED) {
                dispatcher.dispatch(candidate.projectId(), candidate.documentId());
            } else {
                failures.recordStale(candidate, staleBefore);
            }
        }
    }
}
