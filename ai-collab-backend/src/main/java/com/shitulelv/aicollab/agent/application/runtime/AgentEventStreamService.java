package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.view.AgentRunEventView;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 运行事件的 SSE 投递。
 *
 * <p><b>所有网络写出都在专用发送线程上执行</b>：持久事件、初始 replay、正文预览帧与
 * heartbeat 归一到每订阅唯一的一条串行发送路径。模型读流线程、事务 {@code afterCommit}
 * 回调（Agent 运行线程）与 {@code @Scheduled} 调度线程只做非阻塞投递/唤醒，绝不等待
 * 客户端网络写——慢订阅者因此既不拖住模型响应，也不拖住结果收口或调度线程。</p>
 *
 * <p>持久事实仍以 {@code agent_run_event} 为准：发送端按订阅游标（实际写出的最大序号）
 * 批量追赶，因此并发 {@code afterCommit} 的到达顺序不影响投递顺序，也不会跳过已经提交的
 * 较小序号。临时正文帧与 heartbeat 不改动游标，允许合并/丢弃，断线重放只补持久事件。</p>
 */
@Service
public class AgentEventStreamService {
    private static final Logger log = LoggerFactory.getLogger(AgentEventStreamService.class);

    private static final Set<AgentEventType> TERMINAL = Set.of(
            AgentEventType.RUN_CANCELED,
            AgentEventType.RUN_SUCCEEDED,
            AgentEventType.RUN_FAILED,
            AgentEventType.RUN_BUDGET_EXCEEDED,
            AgentEventType.APPROVAL_REQUESTED,
            AgentEventType.WAITING_FOR_USER_INPUT);

    /** 订阅发送线程数：进程内共享、有界，慢订阅者只占用这里的 worker。 */
    private static final int SEND_WRITERS = 4;
    /** 发送任务队列上限：有界。饱和时拒绝投递（AbortPolicy）并复位待发标记，由下一次事件或 heartbeat 重试；绝不在业务线程做网络写。 */
    private static final int SEND_QUEUE_CAPACITY = 128;
    /** 单次持久事件读取批量上限（与 {@link AgentEventRepository#list} 一致）。 */
    private static final int BATCH_LIMIT = 500;
    /** 单轮发送最多推进的持久事件数：超过后让出 worker，保留积压标记留待下一轮继续追赶。 */
    private static final int MAX_EVENTS_PER_PASS = 2000;

