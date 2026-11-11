package com.shitulelv.aicollab.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立管理端口的匹配边界：只有"配置了独立端口且请求落在该端口"时才接管。
 * 任何其它情况都必须返回 false，否则会把主端口的 /actuator 变成匿名可读。
 */
class ManagementPortRequestMatcherTest {

    private static boolean matches(int managementPort, int serverPort, int requestLocalPort) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setLocalPort(requestLocalPort);
        return new ManagementPortRequestMatcher(managementPort, serverPort).matches(request);
    }

    @Test
    void matchesOnlyTheConfiguredManagementPort() {
        assertThat(matches(8081, 8080, 8081)).isTrue();
        assertThat(matches(8081, 8080, 8080)).isFalse();
        assertThat(matches(8081, 8080, 9090)).isFalse();
    }

    @Test
    void neverMatchesWhenManagementPortIsDisabled() {
        assertThat(matches(-1, 8080, 8081)).isFalse();
        assertThat(matches(0, 8080, 0)).isFalse();
    }

    @Test
    void neverMatchesWhenManagementSharesTheApplicationPort() {
        // management.server.port == server.port 时 Boot 不会起第二个连接器，
        // 此时放行 /actuator/** 等于把主端口的 actuator 打开，必须拒绝。
        assertThat(matches(8080, 8080, 8080)).isFalse();
    }
}
