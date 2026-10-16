package com.sharedlibs.opencodezen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SseFrameDecoderTest {

    @Test
    void joinsDataLinesAndIgnoresOtherFields() {
        SseFrameDecoder decoder = new SseFrameDecoder();

        assertThat(decoder.accept(": comment")).isEmpty();
        assertThat(decoder.accept("event: message")).isEmpty();
        assertThat(decoder.accept("data: first")).isEmpty();
        assertThat(decoder.accept("data: second")).isEmpty();
        assertThat(decoder.accept("")).contains("first\nsecond");
        assertThat(decoder.finish()).isEmpty();
    }

    @Test
    void flushesFinalFrameAtEndOfStream() {
        SseFrameDecoder decoder = new SseFrameDecoder();
        decoder.accept("data: [DONE]");

        assertThat(decoder.finish()).contains("[DONE]");
        assertThat(decoder.finish()).isEmpty();
    }
}
