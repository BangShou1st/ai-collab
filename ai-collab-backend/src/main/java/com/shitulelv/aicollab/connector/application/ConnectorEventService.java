package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuEventCrypto;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorEventCredentialRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ConnectorEventService {
    private final ConnectorConnectionMapper connections;
    private final ConnectorEventCredentialRepository credentials;
    private final ModelSecretCipher cipher;
    private final FeishuEventCrypto crypto;
    private final ConnectorEventProcessor processor;
    public ConnectorEventService(ConnectorConnectionMapper connections, ConnectorEventCredentialRepository credentials,
                                 ModelSecretCipher cipher, FeishuEventCrypto crypto, ConnectorEventProcessor processor) {
        this.connections=connections; this.credentials=credentials; this.cipher=cipher; this.crypto=crypto; this.processor=processor;
    }

    public Map<String, Object> receive(UUID connectionId, byte[] body, String timestamp, String nonce, String signature) {
        try {
            return receiveVerified(connectionId,body,timestamp,nonce,signature);
        } catch (IllegalArgumentException failure) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, "飞书事件安全校验失败");
        }
    }

    private Map<String, Object> receiveVerified(UUID connectionId, byte[] body, String timestamp, String nonce, String signature) {
        var connection = connections.find(connectionId);
        var secret = credentials.find(connectionId).orElseThrow(() -> new IllegalArgumentException("飞书事件未配置"));
        if (connection == null || !"ACTIVE".equals(connection.status)) throw new IllegalArgumentException("飞书事件未配置");
        var event = crypto.decode(body,cipher.decrypt(secret.encryptKeyCipher()),cipher.decrypt(secret.verificationTokenCipher()),
                timestamp,nonce,signature);
        if ("url_verification".equals(event.path("type").asText())) {
            String challenge = event.path("challenge").asText("");
            if (challenge.isBlank() || challenge.length() > 512) throw new IllegalArgumentException("飞书事件校验无效");
            if (!credentials.markVerified(connectionId,secret)) throw new IllegalArgumentException("飞书事件校验无效");
            return Map.of("challenge",challenge);
        }
        var header = event.path("header");
        if (!"auth.user_access_token.revoked_v4".equals(header.path("event_type").asText())) return Map.of("code",0);
        String id=header.path("event_id").asText(""), appId=header.path("app_id").asText("");
        String tenant=header.path("tenant_key").asText(""), openId=event.path("event").path("open_id").asText("");
        String time=header.path("create_time").asText("");
        if (!id.matches("[A-Za-z0-9_-]{1,200}") || !connection.appId.equals(appId) || tenant.isBlank() || openId.isBlank()
                || tenant.length()>200 || openId.length()>200 || !time.matches("[0-9]{13}")) {
            throw new IllegalArgumentException("飞书事件校验无效");
        }
        Instant occurredAt=Instant.ofEpochMilli(Long.parseLong(time));
        if (occurredAt.isAfter(Instant.now().plusSeconds(60))) throw new IllegalArgumentException("飞书事件时间无效");
        processor.revokeTokens(connectionId,cipher.subjectDigest(appId,tenant,openId),id,occurredAt);
        return Map.of("code",0);
    }
}
