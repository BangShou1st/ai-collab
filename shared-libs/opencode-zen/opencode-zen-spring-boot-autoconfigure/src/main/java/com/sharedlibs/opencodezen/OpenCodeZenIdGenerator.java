package com.sharedlibs.opencodezen;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Generates the timestamp-shaped ses_/msg_ identifiers required by Zen. */
public final class OpenCodeZenIdGenerator {

    private static final String BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int ID_LENGTH = 26;
    private static final int PREFIX_HEX_LENGTH = 12;

    private static final Object COUNTER_LOCK = new Object();
    private static long counterMillis;
    private static int counter;

    private OpenCodeZenIdGenerator() {
    }

    /** Creates a descending session id; callers should cache it for a conversation. */
    public static String newSessionId() {
        return "ses_" + generate(true);
    }

    /** Creates an ascending request id; call once for each HTTP request. */
    public static String newRequestId() {
        return "msg_" + generate(false);
    }

    private static String generate(boolean descending) {
        long timestamp;
        int slot;
        synchronized (COUNTER_LOCK) {
            timestamp = System.currentTimeMillis();
            if (timestamp != counterMillis) {
                counterMillis = timestamp;
                counter = 0;
            }
            while (counter > 0xF) {
                timestamp = System.currentTimeMillis();
                if (timestamp != counterMillis) {
                    counterMillis = timestamp;
                    counter = 0;
                }
            }
            slot = counter++;
        }

        long current = timestamp * 0x1000L + slot;
        long value = descending ? ~current : current;
        StringBuilder id = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < PREFIX_HEX_LENGTH / 2; i++) {
            id.append(String.format(Locale.ROOT, "%02x", (int) ((value >> (40 - 8 * i)) & 0xFF)));
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = PREFIX_HEX_LENGTH; i < ID_LENGTH; i++) {
            id.append(BASE62.charAt(random.nextInt(BASE62.length())));
        }
        return id.toString();
    }
}