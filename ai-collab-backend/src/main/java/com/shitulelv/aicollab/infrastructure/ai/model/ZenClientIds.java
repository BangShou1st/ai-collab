package com.shitulelv.aicollab.infrastructure.ai.model;

import com.sharedlibs.opencodezen.OpenCodeZenIdGenerator;
import java.util.LinkedHashMap;

/** Provider-only client IDs. Business correlation IDs never become wire session IDs. */
final class ZenClientIds {
    private static final java.util.Map<String,String> SESSIONS = new LinkedHashMap<>(128, .75f, true);
    private ZenClientIds() {}
    static synchronized String session(String correlation) {
        if (correlation == null) return OpenCodeZenIdGenerator.newSessionId();
        String existing = SESSIONS.get(correlation);
        if (existing != null) return existing;
        if (SESSIONS.size() >= 8192) SESSIONS.remove(SESSIONS.keySet().iterator().next());
        String created = OpenCodeZenIdGenerator.newSessionId();
        SESSIONS.put(correlation, created);
        return created;
    }
}
