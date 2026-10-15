package com.shitulelv.aicollab.document.application.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 文档派发去重语义测试。使用可控 TaskExecutor，分别控制排队、执行、拒绝与完成，
 * 不依赖真实线程池时序，也不使用 Thread.sleep。
 */
class DocumentTaskDispatcherTest {
    private static final long TIMEOUT_SECONDS = 5;

    private final DocumentProcessingService processing = mock(DocumentProcessingService.class);
    private final ControllableExecutor executor = new ControllableExecutor();
    private final DocumentTaskDispatcher dispatcher =
            new DocumentTaskDispatcher(executor, processing);

    private ExecutorService background;

    @AfterEach
    void stopBackgroundThread() {
        if (background != null) {
            background.shutdownNow();
        }
    }

    @Test
    void sameDocumentQueuedOnlyOnce() {
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        dispatcher.dispatch(projectId, documentId);
        dispatcher.dispatch(projectId, documentId);

        assertThat(executor.queued()).isEqualTo(1);
        verifyNoInteractions(processing);
    }

    @Test
    void sameDocumentRunningIsNotResubmitted() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch releaseProcessing = new CountDownLatch(1);
        doAnswer(invocation -> {
            processingStarted.countDown();
            releaseProcessing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return null;
        }).when(processing).process(any(), any());

        background = Executors.newSingleThreadExecutor();
        try {
            dispatcher.dispatch(projectId, documentId);
            Runnable task = executor.take();
            Future<?> running = background.submit(task);
            assertThat(processingStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            dispatcher.dispatch(projectId, documentId);

            assertThat(executor.queued()).isZero();
            verify(processing, times(1)).process(projectId, documentId);
            assertThat(running.isDone()).isFalse();
        } finally {
            releaseProcessing.countDown();
        }
    }

    @Test
    void differentDocumentsSubmitIndependently() {
        UUID projectId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        dispatcher.dispatch(projectId, first);
        dispatcher.dispatch(projectId, second);

        assertThat(executor.queued()).isEqualTo(2);
    }

    @Test
    void differentProjectAndDocumentIdentityIsNotDeduplicated() {
        UUID firstProject = UUID.randomUUID();
        UUID secondProject = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        dispatcher.dispatch(firstProject, documentId);
        dispatcher.dispatch(secondProject, documentId);

        assertThat(executor.queued()).isEqualTo(2);
    }

    @Test
    void inFlightKeyClearedAfterNormalCompletion() {
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        dispatcher.dispatch(projectId, documentId);
        executor.runQueued();
        verify(processing).process(projectId, documentId);

        dispatcher.dispatch(projectId, documentId);

        assertThat(executor.queued()).isEqualTo(1);
        executor.runQueued();
        verify(processing, times(2)).process(projectId, documentId);
    }

    @Test
    void inFlightKeyClearedAfterFailure() {
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        doThrow(new IllegalStateException("解析失败")).when(processing).process(any(), any());

        dispatcher.dispatch(projectId, documentId);
        executor.runQueued();

        dispatcher.dispatch(projectId, documentId);

        assertThat(executor.queued()).isEqualTo(1);
    }

    @Test
    void rejectedSubmissionReleasesDeduplicationKey() {
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        executor.rejectNextSubmission();

        dispatcher.dispatch(projectId, documentId);

        assertThat(executor.queued()).isZero();

        executor.acceptSubmissions();
        dispatcher.dispatch(projectId, documentId);

        assertThat(executor.queued()).isEqualTo(1);
    }

    /** 可控执行器：提交只入队，任务由测试显式触发；可切换为拒绝下一次提交。 */
    private static final class ControllableExecutor implements TaskExecutor {
        private final List<Runnable> tasks = new ArrayList<>();
        private boolean rejected;

        @Override
        public void execute(Runnable task) {
            if (rejected) {
                throw new org.springframework.core.task.TaskRejectedException(
                        "文档执行器暂时拒绝提交", new RejectedExecutionException());
            }
            tasks.add(task);
        }

        void rejectNextSubmission() {
            this.rejected = true;
        }

        void acceptSubmissions() {
            this.rejected = false;
        }

        int queued() {
            return tasks.size();
        }

        Runnable take() {
            return tasks.remove(0);
        }

        void runQueued() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            pending.forEach(Runnable::run);
        }
    }
}
