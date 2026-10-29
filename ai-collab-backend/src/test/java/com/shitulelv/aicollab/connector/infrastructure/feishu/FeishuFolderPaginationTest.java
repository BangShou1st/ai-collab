package com.shitulelv.aicollab.connector.infrastructure.feishu;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeishuFolderPaginationTest {
    @Test
    void pageLimitAndMissingContinuationFailInsteadOfReturningPartialSnapshot() throws Exception {
        var count = new AtomicInteger();
        var status = new AtomicInteger(200);
        var response = new java.util.concurrent.atomic.AtomicReference<>("""
                {"code":0,"data":{"files":[],"has_more":true,"next_page_token":"next"}}
                """);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/drive/v1/files", exchange -> {
            count.incrementAndGet();
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            FeishuClient client = new FeishuClient(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            assertThatThrownBy(() -> client.listFolderDocx("synthetic-token", "folder"))
                    .isInstanceOf(FeishuApiException.class);
            assertThat(count.get()).isEqualTo(200);
            response.set("{\"code\":0,\"data\":{\"files\":[],\"has_more\":true}}");
            assertThatThrownBy(() -> client.listFolderDocx("synthetic-token", "folder"))
                    .isInstanceOf(FeishuApiException.class);
            response.set("{\"code\":0,\"data\":{\"files\":[],\"has_more\":false}}");
            assertThat(client.listFolderDocx("synthetic-token", "folder")).isEmpty();
            for (String invalid : new String[] {
                    "{}", "{\"code\":0}", "{\"code\":0,\"data\":{}}",
                    "{\"code\":0,\"data\":{\"files\":[],\"has_more\":\"false\"}}",
                    "{\"code\":0,\"data\":{\"files\":[{\"type\":\"docx\"}],\"has_more\":false}}",
                    "{\"code\":0,\"data\":{\"files\":[],\"has_more\":false,\"next_page_token\":\"unexpected\"}}"}) {
                response.set(invalid);
                assertThatThrownBy(() -> client.listFolderDocx("synthetic-token", "folder"))
                        .isInstanceOf(FeishuApiException.class);
            }
            response.set("{}");
            status.set(403);
            assertThatThrownBy(() -> client.listFolderDocx("synthetic-token", "folder"))
                    .isInstanceOfSatisfying(FeishuApiException.class, failure -> assertThat(failure.forbidden()).isTrue());
            status.set(401);
            assertThatThrownBy(() -> client.listFolderDocx("synthetic-token", "folder"))
                    .isInstanceOfSatisfying(FeishuApiException.class, failure -> assertThat(failure.invalidToken()).isTrue());
            status.set(200);
            response.set("{\"code\":99991400}");
            int before = count.get();
            assertThatThrownBy(() -> client.listFolderDocx("synthetic-token", "folder"))
                    .isInstanceOf(FeishuApiException.class);
            assertThat(count.get() - before).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }
}