    private final ProjectAccessGuard access;
    private final AgentRepository runs;
    private final AgentEventRepository events;
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<Subscription>> subscriptions =
            new ConcurrentHashMap<>();
    /** 统一订阅发送线程池：进程内共享、有界，不按运行/连接创建。 */
    private final ThreadPoolExecutor senders = new ThreadPoolExecutor(
            SEND_WRITERS, SEND_WRITERS, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(SEND_QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "agent-sse-send");
                thread.setDaemon(true);
                return thread;
            });

    public AgentEventStreamService(
            ProjectAccessGuard access, AgentRepository runs, AgentEventRepository events) {
        this.access = access;
        this.runs = runs;
        this.events = events;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        senders.shutdownNow();
    }

    /**
     * 建立订阅并把初始 replay 交给发送线程。请求线程只登记订阅并唤醒一次发送端：
     * 先登记后唤醒，保证登记之前提交的持久事件也会被随后的游标追赶读到，登记之后的事件
     * 会经由 {@link #publish} 唤醒同一个发送端。
     */
    public SseEmitter subscribe(UUID projectId, UUID runId, UUID userId, long cursor) {
        access.requireMember(projectId, userId);
        runs.findRun(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));

        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription(projectId, runId, emitter, cursor);
        subscriptions.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>())
                .add(subscription);
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> remove(subscription));
        emitter.onError(ignored -> remove(subscription));
        subscription.durablePending.set(true);
        schedule(subscription);
        return emitter;
    }

    /**
     * 持久事件已提交：只标记该运行的订阅并唤醒发送端。真正写出由发送端按游标追赶完成，
     * 因此调用线程（Agent 运行线程或事务 afterCommit）不会触碰客户端网络。
     */
    public void publish(AgentRunEventView event) {
        for (Subscription subscription : subscriptions.getOrDefault(
                event.runId(), new CopyOnWriteArrayList<>())) {
            if (!subscription.projectId.equals(event.projectId())) continue;
            subscription.durablePending.set(true);
            schedule(subscription);
        }
    }

    /**
     * 临时正文帧：只推送给当前订阅，不落库、不占持久事件序号、不更新订阅游标，
     * 断线重放不会补发（前端用持久事件收口）。
     *
     * <p><b>非阻塞</b>：调用方是模型读流线程，这里只更新待发快照并唤醒发送线程，
     * 绝不等待客户端网络写完成——否则可选的展示订阅会把已经就绪的模型响应拖成
     * {@code AI_MODEL_TIMEOUT}，进而触发多余的 provider 重试。快照是自包含的累计文本，
     * 只保留最新一帧，旧帧可安全丢弃。
     */
    public void publishContentDelta(UUID projectId, UUID runId, Object payload) {
        for (Subscription subscription : subscriptions.getOrDefault(
                runId, new CopyOnWriteArrayList<>())) {
            if (subscription.closed || !subscription.projectId.equals(projectId)) continue;
            subscription.pendingContent.set(payload);
            schedule(subscription);
        }
    }

    /**
     * 调度线程只登记"该发一次 heartbeat"的意图并唤醒发送端，不直接写连接：
     * 慢订阅者的网络写不再占用 {@code @Scheduled} 调用线程。
     */
    @Scheduled(fixedDelayString = "${agent.events.heartbeat-ms:20000}")
    public void heartbeat() {
        subscriptions.values().forEach(list -> list.forEach(subscription -> {
            subscription.heartbeatPending.set(true);
            schedule(subscription);
        }));
    }

    /**
     * 为订阅排一次发送任务：单飞（CAS）保证同一连接同一时刻只有一个写出者。
     * 线程池饱和时拒绝投递并复位标记，绝不在调用线程执行网络写（不使用 CallerRunsPolicy）；
     * 被拒绝的订阅由下一次事件或 heartbeat 周期重试。
     */
    private void schedule(Subscription subscription) {
        if (subscription.closed) return;
        if (!subscription.sending.compareAndSet(false, true)) return;
        try {
            senders.execute(() -> deliver(subscription));
        } catch (RejectedExecutionException saturated) {
            subscription.sending.set(false);
        }
    }

    /**
     * 发送线程上的单次投递：循环冲刷待发正文快照、按游标追赶持久事件、发送 heartbeat，
     * 直到没有新工作为止。迟到的投递只置位标记，由 {@code finally} 的兜底重排接住，
     * 不会丢失唤醒。
     */
    private void deliver(Subscription subscription) {
        try {
            while (!subscription.closed) {
                subscription.durablePending.set(false);
                sendPendingContent(subscription);
                boolean backlog = catchUpDurable(subscription);
                sendHeartbeat(subscription);
                if (subscription.closed) break;
                if (!backlog
                        && !subscription.durablePending.get()
                        && subscription.pendingContent.get() == null
                        && !subscription.heartbeatPending.get()) {
                    break;
                }
            }
        } catch (RuntimeException unexpected) {
            // 持久读取等瞬时失败不拆连接：保留订阅，由下一次事件或 heartbeat 周期重试
            log.debug("Agent 事件流投递异常，保留订阅等待重试 runId={}", subscription.runId, unexpected);
        } finally {
            subscription.sending.set(false);
            // 停顿时投递方 CAS 失败、或本轮未读完的积压：补排一次；无新工作则结束
            if (!subscription.closed
                    && (subscription.durablePending.get()
                        || subscription.pendingContent.get() != null
                        || subscription.heartbeatPending.get())) {
                schedule(subscription);
            }
        }
    }

    /** 冲刷待发正文快照（只保留最新一帧，允许丢弃中间帧）。 */
    private void sendPendingContent(Subscription subscription) {
        Object payload = subscription.pendingContent.getAndSet(null);
        if (payload == null || subscription.closed) return;
        try {
            subscription.emitter.send(SseEmitter.event().name("MODEL_CONTENT").data(payload));
        } catch (IOException | IllegalStateException failure) {
            log.debug("Agent 正文预览写出失败，断开该订阅 runId={}", subscription.runId, failure);
            close(subscription);
        }
    }

    /**
     * 按持久游标有序追赶 {@code agent_run_event}：每批读取后按序写出，游标只随实际写出的
     * 持久事件推进。并发 afterCommit 的到达顺序不参与排序，因此不会先发较大序号、
     * 再因为游标跳过较小序号；读空即表示已追平，等待下一次唤醒。
     *
     * @return {@code true} 表示本轮达到上限仍有积压，需要继续下一轮
     */
    private boolean catchUpDurable(Subscription subscription) {
        int budget = MAX_EVENTS_PER_PASS;
        while (!subscription.closed && budget > 0) {
            List<AgentRunEventView> batch = events.list(
                    subscription.projectId, subscription.runId, subscription.lastSequence,
                    Math.min(BATCH_LIMIT, budget));
            if (batch.isEmpty()) return false;
            for (AgentRunEventView event : batch) {
                sendDurable(subscription, event);
                if (subscription.closed) return false;
            }
            budget -= batch.size();
        }
        return !subscription.closed;
    }

    /** 单个持久事件的写出：成功后才推进游标；终态事件按序送出后再正常完成连接。 */
    private void sendDurable(Subscription subscription, AgentRunEventView event) {
        if (subscription.closed || event.sequence() <= subscription.lastSequence) return;
        try {
            subscription.emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name(event.type().name())
                    .data(event));
        } catch (IOException | IllegalStateException failure) {
            log.debug("Agent 事件流订阅写出失败，等待客户端按游标重连 runId={}", subscription.runId, failure);
            close(subscription);
            return;
        }
        subscription.lastSequence = event.sequence();
        if (isTerminal(event)) {
            close(subscription);
        }
    }

    private static boolean isTerminal(AgentRunEventView event) {
        return TERMINAL.contains(event.type())
                && !(event.type() == AgentEventType.APPROVAL_REQUESTED
                     && "RUNNING".equals(event.payload().path("status").asText()));
    }

    private void sendHeartbeat(Subscription subscription) {
        if (!subscription.heartbeatPending.getAndSet(false) || subscription.closed) return;
        try {
            subscription.emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | IllegalStateException failure) {
            close(subscription);
        }
    }

    /** 发送线程上终止订阅：置关闭、清待发、摘除 fanout，并正常完成连接。 */
    private void close(Subscription subscription) {
        boolean first = !subscription.closed;
        remove(subscription);
        if (first) {
            try {
                subscription.emitter.complete();
            } catch (RuntimeException ignored) {
                // 连接已完成/已断开：无需再次通知
            }
        }
    }

    /** 生命周期回调（完成/超时/错误）与发送失败共用：幂等摘除，迟到的投递不会复活订阅。 */
    private void remove(Subscription subscription) {
        subscription.closed = true;
        subscription.pendingContent.set(null);
        CopyOnWriteArrayList<Subscription> list = subscriptions.get(subscription.runId);
        if (list == null) return;
        list.remove(subscription);
        if (list.isEmpty()) subscriptions.remove(subscription.runId, list);
    }

    private static final class Subscription {
        private final UUID projectId;
        private final UUID runId;
        private final SseEmitter emitter;
        private long lastSequence;
        private volatile boolean closed;
        /** 待发送的临时正文帧（自包含累计快照，覆盖旧帧安全）：投递线程写、发送线程取。 */
        private final AtomicReference<Object> pendingContent = new AtomicReference<>();
        /** 有新的持久事件待追赶（publish/初始 replay 置位）。 */
        private final AtomicBoolean durablePending = new AtomicBoolean();
        /** 是否有一次 heartbeat 待写出（只保留意图，不积压）。 */
        private final AtomicBoolean heartbeatPending = new AtomicBoolean();
        /** 该订阅的发送任务是否已排队/在执行：保证同一连接只有一个写出者。 */
        private final AtomicBoolean sending = new AtomicBoolean();

        private Subscription(UUID projectId, UUID runId, SseEmitter emitter, long lastSequence) {
            this.projectId = projectId;
            this.runId = runId;
            this.emitter = emitter;
            this.lastSequence = lastSequence;
        }
    }
}
