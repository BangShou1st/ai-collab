package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRecoveryCandidate;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文档恢复调度测试：UPLOADED 只派发给 Dispatcher，不在调度线程同步执行解析/Embedding；
 * PARSING / INDEXING 过期候选仍走原有失败标记。
 */
class DocumentRecoveryServiceTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentFailureRecorder failures = mock(DocumentFailureRecorder.class);

    @Test
    void uploadedCandidateIsDispatchedNotProcessedInline() {
        DocumentTaskDispatcher dispatcher = mock(DocumentTaskDispatcher.class);
        DocumentRecoveryService service = new DocumentRecoveryService(documents, dispatcher, failures);
        DocumentRecoveryCandidate uploaded = candidate(DocumentStatus.UPLOADED, UUID.randomUUID());
        when(documents.recoverable(any())).thenReturn(List.of(uploaded));

        service.dispatchRecoverableWork();

        verify(dispatcher).dispatch(uploaded.projectId(), uploaded.documentId());
        verifyNoInteractions(failures);
    }

    @Test
    void recoveryDispatchDoesNotRunProcessingOnSchedulingThread() {
        DocumentProcessingService processing = mock(DocumentProcessingService.class);
        ControllableExecutor executor = new ControllableExecutor();
        DocumentTaskDispatcher dispatcher = new DocumentTaskDispatcher(executor, processing);
        DocumentRecoveryService service = new DocumentRecoveryService(documents, dispatcher, failures);
        DocumentRecoveryCandidate uploaded = candidate(DocumentStatus.UPLOADED, UUID.randomUUID());
        when(documents.recoverable(any())).thenReturn(List.of(uploaded));

        service.dispatchRecoverableWork();

        verifyNoInteractions(processing);
        assertThat(executor.queued()).isEqualTo(1);

        executor.runQueued();
        verify(processing).process(uploaded.projectId(), uploaded.documentId());
    }

    @Test
    void staleCandidatesStillUseTheOriginalFailureRecorder() {
        DocumentTaskDispatcher dispatcher = mock(DocumentTaskDispatcher.class);
        DocumentRecoveryService service = new DocumentRecoveryService(documents, dispatcher, failures);
        DocumentRecoveryCandidate parsing = candidate(DocumentStatus.PARSING, UUID.randomUUID());
        DocumentRecoveryCandidate indexing = candidate(DocumentStatus.INDEXING, UUID.randomUUID());
        when(documents.recoverable(any())).thenReturn(List.of(parsing, indexing));

        service.dispatchRecoverableWork();

        verify(failures).recordStale(eq(parsing), any(OffsetDateTime.class));
        verify(failures).recordStale(eq(indexing), any(OffsetDateTime.class));
        verifyNoInteractions(dispatcher);
    }

    @Test
    void emptyCandidateListProducesNoCalls() {
        DocumentTaskDispatcher dispatcher = mock(DocumentTaskDispatcher.class);
        DocumentRecoveryService service = new DocumentRecoveryService(documents, dispatcher, failures);
        when(documents.recoverable(any())).thenReturn(List.of());

        service.dispatchRecoverableWork();

        verifyNoInteractions(dispatcher);
        verifyNoInteractions(failures);
    }

    @Test
    void repeatedScanDoesNotBypassDispatcherDeduplication() {
        DocumentProcessingService processing = mock(DocumentProcessingService.class);
        ControllableExecutor executor = new ControllableExecutor();
        DocumentTaskDispatcher dispatcher = new DocumentTaskDispatcher(executor, processing);
        DocumentRecoveryService service = new DocumentRecoveryService(documents, dispatcher, failures);
        DocumentRecoveryCandidate uploaded = candidate(DocumentStatus.UPLOADED, UUID.randomUUID());
        when(documents.recoverable(any())).thenReturn(List.of(uploaded));

        service.dispatchRecoverableWork();
        service.dispatchRecoverableWork();

        assertThat(executor.queued()).isEqualTo(1);
        verifyNoInteractions(processing);

        executor.runQueued();
        verify(processing, times(1)).process(uploaded.projectId(), uploaded.documentId());
    }

    private static DocumentRecoveryCandidate candidate(DocumentStatus status, UUID processingToken) {
        return new DocumentRecoveryCandidate(
                UUID.randomUUID(), UUID.randomUUID(), status, processingToken);
    }

    /** 可控执行器：只入队，由测试显式触发执行。 */
    private static final class ControllableExecutor implements TaskExecutor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        int queued() {
            return tasks.size();
        }

        void runQueued() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            pending.forEach(Runnable::run);
        }
    }
}
