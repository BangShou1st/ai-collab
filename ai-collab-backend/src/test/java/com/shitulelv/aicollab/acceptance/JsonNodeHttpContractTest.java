package com.shitulelv.aicollab.acceptance;

import com.fasterxml.jackson.databind.*;
import com.shitulelv.aicollab.common.config.ObjectMapperConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

class JsonNodeHttpContractTest {
    record Payload(JsonNode payload) {}
    @RestController static class ProbeController {
        @PostMapping("/probe") Payload echo(@RequestBody Payload request) { return request; }
        @GetMapping("/approval") com.shitulelv.aicollab.agent.api.dto.AgentApprovalResponse approval() {
            var view=mock(com.shitulelv.aicollab.agent.application.view.AgentApprovalView.class);
            when(view.revision()).thenReturn(2);
            when(view.proposalFamily()).thenReturn(com.shitulelv.aicollab.agent.domain.model.AgentProposalFamily.TASK_CREATE);
            return com.shitulelv.aicollab.agent.api.dto.AgentApprovalResponse.from(view);
        }
    }
    @Test void httpRoundTripPreservesActualTreeFieldsArraysAndNulls() throws Exception {
        var configuration=new ObjectMapperConfiguration();
        var transport=tools.jackson.databind.json.JsonMapper.builder()
                .addModule(configuration.legacyJsonNodeHttpModule(configuration.objectMapper())).build();
        var mvc=MockMvcBuilders.standaloneSetup(new ProbeController())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(transport)).build();
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"toolName\":\"list_tasks\",\"status\":\"SUCCEEDED\",\"items\":[1,null,{\"title\":\"中文\"}]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.toolName").value("list_tasks"))
                .andExpect(jsonPath("$.payload.items[2].title").value("中文"))
                .andExpect(jsonPath("$.payload.nodeType").doesNotExist())
                .andExpect(content().json("{\"payload\":{\"toolName\":\"list_tasks\",\"status\":\"SUCCEEDED\",\"items\":[1,null,{\"title\":\"中文\"}]}}"));
        mvc.perform(get("/approval")).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.proposalFamily").value("TASK_CREATE"));
    }
}
