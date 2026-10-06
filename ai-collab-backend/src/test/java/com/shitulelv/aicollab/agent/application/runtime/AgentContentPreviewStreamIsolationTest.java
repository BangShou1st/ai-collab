package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.view.AgentRunEventView;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata;
import com.shitulelv.aicollab.infrastructure.ai.model.JsonHttpModelClient;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.OpenAiCompatibleModelAdapter;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelContentPreview;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订阅发送边界：所有 SSE 网络写必须退出模型读流线程、事务 afterCommit 回调与调度线程，
 * 只发生在专用发送线程上；持久事件按游标有序、恰好一次地投递。
 *
 * <p>确定性复现：真实 {@link JsonHttpModelClient} + 生产 OpenAI 兼容适配器，HTTP transport
 * 替身立即给出有效正文与 {@code [DONE]}；订阅写出用闩锁阻塞。探针不等网络、不调用真实模型。
 * 持久存储用仓库替身模拟游标语义（{@code sequence_no > afterSequence} 升序）。</p>
 */
class AgentContentPreviewStreamIsolationTest {

    @Test
    void slowPreviewSubscriberDoesNotTurnAnImmediateProviderResponseIntoModelTimeout() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch enteredSend = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        SseEmitter blocked = mock(SseEmitter.class);
        doAnswer(invocation -> {
            enteredSend.countDown();
            awaitQuietly(releaseSend);
            return null;
        }).when(blocked).send(any(SseEmitter.SseEventBuilder.class));
        register(fixture.streams, fixture.projectId, fixture.runId, blocked);
        try {
            // 同一份 wire 数据在没有预览订阅时成功
            assertThat(turn(fixture)).isEqualTo("provider replied immediately");
            // 加入被阻塞的预览订阅后，模型读取不得被拖成 AI_MODEL_TIMEOUT
            ModelContentPreview.activate(new AgentContentPreviewPublisher(
                    fixture.events, fixture.json, fixture.projectId, fixture.runId, "fixture-call"));
            assertThat(turn(fixture)).isEqualTo("provider replied immediately");
            // 展示写入确实被尝试（只是移到发送线程），不是把预览静默关掉
            assertThat(enteredSend.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            ModelContentPreview.clear();
            releaseSend.countDown();
            fixture.close();
        }
    }

    @Test
    void previewWriteFailureDoesNotFailOrRetryTheModelCall() throws Exception {
        Fixture fixture = new Fixture();
        SseEmitter failing = mock(SseEmitter.class);
        doThrow(new IOException("订阅端已断开")).when(failing).send(any(SseEmitter.SseEventBuilder.class));
        register(fixture.streams, fixture.projectId, fixture.runId, failing);
        try {
            ModelContentPreview.activate(new AgentContentPreviewPublisher(
                    fixture.events, fixture.json, fixture.projectId, fixture.runId, "fixture-call"));
            assertThat(turn(fixture)).isEqualTo("provider replied immediately");
        } finally {
            ModelContentPreview.clear();
        }
        // 展示侧失败不等于 provider 失败：上游只发了一次请求，没有多余重试
        verify(fixture.transport, times(1))
                .send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        // 临时帧不落库：预览路径从不写入/查询持久事件序号
        verify(fixture.repository, never()).append(any(), any(), any(), any());
        verify(fixture.repository, never()).exists(any(), any(), any());
        fixture.close();
    }

    @Test
    void contentDeltaPublishingIsNonBlockingAndDoesNotStarveHealthySubscribers() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch enteredSend = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        SseEmitter blocked = mock(SseEmitter.class);
        doAnswer(invocation -> {
            enteredSend.countDown();
            awaitQuietly(releaseSend);
            return null;
        }).when(blocked).send(any(SseEmitter.SseEventBuilder.class));
        SseEmitter healthy = mock(SseEmitter.class);
        register(fixture.streams, fixture.projectId, fixture.runId, blocked);
        register(fixture.streams, fixture.projectId, fixture.runId, healthy);
        try {
            long started = System.nanoTime();
            fixture.streams.publishContentDelta(fixture.projectId, fixture.runId, payload(1, "第一段"));
            fixture.streams.publishContentDelta(fixture.projectId, fixture.runId, payload(2, "第一段第二段"));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertThat(elapsedMs).as("读流线程不能等待客户端网络写").isLessThan(250L);
            // 慢订阅者占用一个发送 worker，健康订阅仍能收到预览
            assertThat(enteredSend.await(2, TimeUnit.SECONDS)).isTrue();
            verify(healthy, timeout(2_000).atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
        } finally {
            releaseSend.countDown();
            fixture.close();
        }
    }

