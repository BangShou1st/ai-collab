package com.shitulelv.aicollab.infrastructure.ai.model;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedlibs.kilogateway.KiloGatewayClient;
import com.sharedlibs.kilogateway.KiloGatewayException;
import com.shitulelv.aicollab.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 服务端日志安全：Kilo 出站失败只记录可信诊断（HTTP statusCode、固定分类、是否未完成流）。
 * 上游原始正文、provider 名称与异常消息属不可信输入，不得进入日志。
 */
class KiloGatewayTransportLogSafetyTest {
    private static final String SECRET = "SUPERSECRET";
    private static final String HOSTILE_BODY = "Kilo HTTP 502 (ForgedProvider): <html><body>Internal Server Error"
            + "</body></html> at /srv/kilo/internal/route?token=" + SECRET + "&access_key=" + SECRET;

    private final ObjectMapper mapper = new ObjectMapper();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(KiloGatewayClientTransport.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    private String logs() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    private static void assertNoUntrustedContent(String logs) {
        assertThat(logs)
                .as("日志不得包含不可信上游内容")
                .doesNotContain(SECRET)
                .doesNotContain("token=")
                .doesNotContain("/srv/")
                .doesNotContain("<html>")
                .doesNotContain("Internal Server Error")
                .doesNotContain("ForgedProvider")
                .doesNotContain("access_key");
    }

    @Test void upstreamBodyAndForgedProviderNeverReachLogs() {
        var client = mock(KiloGatewayClient.class);
        when(client.chat(any())).thenThrow(new KiloGatewayException(502, "ForgedProvider", HOSTILE_BODY));

        assertThatThrownBy(() -> new KiloGatewayClientTransport(client).chat(mapper.createObjectNode()))
                .isInstanceOf(BusinessException.class);

        String logs = logs();
        assertThat(logs).contains("status=502").contains("class=UPSTREAM_5XX").contains("incompleteStream=false");
        assertNoUntrustedContent(logs);
    }

    @Test void rateLimitAndCredentialDiagnosticsStayContentFree() {
        var throttled = mock(KiloGatewayClient.class);
        when(throttled.chat(any())).thenThrow(new KiloGatewayException(429, "ForgedProvider", HOSTILE_BODY));
        assertThatThrownBy(() -> new KiloGatewayClientTransport(throttled).chat(mapper.createObjectNode()))
                .isInstanceOf(BusinessException.class);
        assertThat(logs()).contains("class=RATE_LIMITED");
        assertNoUntrustedContent(logs());

        appender.list.clear();

        var unauthorized = mock(KiloGatewayClient.class);
        when(unauthorized.chat(any())).thenThrow(new KiloGatewayException(401, "ForgedProvider",
                "missing key at /srv/kilo/secret.pem?token=" + SECRET));
        assertThatThrownBy(() -> new KiloGatewayClientTransport(unauthorized).chat(mapper.createObjectNode()))
                .isInstanceOf(BusinessException.class);
        assertThat(logs()).contains("class=CREDENTIAL_REJECTED");
        assertNoUntrustedContent(logs());
    }

    @Test void incompleteStreamDiagnosticCarriesNoUpstreamText() {
        var client = mock(KiloGatewayClient.class);
        doThrow(new KiloGatewayException(KiloGatewayException.STATUS_INCOMPLETE_STREAM, "ForgedProvider",
                "Kilo SSE stream ended without the [DONE] completion signal at /srv/x?token=" + SECRET))
                .when(client).streamChat(any(), any());

        assertThatThrownBy(() -> new KiloGatewayClientTransport(client)
                .streamChat(mapper.createObjectNode(), chunk -> { }))
                .isInstanceOf(BusinessException.class);

        String logs = logs();
        assertThat(logs).contains("class=INCOMPLETE_STREAM").contains("incompleteStream=true");
        assertNoUntrustedContent(logs);
        assertThat(logs).doesNotContain("[DONE]");
    }

    @Test void timeoutDiagnosticDoesNotEchoTheCauseMessage() {
        var client = mock(KiloGatewayClient.class);
        when(client.chat(any())).thenThrow(new KiloGatewayException(-1, "ForgedProvider",
                "connection failed at /srv/x?token=" + SECRET,
                new java.net.http.HttpTimeoutException("read timeout /srv/x?token=" + SECRET)));

        assertThatThrownBy(() -> new KiloGatewayClientTransport(client).chat(mapper.createObjectNode()))
                .isInstanceOf(BusinessException.class);

        String logs = logs();
        assertThat(logs).contains("class=TIMEOUT");
        assertNoUntrustedContent(logs);
        assertThat(logs).doesNotContain("read timeout");
    }

    @Test void libraryRequestRejectionLogsOnlyAFixedLabel() {
        var client = mock(KiloGatewayClient.class);
        when(client.chat(any())).thenThrow(new IllegalArgumentException(
                "Free-only mode rejected non-free model: paid/model?token=" + SECRET));

        assertThatThrownBy(() -> new KiloGatewayClientTransport(client).chat(mapper.createObjectNode()))
                .isInstanceOf(BusinessException.class);

        String logs = logs();
        assertThat(logs).contains("class=INVALID_REQUEST");
        assertNoUntrustedContent(logs);
        assertThat(logs).doesNotContain("Free-only").doesNotContain("paid/model");
    }

    @Test void transportFailureIsClassifiedWithoutUpstreamText() {
        var client = mock(KiloGatewayClient.class);
        doThrow(new KiloGatewayException(-1, "ForgedProvider", "Streaming I/O failure " + HOSTILE_BODY,
                new java.io.IOException("broken pipe /srv/x?token=" + SECRET)))
                .when(client).streamChat(any(), any());

        assertThatThrownBy(() -> new KiloGatewayClientTransport(client)
                .streamChat(mapper.createObjectNode(), chunk -> { }))
                .isInstanceOf(BusinessException.class);

        assertThat(logs()).contains("class=TRANSPORT_ERROR");
        assertNoUntrustedContent(logs());
    }
}
