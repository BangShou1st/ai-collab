package com.shitulelv.aicollab.connector.infrastructure.feishu;

/** Feishu OpenAPI business error; the message is safe for UI display. */
public class FeishuApiException extends RuntimeException {
    private final int feishuCode;

    public FeishuApiException(int feishuCode, String message) {
        super(message);
        this.feishuCode = feishuCode;
    }

    public int feishuCode() {
        return feishuCode;
    }

    public boolean forbidden() {
        return feishuCode == 403 || feishuCode == 1061004 || feishuCode == 1063002
                || feishuCode == 1770032 || feishuCode == 230002;
    }

    public boolean invalidToken() {
        return feishuCode == 401 || feishuCode == 99991661 || feishuCode == 99991663 || feishuCode == 99991668
                || feishuCode == 99991677 || feishuCode == 20005 || feishuCode == 20006;
    }

    public boolean invalidApplicationCredentials() {
        return feishuCode == 20002 || feishuCode == 20009 || feishuCode == 20048 || feishuCode == 20069;
    }
}
