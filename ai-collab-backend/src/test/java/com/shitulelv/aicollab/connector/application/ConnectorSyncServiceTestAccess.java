package com.shitulelv.aicollab.connector.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Test bridge for the package-private hash helper. */
final class ConnectorSyncServiceTestAccess {
    private ConnectorSyncServiceTestAccess() {}

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
