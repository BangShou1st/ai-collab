package com.shitulelv.aicollab.agent.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.net.OutboundHttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Java → Python {@code POST /v1/jobs} 派发骨架（W5 冻结 §3.4）。
 *
 * <p><b>本波不接生产触发</b>：只有 {@code memory.job.enabled=true} 才装配；类内没有
 * {@code @Scheduled}、没有队列消费者、没有任何生产入口调用 {@link #dispatch(UUID)}，
 * 仅测试与手动触发可用。</p>
 *
 * <p>Java 决定重试：超时/网关不可用 → {@code FAILED_RETRYABLE}；其它 4xx → {@code FAILED}。
 * Python 不重试、不调度。失败分类只记日志/状态，不把上游响应正文写入异常。
 * 令牌/claims 不打印。</p>
 */
@Component
@ConditionalOnProperty(name = "memory.job.enabled", havingValue = "true")
public class AiJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AiJobDispatcher.class);

    static final String JOBS_PATH = "/v1/jobs";
    private static final int DEADLINE_MS = 60000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(DEADLINE_MS + 5000L);

    private final AiJobService service;
    private final AiJobTokenService tokenService;
    private final ObjectMapper json;
    private final String workerBaseUrl;
    private final HttpClient httpClient;

    public AiJobDispatcher(AiJobService service, AiJobTokenService tokenService,
            ObjectMapper json,
            @Value("${memory.job.worker-base-url:http://ai-worker-api:9100}") String workerBaseUrl) {
        this.service = service;
        this.tokenService = tokenService;
        this.json = json;
        this.workerBaseUrl = normalize(workerBaseUrl);
        this.httpClient = OutboundHttpClients.builder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 租约一个 QUEUED Job 并调用 Python。成功返回后等待 Python 经 results 回报；
     * 调用失败按可重试性落状态，不在本类重试。
     */
    public void dispatch(UUID jobId) {
        AiJobService.Lease lease = service.lease(jobId);
        AiJobService.Snapshot snapshot;
        try {
            snapshot = service.snapshot(jobId, lease.attempt(), lease.leaseEpoch());
        } catch (AiJobException failure) {
            service.fail(jobId, lease.attempt(), lease.leaseEpoch(), false,
                    "SNAPSHOT_UNAVAILABLE");
            throw failure;
        }
        Optional<String> token = tokenService.jobToken(jobId, lease.attempt(),
                lease.leaseEpoch(), lease.job().projectId(), snapshot.snapshotHash());
        if (token.isEmpty()) {
            service.fail(jobId, lease.attempt(), lease.leaseEpoch(), false,
                    "JOB_TOKEN_UNAVAILABLE");
            log.error("AI Job 派发拒绝: 未配置 Ed25519 job token（mode 必须为 ed25519/dual）");
            return;
        }

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request(jobId, token.get()),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            service.fail(jobId, lease.attempt(), lease.leaseEpoch(), true, "DISPATCH_INTERRUPTED");
            return;
        } catch (IOException transportFailure) {
            boolean retryable = !(transportFailure instanceof ConnectException);
            service.fail(jobId, lease.attempt(), lease.leaseEpoch(), retryable,
                    retryable ? "DISPATCH_UNAVAILABLE" : "DISPATCH_CONNECT_FAILED");
            log.warn("AI Job 派发传输失败: retryable={} reason={}", retryable,
                    transportFailure.getClass().getSimpleName());
            return;
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            log.debug("AI Job 派发成功: status={}", status);
            return;
        }
        boolean retryable = status == 408 || status == 429 || status >= 500;
        service.fail(jobId, lease.attempt(), lease.leaseEpoch(), retryable,
                retryable ? "DISPATCH_UNAVAILABLE" : "DISPATCH_REJECTED");
        log.warn("AI Job 派发返回非 2xx: status={} retryable={}", status, retryable);
    }

    private HttpRequest request(UUID jobId, String token) {
        ObjectNode body = json.createObjectNode();
        body.put("schemaVersion", 1);
        body.put("jobId", jobId.toString());
        body.put("type", "MEMORY_EXTRACTION");
        body.put("deadlineMs", DEADLINE_MS);
        return HttpRequest.newBuilder()
                .uri(URI.create(workerBaseUrl + JOBS_PATH))
                .timeout(READ_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private static String normalize(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? "http://ai-worker-api:9100" : value;
    }
}

