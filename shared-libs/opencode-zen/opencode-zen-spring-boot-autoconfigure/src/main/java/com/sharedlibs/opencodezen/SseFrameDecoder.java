package com.sharedlibs.opencodezen;

import java.util.Optional;

/**
 * Decodes the data field of one Server-Sent Event frame.
 *
 * <p>It deliberately leaves JSON and provider semantics to the consuming adapter.
 */
public final class SseFrameDecoder {

    private final StringBuilder data = new StringBuilder();

    /**
     * Accepts one line from a normalized SSE stream. A completed non-empty data frame
     * is returned when the blank event delimiter arrives.
     */
    public Optional<String> accept(String line) {
        if (line == null) {
            throw new IllegalArgumentException("SSE line must not be null");
        }
        if (line.isEmpty()) {
            return dispatch();
        }
        if (line.startsWith("data:")) {
            String value = line.substring("data:".length());
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (data.length() > 0) {
                data.append('\n');
            }
            data.append(value);
        }
        return Optional.empty();
    }

    /** Flushes a final data frame when the stream ends without a blank delimiter. */
    public Optional<String> finish() {
        return dispatch();
    }

    private Optional<String> dispatch() {
        if (data.length() == 0) {
            return Optional.empty();
        }
        String frame = data.toString();
        data.setLength(0);
        return Optional.of(frame);
    }
}