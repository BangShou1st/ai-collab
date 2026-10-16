package com.sharedlibs.opencodezen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeZenIdGeneratorTest {

    @Test
    void createsTimestampShapedSessionAndRequestIds() {
        assertThat(OpenCodeZenIdGenerator.newSessionId())
                .matches("ses_[0-9a-f]{12}[0-9A-Za-z]{14}");
        assertThat(OpenCodeZenIdGenerator.newRequestId())
                .matches("msg_[0-9a-f]{12}[0-9A-Za-z]{14}");
    }
}
