package com.shitulelv.aicollab.common.ai.prompt;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实的本地 HTTP worker stub（JDK {@link HttpServer}）：不依赖外网，可精确计数请求、
 * 注入 5xx/404/非法 JSON/延时/单条失败/篡改正文。
 */
final class PromptWorkerStub implements AutoCloseable {

    private static final String DOCUMENT_PREFIX = "/v1/prompts/";
    private static final byte[] ERROR_BODY =
            "{\"code\":\"PROMPT_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8);

    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicInteger manifestRequests = new AtomicInteger();
    private final AtomicInteger documentRequests = new AtomicInteger();

    private volatile PromptPackFixtures.Pack pack;
    private volatile int manifestStatus = 200;
    private volatile int documentStatus = 200;
    private volatile String manifestBody;
    private volatile String documentBody;
    private volatile String failingKey;
    private volatile int failingStatus = 503;
    private volatile String tamperedKey;
    private volatile String tamperedText;
    private volatile Map<String, String> shaOverrides = Map.of();
    private volatile Map<String, List<String>> variableOverrides = Map.of();
    private volatile long delayMillis;

    PromptWorkerStub(PromptPackFixtures.Pack pack) throws IOException {
        this.pack = pack;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "prompt-worker-stub");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/v1/prompts/", exchange -> {
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                String path = exchange.getRequestURI().getPath();
                int status;
                byte[] body;
                if ("/v1/prompts/manifest".equals(path)) {
                    manifestRequests.incrementAndGet();
                    status = manifestStatus;
                    body = manifestStatus == 200
                            ? (manifestBody != null ? manifestBody
                                    : PromptPackFixtures.manifestJson(this.pack))
                                    .getBytes(StandardCharsets.UTF_8)
                            : ERROR_BODY;
                } else {
                    documentRequests.incrementAndGet();
                    String key = keyFrom(path);
                    if (failingKey != null && failingKey.equals(key)) {
                        status = failingStatus;
                    } else {
                        status = documentStatus;
                    }
                    if (status == 200) {
                        if (documentBody != null) {
                            body = documentBody.getBytes(StandardCharsets.UTF_8);
                        } else {
                            PromptPackFixtures.Entry entry = this.pack.entries().get(key);
                            if (entry == null) {
                                status = 404;
                                body = ERROR_BODY;
                            } else {
                                String text = key.equals(tamperedKey) && tamperedText != null
                                        ? tamperedText : this.pack.texts().get(key);
                                String sha = shaOverrides.getOrDefault(key, entry.sha256());
                                List<String> variables = variableOverrides.getOrDefault(
                                        key, entry.variables());
                                body = PromptPackFixtures.documentJson(entry, text, sha, variables)
                                        .getBytes(StandardCharsets.UTF_8);
                            }
                        }
                    } else {
                        body = ERROR_BODY;
                    }
                }
                exchange.getResponseHeaders().set("Content-Type",
                        "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException clientGone) {
                // 读侧超时后客户端已断开，忽略写失败。
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int manifestRequests() {
        return manifestRequests.get();
    }

    int documentRequests() {
        return documentRequests.get();
    }

    void setPack(PromptPackFixtures.Pack pack) {
        this.pack = pack;
    }

    void manifestStatus(int status) {
        this.manifestStatus = status;
    }

    void documentStatus(int status) {
        this.documentStatus = status;
    }

    void manifestBody(String body) {
        this.manifestBody = body;
    }

    void documentBody(String body) {
        this.documentBody = body;
    }

    void failDocument(String key, int status) {
        this.failingKey = key;
        this.failingStatus = status;
    }

    void clearDocumentFailure() {
        this.failingKey = null;
    }

    void tamperText(String key, String text) {
        this.tamperedKey = key;
        this.tamperedText = text;
    }

    void clearTamperedText() {
        this.tamperedKey = null;
        this.tamperedText = null;
    }

    void overrideDocumentSha(String key, String sha) {
        this.shaOverrides = Map.of(key, sha);
    }

    void overrideDocumentVariables(String key, List<String> variables) {
        this.variableOverrides = Map.of(key, List.copyOf(variables));
    }

    void delayMillis(long millis) {
        this.delayMillis = millis;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static String keyFrom(String path) {
        String rest = path.substring(DOCUMENT_PREFIX.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
