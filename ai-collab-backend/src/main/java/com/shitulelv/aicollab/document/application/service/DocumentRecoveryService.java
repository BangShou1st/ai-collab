package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRecoveryCandidate;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 文档恢复调度。
 *
 * <p>UPLOADED 分支只派发给 {@link DocumentTaskDispatcher}，不在此同步执行解析或 Embedding；
 * 派发与上传事务提交后的事件共用同一去重边界。PARSING / INDEXING 过期候选仍沿用原有失败标记。</p>
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

    @Scheduled(fixedDelayString = "${document.dispatch-interval:30s}",
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
