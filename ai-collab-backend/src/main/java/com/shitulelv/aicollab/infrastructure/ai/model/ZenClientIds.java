package com.shitulelv.aicollab.infrastructure.ai.model;

import java.util.LinkedHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Provider-only client IDs. Business correlation IDs never become wire session IDs. */
final class ZenClientIds {
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final java.util.Map<String,String> SESSIONS = new LinkedHashMap<>(128, .75f, true);
    private ZenClientIds() {}
    static synchronized String session(String correlation) {
        if (correlation == null) return generate("ses_", true);
        String existing = SESSIONS.get(correlation);
        if (existing != null) return existing;
        if (SESSIONS.size() >= 8192) SESSIONS.remove(SESSIONS.keySet().iterator().next());
        String created = generate("ses_", true);
        SESSIONS.put(correlation, created);
        return created;
    }
    static String request() { return generate("msg_", false); }
    private static String generate(String prefix, boolean descending) {
        var random = ThreadLocalRandom.current();
        long value = System.currentTimeMillis() * 0x1000L + random.nextInt(0x1000);
        if (descending) value = ~value;
        StringBuilder id = new StringBuilder(prefix);
        for (int shift = 44; shift >= 0; shift -= 4) id.append(Character.forDigit((int)(value >>> shift & 15), 16));
        for (int i = 0; i < 14; i++) id.append(BASE62.charAt(random.nextInt(62)));
        return id.toString();
    }
}
