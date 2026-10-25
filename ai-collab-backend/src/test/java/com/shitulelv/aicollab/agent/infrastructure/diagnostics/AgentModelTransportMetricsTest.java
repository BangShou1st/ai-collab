package com.shitulelv.aicollab.agent.infrastructure.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.infrastructure.ai.model.*;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentModelTransportMetricsTest {
    @Test void c03ActualTransportSignalCrossesPostAndStreamExecutorBoundaries() throws Exception {
        var json=new ObjectMapper();
        var registry=new SimpleMeterRegistry();
        var metrics=new AgentRuntimeMetrics(registry);
        var transport=mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{}");
        doReturn(response).when(transport).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        var policy=new OutboundEndpointPolicy(host->new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        var client=new JsonHttpModelClient(json,policy,transport);
        try {
            metrics.model("turn",()->client.post("https://model.example/v1/chat",Map.of(),json.createObjectNode(),1));
            assertThat(AiOutboundObservation.current()).isNull();
            @SuppressWarnings("unchecked") HttpResponse<InputStream> stream=mock(HttpResponse.class);
            when(stream.statusCode()).thenReturn(200);
            when(stream.body()).thenReturn(new ByteArrayInputStream("data: {}\n\ndata: [DONE]\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            when(stream.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("text/event-stream")),(a,b)->true));
            doReturn(stream).when(transport).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
            metrics.model("summary",()->{client.stream("https://model.example/v1/chat",Map.of(),json.createObjectNode(),(event,data)->{});return null;});
            assertThat(AiOutboundObservation.current()).isNull();
        } finally { client.close(); }
        assertThat(registry.get("agent.model.request").tags("purpose","turn","outcome","success").counter().count()).isEqualTo(1);
        assertThat(registry.get("agent.model.request").tags("purpose","summary","outcome","success").counter().count()).isEqualTo(1);
        assertThat(registry.get("agent.model.request.duration").tags("purpose","summary","outcome","success").timer().count()).isEqualTo(1);
    }
    @Test void c03LocalEndpointRejectionDoesNotCountAsOutbound() throws Exception {
        var registry=new SimpleMeterRegistry();
        var metrics=new AgentRuntimeMetrics(registry);
        var transport=mock(HttpClient.class);
        var json=new ObjectMapper();
        var client=new JsonHttpModelClient(json,new OutboundEndpointPolicy(host->new InetAddress[]{InetAddress.getLoopbackAddress()}),transport);
        try {
            assertThatThrownBy(()->metrics.model("turn",()->client.post("https://model.example/v1/chat",Map.of(),json.createObjectNode(),1))).isInstanceOf(RuntimeException.class);
            assertThat(registry.find("agent.model.request").counter()).isNull();
            assertThat(AiOutboundObservation.current()).isNull();
            verifyNoInteractions(transport);
        } finally { client.close(); }
    }
    @Test void c03KiloTransportCountsAfterClientAvailabilityCheck() {
        var json=new ObjectMapper();var registry=new SimpleMeterRegistry();var metrics=new AgentRuntimeMetrics(registry);
        var shared=mock(com.sharedlibs.kilogateway.KiloGatewayClient.class);
        when(shared.chat(any())).thenReturn(json.createObjectNode());
        var kilo=new KiloGatewayClientTransport(shared);
        metrics.model("turn",()->kilo.chat(json.createObjectNode()));
        assertThat(registry.get("agent.model.request").tag("outcome","success").counter().count()).isEqualTo(1);
        assertThat(AiOutboundObservation.current()).isNull();
    }
    @Test void c03KiloLocalRequestPreparationRejectionIsNotAnOutboundRequest() {
        var registry=new SimpleMeterRegistry();var metrics=new AgentRuntimeMetrics(registry);
        var shared=mock(com.sharedlibs.kilogateway.KiloGatewayClient.class);
        when(shared.chat(any())).thenThrow(new IllegalArgumentException("private-model local validation"));
        var kilo=new KiloGatewayClientTransport(shared);
        assertThatThrownBy(()->metrics.model("turn",()->kilo.chat(new ObjectMapper().createObjectNode())))
                .isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class);
        assertThat(registry.find("agent.model.request").counter()).isNull();
        assertThat(AiOutboundObservation.current()).isNull();
    }

}
