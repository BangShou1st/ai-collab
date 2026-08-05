package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpMcpClientFacadeTest {
    @Test
    void rejectsResponseBeforeReadingBeyondConfiguredByteLimit() {
        byte[] payload = "123456789".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> HttpMcpClientFacade.readLimited(
                new ByteArrayInputStream(payload), 8))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.AGENT_TOOL_RESULT_TOO_LARGE));
    }

    @Test
    void acceptsResponseAtConfiguredByteLimit() throws Exception {
        byte[] payload = "12345678".getBytes(StandardCharsets.UTF_8);

        assertThat(HttpMcpClientFacade.readLimited(new ByteArrayInputStream(payload), 8))
                .isEqualTo("12345678");
    }
}
