package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 正文预览发布器：累计快照 + 请求内 revision 幂等；节流合并短片段，
 * final 帧总是发送。发布经 AgentEventService 转发，不落库。
 */
class AgentContentPreviewPublisherTest {

    private final ObjectMapper json = new ObjectMapper();
    private final AgentEventService events = mock(AgentEventService.class);
    private final List<JsonNode> frames = new ArrayList<>();
    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void capturePublishedFrames() {
        doAnswer(inv -> {
            frames.add((JsonNode) inv.getArgument(2));
            return null;
        }).when(events).publishContentDelta(eq(projectId), eq(runId), any());
    }

    private AgentContentPreviewPublisher publisher() {
        return new AgentContentPreviewPublisher(events, json, projectId, runId, "call-1");
    }

    @Test
    void finalFrameIsAlwaysSentWithPayloadIdentity() {
        publisher().onContent("完整正文", true);

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).path("modelCallId").asText()).isEqualTo("call-1");
        assertThat(frames.get(0).path("revision").asInt()).isEqualTo(1);
        assertThat(frames.get(0).path("text").asText()).isEqualTo("完整正文");
        assertThat(frames.get(0).path("final").asBoolean()).isTrue();
    }

    @Test
    void firstPushSendsImmediatelyAndShortUpdatesAreCoalescedUntilCharThreshold() {
        AgentContentPreviewPublisher publisher = publisher();
        publisher.onContent("短", false); // 首次推送立即发送（lastSentAt 初始为 0）
        publisher.onContent("短", false); // 无新增不发送
        publisher.onContent("短" + "字".repeat(90), false); // 新增 ≥80 字符 → 发送

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).path("revision").asInt()).isEqualTo(1);
        assertThat(frames.get(0).path("text").asText()).isEqualTo("短");
        assertThat(frames.get(1).path("revision").asInt()).isEqualTo(2);
        assertThat(frames.get(1).path("final").asBoolean()).isFalse();
    }

    @Test
    void revisionsIncreaseMonotonicallyAcrossSentFrames() {
        AgentContentPreviewPublisher publisher = publisher();
        publisher.onContent("a".repeat(100), false); // 阈值直达 → 发送
        publisher.onContent("a".repeat(200), false); // 新增 100 → 发送
        publisher.onContent("a".repeat(260), false); // 新增 60 未达阈值且未到间隔 → 合并
        publisher.onContent("a".repeat(260), true);  // final 总是发送

        assertThat(frames).extracting(f -> f.path("revision").asInt())
                .containsExactly(1, 2, 3);
        assertThat(frames.get(2).path("text").asText()).hasSize(260);
        assertThat(frames.get(2).path("final").asBoolean()).isTrue();
    }

    @Test
    void emptyTextIsNeverPublished() {
        AgentContentPreviewPublisher publisher = publisher();
        publisher.onContent("", false);
        publisher.onContent("", true);
        assertThat(frames).isEmpty();
    }

    @Test
    void serverSideSnapshotIsBoundedToTheDisplayLimitAndStopsGrowing() {
        AgentContentPreviewPublisher publisher = publisher();
        publisher.onContent("a".repeat(9000), true);

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).path("text").asText()).hasSize(8000);
        assertThat(frames.get(0).path("final").asBoolean()).isTrue();

        // 到限后不再持续下发同样长度的截断快照（客户端展示上限也为 8000）
        publisher.onContent("a".repeat(9000) + "尾部", false);
        assertThat(frames).hasSize(1);
    }
}
