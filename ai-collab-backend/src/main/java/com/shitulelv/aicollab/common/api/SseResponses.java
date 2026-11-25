package com.shitulelv.aicollab.common.api;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 统一构造 SSE 流式响应：关闭 Nginx 与外层网关的响应缓冲及内容转换，避免流式帧攒批。
 */
public final class SseResponses {
    private SseResponses() {
    }

    public static ResponseEntity<SseEmitter> stream(SseEmitter emitter) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache().noTransform())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
