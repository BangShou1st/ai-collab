package com.shitulelv.aicollab.infrastructure.ai;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleChatModelGatewayTest {
    @Test
    void planningModeDoesNotRetryOneProviderRequestInsideOneAttempt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(504, -1);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ChatModelProperties(true, "fake",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "/chat",
                    "test-placeholder", "fake-model", Duration.ofSeconds(2),
                    Duration.ofSeconds(2), 0, 100);
            var gateway = new OpenAiCompatibleChatModelGateway(properties, false);

            assertThatThrownBy(() -> gateway.complete(new ChatCompletionCommand("system", "user")))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.AI_MODEL_TIMEOUT));
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }
}
