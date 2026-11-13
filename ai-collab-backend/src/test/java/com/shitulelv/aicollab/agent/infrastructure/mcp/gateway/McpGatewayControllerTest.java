package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpGatewayControllerTest {

    @Test
    void mappingIsAvailableOnlyThroughTheConditionalBeanRegistration() throws Exception {
        McpGatewayService service = mock(McpGatewayService.class);
        ObjectMapper json = new ObjectMapper();
        var result = json.createObjectNode().put("status", "ok");
        when(service.handle(any(), any()))
                .thenReturn(new McpGatewayService.McpGatewayResponse(200, result));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpGatewayController(service, json)).build();

        mvc.perform(post("/internal/ai/v1/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"ping\",\"params\":{}}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"ok\"}"));

        verify(service).handle(any(), any());
    }
}
