package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelContentPreview;

import java.util.UUID;

/**
 * 把模型正文累计快照发布为临时 SSE 帧。帧是<b>自包含的累计文本</b>并带请求内递增 revision，
 * 订阅端按 revision 幂等替换缓冲——丢弃中间帧不产生缺口，重复/过期帧不会拼接出错。
 *
 * <p>节流：距上次发送超过 {@link #MIN_DELTA_CHARS} 字符，或持续有新增且距上次超过
 * {@link #MIN_INTERVAL_MS} 毫秒时才发送，合并短片段降低重绘。finalFrame 总是发送，
 * 让前端在持久完成事件到达前就能定格完整预览。</p>
 *
 * <p>只在模型调用的 worker 线程上被回调（适配器流式聚合是同步读取）；
 * 发布走 {@link AgentEventService#publishContentDelta}，不落库、不占持久事件序号。</p>
 */
final class AgentContentPreviewPublisher implements ModelContentPreview.Observer {

    private static final long MIN_INTERVAL_MS = 300;
    private static final int MIN_DELTA_CHARS = 80;

    private final AgentEventService events;
    private final ObjectMapper json;
    private final UUID projectId;
    private final UUID runId;
    private final String modelCallId;
    private int revision = 0;
    private int lastSentChars = 0;
    private long lastSentAt = 0;

    AgentContentPreviewPublisher(
            AgentEventService events, ObjectMapper json, UUID projectId, UUID runId, String modelCallId) {
        this.events = events;
        this.json = json;
        this.projectId = projectId;
        this.runId = runId;
        this.modelCallId = modelCallId;
    }

    @Override
    public void onContent(String cumulativeText, boolean finalFrame) {
        if (cumulativeText == null || cumulativeText.isEmpty()) return;
        long now = System.currentTimeMillis();
        int length = cumulativeText.length();
        boolean due = finalFrame
                || length - lastSentChars >= MIN_DELTA_CHARS
                || (length != lastSentChars && now - lastSentAt >= MIN_INTERVAL_MS);
        if (!due) return;
        ObjectNode payload = json.createObjectNode()
                .put("modelCallId", modelCallId)
                .put("revision", ++revision)
                .put("text", cumulativeText)
                .put("final", finalFrame);
        events.publishContentDelta(projectId, runId, payload);
        lastSentChars = length;
        lastSentAt = now;
    }
}
