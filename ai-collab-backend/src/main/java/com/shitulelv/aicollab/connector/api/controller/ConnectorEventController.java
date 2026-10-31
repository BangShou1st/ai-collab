package com.shitulelv.aicollab.connector.api.controller;

import com.shitulelv.aicollab.connector.application.ConnectorEventService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public class ConnectorEventController {
    private final ConnectorEventService events;
    public ConnectorEventController(ConnectorEventService events) { this.events=events; }

    @PostMapping("/api/v1/connector/events/{connectionId}")
    public ResponseEntity<Map<String,Object>> receive(@PathVariable UUID connectionId, @RequestBody byte[] body,
            @RequestHeader(value="X-Lark-Request-Timestamp",required=false) String timestamp,
            @RequestHeader(value="X-Lark-Request-Nonce",required=false) String nonce,
            @RequestHeader(value="X-Lark-Signature",required=false) String signature) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(events.receive(connectionId,body,timestamp,nonce,signature));
    }
}