    @Test
    void temporaryFramesDoNotAdvanceTheDurableCursor() throws Exception {
        Fixture fixture = new Fixture();
        SseEmitter healthy = mock(SseEmitter.class);
        // 持久存储里已有一条序号 1 的持久事件
        when(fixture.repository.list(eq(fixture.projectId), eq(fixture.runId), eq(0L), anyInt()))
                .thenReturn(List.of(event(fixture.projectId, fixture.runId, 1, AgentEventType.MODEL_STARTED)));
        register(fixture.streams, fixture.projectId, fixture.runId, healthy);
        try {
            // 初始 replay 送出持久事件；临时帧沿用同一连接但不推进游标
            fixture.streams.publishContentDelta(fixture.projectId, fixture.runId, payload(1, "临时正文"));
            // 重复发布同一序号：游标已推进，不得重发
            fixture.streams.publish(event(fixture.projectId, fixture.runId, 1, AgentEventType.MODEL_STARTED));
            fixture.streams.publish(event(fixture.projectId, fixture.runId, 1, AgentEventType.MODEL_STARTED));
            verify(healthy, timeout(2_000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
            fixture.streams.publishContentDelta(fixture.projectId, fixture.runId, payload(2, "临时正文续"));
            verify(healthy, timeout(2_000).times(3)).send(any(SseEmitter.SseEventBuilder.class));
        } finally {
            fixture.close();
        }
    }

    // ---- 统一发送入口的正式回归（由复核探针转正） ----

    @Test
    void blockedPreviewWriteDoesNotBlockPersistentPublicationOrOtherSubscribers() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch previewWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        SseEmitter blocked = mock(SseEmitter.class);
        doAnswer(invocation -> {
            previewWriteEntered.countDown();
            awaitQuietly(releaseWrite);
            return null;
        }).when(blocked).send(any(SseEmitter.SseEventBuilder.class));
        SseEmitter healthy = mock(SseEmitter.class);
        CountDownLatch healthyReceived = new CountDownLatch(1);
        doAnswer(invocation -> {
            healthyReceived.countDown();
            return null;
        }).when(healthy).send(any(SseEmitter.SseEventBuilder.class));
        AgentRunEventView completed = event(
                fixture.projectId, fixture.runId, 1, AgentEventType.MODEL_COMPLETED);
        when(fixture.repository.list(eq(fixture.projectId), eq(fixture.runId), eq(0L), anyInt()))
                .thenReturn(List.of(completed));
        register(fixture.streams, fixture.projectId, fixture.runId, blocked);
        ExecutorService publisher = Executors.newSingleThreadExecutor();
        CountDownLatch publicationReturned = new CountDownLatch(1);
        Future<?> task = null;
        try {
            fixture.streams.publishContentDelta(fixture.projectId, fixture.runId, payload(1, "preview"));
            assertThat(previewWriteEntered.await(2, TimeUnit.SECONDS)).isTrue();
            // 预览已占住一个发送 worker；此时才登记健康订阅者，再在业务线程发布持久事件
            register(fixture.streams, fixture.projectId, fixture.runId, healthy);
            task = publisher.submit(() -> {
                fixture.streams.publish(completed);
                publicationReturned.countDown();
            });
            // 慢临时写期间，持久发布的调用线程及时返回，健康连接收到自己的持久事件
            assertThat(publicationReturned.await(300, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(healthyReceived.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseWrite.countDown();
            if (task != null) task.get(2, TimeUnit.SECONDS);
            publisher.shutdownNow();
            fixture.close();
        }
    }

    @Test
    void heartbeatSchedulingDoesNotWaitForSubscriberNetworkWrites() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        SseEmitter blocked = mock(SseEmitter.class);
        doAnswer(invocation -> {
            writeEntered.countDown();
            awaitQuietly(releaseWrite);
            return null;
        }).when(blocked).send(any(SseEmitter.SseEventBuilder.class));
        register(fixture.streams, fixture.projectId, fixture.runId, blocked);
        ExecutorService scheduler = Executors.newSingleThreadExecutor();
        CountDownLatch heartbeatReturned = new CountDownLatch(1);
        Future<?> task = null;
        try {
            task = scheduler.submit(() -> {
                fixture.streams.heartbeat();
                heartbeatReturned.countDown();
            });
            assertThat(writeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(heartbeatReturned.await(300, TimeUnit.MILLISECONDS))
                    .as("heartbeat 调度调用不能等待订阅端网络写").isTrue();
        } finally {
            releaseWrite.countDown();
            if (task != null) task.get(2, TimeUnit.SECONDS);
            scheduler.shutdownNow();
            fixture.close();
        }
    }

    @Test
    void durableEventsReplayAndPublishInCursorOrderExactlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        SseEmitter emitter = mock(SseEmitter.class);
        List<AgentRunEventView> committed = List.of(
                event(fixture.projectId, fixture.runId, 1, AgentEventType.MODEL_STARTED),
                event(fixture.projectId, fixture.runId, 2, AgentEventType.MODEL_COMPLETED),
                event(fixture.projectId, fixture.runId, 3, AgentEventType.MODEL_STARTED));
        when(fixture.repository.list(eq(fixture.projectId), eq(fixture.runId), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    long after = invocation.getArgument(2);
                    return committed.stream().filter(e -> e.sequence() > after).toList();
                });
        try {
            register(fixture.streams, fixture.projectId, fixture.runId, emitter);
            // 乱序唤醒：并发 afterCommit 的到达顺序不等于数据库 sequence 顺序
            fixture.streams.publish(committed.get(2));
            fixture.streams.publish(committed.get(0));
            fixture.streams.publish(committed.get(1));
            ArgumentCaptor<SseEmitter.SseEventBuilder> frames =
                    ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
            verify(emitter, timeout(2_000).times(3)).send(frames.capture());
            assertThat(durableFrames(frames.getAllValues()))
                    .extracting(AgentRunEventView::sequence)
                    .containsExactly(1L, 2L, 3L);
        } finally {
            fixture.close();
        }
    }

    @Test
    void terminalEventCompletesTheConnectionOnlyAfterEarlierEventsAreSent() throws Exception {
        Fixture fixture = new Fixture();
        SseEmitter emitter = mock(SseEmitter.class);
        when(fixture.repository.list(eq(fixture.projectId), eq(fixture.runId), eq(0L), anyInt()))
                .thenReturn(List.of(
                        event(fixture.projectId, fixture.runId, 1, AgentEventType.MODEL_COMPLETED),
                        event(fixture.projectId, fixture.runId, 2, AgentEventType.RUN_SUCCEEDED)));
        try {
            register(fixture.streams, fixture.projectId, fixture.runId, emitter);
            ArgumentCaptor<SseEmitter.SseEventBuilder> frames =
                    ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
            verify(emitter, timeout(2_000).times(2)).send(frames.capture());
            assertThat(durableFrames(frames.getAllValues()))
                    .extracting(AgentRunEventView::sequence)
                    .containsExactly(1L, 2L);
            InOrder order = inOrder(emitter);
            order.verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            order.verify(emitter).complete();
            // 终态已按序送出并关闭连接：后续发布不再写这条连接
            fixture.streams.publish(event(fixture.projectId, fixture.runId, 3, AgentEventType.MODEL_STARTED));
            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        } finally {
            fixture.close();
        }
    }

    @Test
    void saturatedSendPoolNeverBlocksPublicationAndTheSubscriptionRecovers() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch releaseAll = new CountDownLatch(1);
        int capacity = sendCapacity();
        try {
            // 占满发送线程 + 队列：worker 阻塞在网络写上，其余排队/被拒绝
            for (int i = 0; i < capacity + 2; i++) {
                UUID runId = UUID.randomUUID();
                SseEmitter blocked = mock(SseEmitter.class);
                doAnswer(invocation -> {
                    awaitQuietly(releaseAll);
                    return null;
                }).when(blocked).send(any(SseEmitter.SseEventBuilder.class));
                register(fixture.streams, fixture.projectId, runId, blocked);
                fixture.streams.publishContentDelta(fixture.projectId, runId, payload(1, "backpressure"));
            }
            // 饱和期间业务线程与调度线程都不等待：投递只标记/入队，失败则复位待发标记
            long started = System.nanoTime();
            fixture.streams.publish(event(fixture.projectId, UUID.randomUUID(), 1,
                    AgentEventType.MODEL_STARTED));
            fixture.streams.publishContentDelta(fixture.projectId, UUID.randomUUID(), payload(1, "x"));
            fixture.streams.heartbeat();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertThat(elapsedMs).as("发送池饱和时业务线程/调度线程不得阻塞").isLessThan(250L);

            // 饱和期间登记一个新订阅：其调度可能被拒绝
            UUID recoveredRun = UUID.randomUUID();
            SseEmitter recovered = mock(SseEmitter.class);
            CountDownLatch recoveredReceived = new CountDownLatch(1);
            doAnswer(invocation -> {
                recoveredReceived.countDown();
                return null;
            }).when(recovered).send(any(SseEmitter.SseEventBuilder.class));
            AgentRunEventView durable = event(
                    fixture.projectId, recoveredRun, 1, AgentEventType.MODEL_COMPLETED);
            when(fixture.repository.list(eq(fixture.projectId), eq(recoveredRun), eq(0L), anyInt()))
                    .thenReturn(List.of(durable));
            register(fixture.streams, fixture.projectId, recoveredRun, recovered);

            // 释放慢连接、等发送池排空，再由后续事件唤醒重试：订阅不被永久搁置
            releaseAll.countDown();
            awaitSendPoolIdle(fixture.streams);
            fixture.streams.publish(durable);
            assertThat(recoveredReceived.await(3, TimeUnit.SECONDS))
                    .as("发送池恢复后订阅仍能被调度并补回持久事件").isTrue();
        } finally {
            releaseAll.countDown();
            fixture.close();
        }
    }

    @Test
    void latePreviewFramesDoNotReviveAClosedSubscription() throws Exception {
        Fixture fixture = new Fixture();
        SseEmitter failing = mock(SseEmitter.class);
        doThrow(new IllegalStateException("连接已关闭"))
                .when(failing).send(any(SseEmitter.SseEventBuilder.class));
        register(fixture.streams, fixture.projectId, fixture.runId, failing);
        try {
            fixture.streams.publishContentDelta(fixture.projectId, fixture.runId, payload(1, "第一段"));
            verify(failing, timeout(2_000)).send(any(SseEmitter.SseEventBuilder.class));
            awaitDetached(fixture.streams, fixture.runId);
            // 迟到的临时帧与持久事件都不能复活已关闭的订阅
            fixture.streams.publishContentDelta(fixture.projectId, fixture.runId, payload(2, "迟到"));
            fixture.streams.publish(event(fixture.projectId, fixture.runId, 1, AgentEventType.MODEL_STARTED));
            verify(failing, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        } finally {
            fixture.close();
        }
    }

    /** 模拟慢订阅者：阻塞到释放闩锁；线程被中断（收尾关闭连接）时当作订阅已断开返回。 */
    private static void awaitQuietly(CountDownLatch release) {
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String turn(Fixture fixture) {
        return fixture.adapter.turnWithSession(fixture.config, "fixture-key", fixture.command,
                AiRequestMetadata.fresh(), "fixture").content();
    }

    private static ObjectNode payload(int revision, String text) {
        return new ObjectMapper().createObjectNode()
                .put("modelCallId", "fixture-call")
                .put("revision", revision)
                .put("text", text)
                .put("final", false);
    }

    private static AgentRunEventView event(
            UUID projectId, UUID runId, long sequence, AgentEventType type) {
        return new AgentRunEventView(UUID.randomUUID(), projectId, runId, sequence,
                type, new ObjectMapper().createObjectNode(), OffsetDateTime.now());
    }

    /** 从捕获到的 SSE 帧里取出持久事件（临时帧与 heartbeat 的数据对象不是视图）。 */
    private static List<AgentRunEventView> durableFrames(List<SseEmitter.SseEventBuilder> builders) {
        return builders.stream()
                .flatMap(builder -> builder.build().stream())
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(AgentRunEventView.class::isInstance)
                .map(AgentRunEventView.class::cast)
                .toList();
    }

    /** 发送池容量（worker 数 + 队列上限）：从实现常量读取，避免测试硬编码漂移。 */
    private static int sendCapacity() throws Exception {
        Field writers = AgentEventStreamService.class.getDeclaredField("SEND_WRITERS");
        Field queue = AgentEventStreamService.class.getDeclaredField("SEND_QUEUE_CAPACITY");
        writers.setAccessible(true);
        queue.setAccessible(true);
        return writers.getInt(null) + queue.getInt(null);
    }

    private static void awaitSendPoolIdle(AgentEventStreamService streams) throws Exception {
        Field field = AgentEventStreamService.class.getDeclaredField("senders");
        field.setAccessible(true);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(streams);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((pool.getActiveCount() > 0 || !pool.getQueue().isEmpty())
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static void awaitDetached(AgentEventStreamService streams, UUID runId) throws Exception {
        Field field = AgentEventStreamService.class.getDeclaredField("subscriptions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, CopyOnWriteArrayList<Object>> subscriptions =
                (Map<UUID, CopyOnWriteArrayList<Object>>) field.get(streams);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (subscriptions.get(runId) != null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(subscriptions.get(runId)).isNull();
    }

    /**
     * 把订阅放进既有 fanout 表并像 {@code subscribe} 一样唤醒发送端：
     * {@code Subscription} 是私有实现，测试用反射构造。
     */
    @SuppressWarnings("unchecked")
    private static void register(
            AgentEventStreamService streams, UUID projectId, UUID runId, SseEmitter emitter) throws Exception {
        Class<?> subscriptionType = Class.forName(AgentEventStreamService.class.getName() + "$Subscription");
        Constructor<?> constructor = subscriptionType.getDeclaredConstructor(
                UUID.class, UUID.class, SseEmitter.class, long.class);
        constructor.setAccessible(true);
        Object subscription = constructor.newInstance(projectId, runId, emitter, 0L);
        Field field = AgentEventStreamService.class.getDeclaredField("subscriptions");
        field.setAccessible(true);
        Map<UUID, CopyOnWriteArrayList<Object>> subscriptions =
                (Map<UUID, CopyOnWriteArrayList<Object>>) field.get(streams);
        subscriptions.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(subscription);
        Method schedule = AgentEventStreamService.class.getDeclaredMethod("schedule", subscriptionType);
        schedule.setAccessible(true);
        schedule.invoke(streams, subscription);
    }

    private static final class Fixture {
        final ObjectMapper json = new ObjectMapper();
        final UUID projectId = UUID.randomUUID();
        final UUID runId = UUID.randomUUID();
        final AgentEventRepository repository = mock(AgentEventRepository.class);
        final AgentEventStreamService streams = new AgentEventStreamService(
                mock(ProjectAccessGuard.class), mock(AgentRepository.class), repository);
        final AgentEventService events = new AgentEventService(repository, streams);
        final HttpClient transport = mock(HttpClient.class);
        final JsonHttpModelClient http;
        final OpenAiCompatibleModelAdapter adapter;
        final ModelConfiguration config;
        final ModelTurnCommand command;

        Fixture() throws Exception {
            http = new JsonHttpModelClient(
                    json, mock(OutboundEndpointPolicy.class), transport, Duration.ofSeconds(1));
            adapter = new OpenAiCompatibleModelAdapter(json, http);
            config = new ModelConfiguration(UUID.randomUUID(), projectId, "fixture",
                    ModelProviderType.OPENAI_COMPATIBLE, "https://example.com", "/v1/chat/completions",
                    null, "fixture-model", true, 0.2, 1200,
                    EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.NATIVE_TOOLS),
                    OffsetDateTime.now(), OffsetDateTime.now());
            command = new ModelTurnCommand(
                    ModelPurpose.AGENT, List.of(new ModelMessage.User("fixture")), List.of(), false);
            HttpResponse<InputStream> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.headers()).thenReturn(HttpHeaders.of(
                    Map.of("Content-Type", List.of("text/event-stream")), (a, b) -> true));
            String wire = "data: {\"choices\":[{\"delta\":{\"content\":\"provider replied immediately\"},"
                    + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            when(response.body()).thenAnswer(
                    invocation -> new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
            when(transport.send(any(HttpRequest.class),
                    ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any())).thenReturn(response);
        }

        void close() {
            streams.shutdown();
            http.close();
        }
    }
}
