package com.shitulelv.aicollab.document.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 文档派发器语义测试（M1 迁移后）。
 *
 * <p>进程内 inFlight 去重集合已删除（多实例绑定点），同一文档重复派发会在执行器层面
 * 排队多次，由下游三重保障兜底：MQ 消费幂等 / claim CAS（UPLOADED→PARSING 条件更新，
 * 重复派发仅第一例成功，其余空返回）/ 处理代际心跳。Redis 深化 P2 后回落模式另有
 * Redisson 跨实例互斥锁（本测试用空 ObjectProvider 模拟 MQ 模式下锁 Bean 不装配的
 * 直通路径；锁语义验证见 RedissonDocumentDispatchLockTest）。</p>
 */
class DocumentTaskDispatcherTest {

    private final DocumentProcessingService processing = mock(DocumentProcessingService.class);
    private final ControllableExecutor executor = new ControllableExecutor();
    private final DocumentTaskDispatcher dispatcher =
            new DocumentTaskDispatcher(executor, processing, noLock());

    /** 空锁提供者：模拟 MQ 模式（总闸开）下锁 Bean 不装配的场景（getIfAvailable 无可用实现）。 */
    private static ObjectProvider<DocumentDispatchLock> noLock() {
        return new ObjectProvider<>() {
            @Override
            public DocumentDispatchLock getIfAvailable() {
                return null;
            }
        };
    }

    @Test
    void everyDispatchSubmitsWorkWithoutInProcessDeduplication() {
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        dispatcher.dispatch(projectId, documentId);
        dispatcher.dispatch(projectId, documentId);

        // 去重集合删除后重复派发不再在 JVM 内拦截，处理副作用恰好一次由 claim CAS 保障
        assertThat(executor.queued()).isEqualTo(2);
        verifyNoProcessingYet();
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
    void runOnceInvokesSharedProcessingPath() {
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        dispatcher.runOnce(projectId, documentId);

        verify(processing).process(projectId, documentId);
    }

    @Test
    void runOnceRethrowsUnexpectedFailureForMqRetryPath() {
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        doThrow(new IllegalStateException("数据库瞬时不可用")).when(processing).process(any(), any());

        // MQ 消费路径依赖异常透出以触发 RECONSUME_LATER；进程内 executor 线程有默认异常处理
        assertThatThrownBy(() -> dispatcher.runOnce(projectId, documentId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectedSubmissionDoesNotThrowAndKeepsDocumentRecoverable() {
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        executor.rejectNextSubmission();

        dispatcher.dispatch(projectId, documentId);

        assertThat(executor.queued()).isZero();

        // 拒绝不留下任何本地状态：恢复 Job（5 分钟对账）稍后重新派发即可
        executor.acceptSubmissions();
        dispatcher.dispatch(projectId, documentId);
        assertThat(executor.queued()).isEqualTo(1);
    }

    private void verifyNoProcessingYet() {
        verify(processing, times(0)).process(any(), any());
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
    }
}
