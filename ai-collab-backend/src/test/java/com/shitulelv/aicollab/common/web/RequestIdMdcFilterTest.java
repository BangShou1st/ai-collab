package com.shitulelv.aicollab.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdMdcFilterTest {

    private final RequestIdMdcFilter filter = new RequestIdMdcFilter();

    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    @Test
    void generatesServerOwnedRequestId_echoesHeader_andClearsMdcAfterward() throws Exception {
        List<String> seenInChain = new ArrayList<>();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                seenInChain.add(MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID_KEY)));

        assertThat(seenInChain).hasSize(1).allMatch(id -> id != null && id.matches("[0-9a-f]{16}"));
        assertThat(response.getHeader(RequestIdMdcFilter.REQUEST_ID_HEADER)).isEqualTo(seenInChain.get(0));
        assertThat(MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID_KEY)).isNull();
    }

    @Test
    void ignoresClientSuppliedRequestIdHeader() throws Exception {
        // 客户端头只可能伪造关联或注入日志内容；关联 ID 必须由服务端生成。
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.addHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, "attacker-controlled\nvalue");
        MockHttpServletResponse response = new MockHttpServletResponse();

        List<String> seenInChain = new ArrayList<>();
        filter.doFilter(request, response, (req, res) ->
                seenInChain.add(MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID_KEY)));

        assertThat(seenInChain.get(0)).doesNotContain("attacker");
        assertThat(response.getHeader(RequestIdMdcFilter.REQUEST_ID_HEADER)).isEqualTo(seenInChain.get(0));
    }

    @Test
    void distinctRequests_getDistinctIds() throws Exception {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                    (req, res) -> seen.add(MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID_KEY)));
        }
        assertThat(seen.get(0)).isNotEqualTo(seen.get(1));
    }
}
