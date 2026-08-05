package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEventControllerTest {
    @Test
    void lastEventIdCannotMoveCursorBackwards() {
        assertThat(AgentEventController.resolveCursor(12, "8")).isEqualTo(12);
        assertThat(AgentEventController.resolveCursor(12, "15")).isEqualTo(15);
    }

    @Test
    void rejectsNegativeOrMalformedCursor() {
        assertThatThrownBy(() -> AgentEventController.resolveCursor(-1, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AGENT_EVENT_CURSOR_INVALID);
        assertThatThrownBy(() -> AgentEventController.resolveCursor(0, "not-a-number"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AGENT_EVENT_CURSOR_INVALID);
    }
}
