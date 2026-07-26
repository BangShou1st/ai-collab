package com.shitulelv.aicollab.planning.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class PlanningRequestHash {
    private PlanningRequestHash() {}

    public static String confirmation(UUID projectId, UUID planId, UUID versionId) {
        String canonical = "project=" + projectId + "&plan=" + planId + "&version=" + versionId;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
