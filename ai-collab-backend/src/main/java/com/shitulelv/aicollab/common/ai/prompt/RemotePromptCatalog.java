package com.shitulelv.aicollab.common.ai.prompt;

import com.shitulelv.aicollab.common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ai-worker 远端 PromptCatalog：启动预取 + 进程内不可变快照 + 单线程周期刷新。
 *
 * <p><b>启动语义：不阻断应用启动。</b>构造时同步尝试一次
 * {@code GET /v1/prompts/manifest} 并预取 {@link PromptKeys#all()} 的当前版本；
 * 任何失败（不可达、超时、5xx、JSON 非法、sha256/chars/variables 不符）都只
 * {@code log.error} + 指标，构造函数照常返回。此后 {@link #require(String)} 在缓存
 * 为空时快速失败 {@code BusinessException(AI_PROMPT_UNAVAILABLE)}。同步预取意味着
 * 最坏启动延迟由配置的 connect/read 超时决定，这是"启动即可用"与"不阻断启动"的
 * 折中（失败不会让主站起不来）。</p>
 *
 * <p><b>缓存语义（设计 §5.3）：</b>一批必须 14 条全部通过校验才整批原子替换；
 * 运行期刷新失败时保留"上次成功版本"并 {@code log.warn} + 指标，绝不把半套新数据
 * 换入，也绝不从半套新数据回退到空。从未成功过时缓存为空，{@code require} 直接失败。
 * 这是设计明文允许的唯一降级例外（避免 Python 侧抖动放大成全站不可用）。</p>
 *
 * <p><b>安全边界：</b>不发任何密钥/Token 头；不写回、不做在线编辑；日志只记录
 * key/version 与固定失败分类，<b>绝不记录 Prompt 正文、变量值或文档响应体</b>。
 * 异常消息也不进日志（响应体片段可能藏在 Jackson 异常消息里）。</p>
 *
 * <p>刷新间隔来自 {@link PromptRegistryProperties#refreshInterval()}；{@code <= 0}
 * 表示只做启动尝试、不周期刷新。调度器由本类自持（单线程 daemon），
 * {@link #destroy()} / {@link #close()} 关闭；{@link #refreshNow()} 供测试与运维手动触发。</p>
 */
public final class RemotePromptCatalog implements PromptCatalog, DisposableBean, AutoCloseable {

    static final String SOURCE = "REMOTE";
    private static final String MANIFEST_PATH = "/v1/prompts/manifest";
    private static final String DOCUMENT_PATH_PREFIX = "/v1/prompts/";

    private static final Logger log = LoggerFactory.getLogger(RemotePromptCatalog.class);

    private final PromptRegistryProperties properties;
    private final RestClient restClient;
    private final PromptCatalogMetrics metrics;
    private final String baseUrl;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());
    private final Object refreshLock = new Object();
    private final ScheduledExecutorService scheduler;
    private volatile String readinessFailure;

    /** 便捷构造：无 Micrometer 容器时退化为 no-op 指标。 */
    public RemotePromptCatalog(PromptRegistryProperties properties, RestClient restClient) {
        this(properties, restClient, Metrics.globalRegistry);
    }

    public RemotePromptCatalog(PromptRegistryProperties properties, RestClient restClient,
                               MeterRegistry meterRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.metrics = new PromptCatalogMetrics(meterRegistry);
        this.baseUrl = normalizeBaseUrl(properties.baseUrl());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());

        metrics.entries(SOURCE, snapshot, state -> state.get().templates().size());
        refresh();
        schedulePeriodicRefresh();
    }

    @Override
    public PromptTemplate require(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key 不能为空");
        }
        PromptTemplate template = snapshot.get().templates().get(key);
        if (template == null) {
            metrics.unavailable(key);
            throw PromptAssetSupport.unavailable();
        }
        return template;
    }

    @Override
    public Map<String, String> versions() {
        return snapshot.get().versions();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public String manifestFingerprint() {
        return snapshot.get().fingerprint();
    }

    /** 最近一次失败的固定分类；当前快照可用但刷新失败时也可能非空（诊断用，不含正文）。 */
    public String readinessFailure() {
        return readinessFailure;
    }

    /** 手动触发一次刷新；失败只记录状态与指标，不向调用方抛异常。 */
    public void refreshNow() {
        refresh();
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
        log.info("prompt catalog scheduler stopped: source={}", SOURCE);
    }

    @Override
    public void close() {
        destroy();
    }

    private void refresh() {
        synchronized (refreshLock) {
            Snapshot previous = snapshot.get();
            try {
                Snapshot next = loadSnapshot();
                snapshot.set(next);
                readinessFailure = null;
                metrics.refresh(true);
                if (previous.templates().isEmpty()) {
                    log.info("prompt catalog ready: source={} entries={} fingerprint={}",
                            SOURCE, next.templates().size(), next.fingerprint());
                } else {
                    log.debug("prompt catalog refreshed: source={} entries={} fingerprint={}",
                            SOURCE, next.templates().size(), next.fingerprint());
                }
            } catch (RuntimeException failure) {
                String reason = reasonOf(failure);
                readinessFailure = reason;
                metrics.refresh(false);
                int kept = snapshot.get().templates().size();
                if (kept == 0) {
                    log.error("prompt catalog unavailable: source={} reason={}", SOURCE, reason);
                } else {
                    log.warn("prompt catalog refresh failed, keeping last successful snapshot: "
                            + "source={} entries={} reason={}", SOURCE, kept, reason);
                }
            }
        }
    }

    private Snapshot loadSnapshot() {
        PromptPackJson.Manifest manifest = fetchManifest();
        Map<String, List<PromptPackJson.Entry>> byKey = indexAndValidate(manifest);
        Map<String, PromptTemplate> templates = new LinkedHashMap<>();
        for (String key : PromptKeys.all()) {
            PromptPackJson.Entry entry = selectCurrent(key, byKey.get(key));
            PromptPackJson.Document document = fetchDocument(key, entry.version());
            templates.put(key, verifyAndBuild(entry, document));
        }
        return Snapshot.of(templates);
    }

    private PromptPackJson.Manifest fetchManifest() {
        try {
            PromptPackJson.Manifest manifest = restClient.get()
                    .uri(baseUrl + MANIFEST_PATH)
                    .retrieve()
                    .body(PromptPackJson.Manifest.class);
            if (manifest == null) {
                throw new PromptAssetSupport.AssetException("manifest empty response");
            }
            return manifest;
        } catch (PromptAssetSupport.AssetException invalid) {
            throw invalid;
        } catch (RestClientResponseException http) {
            throw new PromptAssetSupport.AssetException("manifest http " + http.getStatusCode().value());
        } catch (ResourceAccessException unreachable) {
            throw new PromptAssetSupport.AssetException(
                    isTimeout(unreachable) ? "manifest worker timeout" : "manifest worker unreachable");
        } catch (RestClientException invalid) {
            throw new PromptAssetSupport.AssetException("manifest response invalid");
        } catch (RuntimeException unexpected) {
            throw new PromptAssetSupport.AssetException("manifest request failed");
        }
    }

    private PromptPackJson.Document fetchDocument(String key, String version) {
        String label = PromptAssetSupport.label(key, version);
        try {
            PromptPackJson.Document document = restClient.get()
                    .uri(baseUrl + DOCUMENT_PATH_PREFIX + key + "/" + version)
                    .retrieve()
                    .body(PromptPackJson.Document.class);
            if (document == null) {
                throw new PromptAssetSupport.AssetException(label + ": empty response");
            }
            return document;
        } catch (PromptAssetSupport.AssetException invalid) {
            throw invalid;
        } catch (RestClientResponseException http) {
            throw new PromptAssetSupport.AssetException(
                    label + ": document http " + http.getStatusCode().value());
        } catch (ResourceAccessException unreachable) {
            throw new PromptAssetSupport.AssetException(label + ": "
                    + (isTimeout(unreachable) ? "document worker timeout" : "document worker unreachable"));
        } catch (RestClientException invalid) {
            throw new PromptAssetSupport.AssetException(label + ": document response invalid");
        } catch (RuntimeException unexpected) {
            throw new PromptAssetSupport.AssetException(label + ": document request failed");
        }
    }

    private static Map<String, List<PromptPackJson.Entry>> indexAndValidate(
            PromptPackJson.Manifest manifest) {
        if (manifest.schemaVersion() == null || manifest.schemaVersion() != 1) {
            throw new PromptAssetSupport.AssetException("manifest schemaVersion mismatch");
        }
        if (manifest.prompts() == null) {
            throw new PromptAssetSupport.AssetException("manifest schema invalid");
        }
        Map<String, List<PromptPackJson.Entry>> byKey = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (PromptPackJson.Entry entry : manifest.prompts()) {
            if (entry == null || entry.key() == null || entry.key().isBlank()) {
                throw new PromptAssetSupport.AssetException("manifest entry invalid");
            }
            String label = PromptAssetSupport.label(entry.key(), String.valueOf(entry.version()));
            if (!PromptVersion.isValid(entry.version())) {
                throw new PromptAssetSupport.AssetException(label + ": invalid version");
            }
            if (!seen.add(label)) {
                throw new PromptAssetSupport.AssetException(label + ": duplicate entry");
            }
            if (entry.sha256() == null
                    || !PromptAssetSupport.SHA256.matcher(entry.sha256()).matches()) {
                throw new PromptAssetSupport.AssetException(label + ": invalid sha256");
            }
            if (entry.chars() == null || entry.chars() < 1) {
                throw new PromptAssetSupport.AssetException(label + ": invalid chars");
            }
            byKey.computeIfAbsent(entry.key(), ignored -> new ArrayList<>()).add(entry);
        }
        return byKey;
    }

    private static PromptPackJson.Entry selectCurrent(String key, List<PromptPackJson.Entry> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new PromptAssetSupport.AssetException(key + ": missing from manifest");
        }
        return candidates.stream()
                .max(Comparator.comparing(PromptPackJson.Entry::version, PromptVersion.COMPARATOR))
                .orElseThrow();
    }

    private static PromptTemplate verifyAndBuild(PromptPackJson.Entry entry,
                                                 PromptPackJson.Document document) {
        String key = entry.key();
        String version = entry.version();
        String label = PromptAssetSupport.label(key, version);
        if (document.schemaVersion() != null && document.schemaVersion() != 1) {
            throw new PromptAssetSupport.AssetException(label + ": document schemaVersion mismatch");
        }
        if (!key.equals(document.key()) || !version.equals(document.version())) {
            throw new PromptAssetSupport.AssetException(label + ": document identity mismatch");
        }
        if (document.text() == null || document.text().isEmpty()) {
            throw new PromptAssetSupport.AssetException(label + ": document text empty");
        }
        if (document.sha256() == null) {
            throw new PromptAssetSupport.AssetException(label + ": document sha256 missing");
        }
        String computed = PromptAssetSupport.sha256Hex(document.text());
        if (!computed.equals(entry.sha256())) {
            throw new PromptAssetSupport.AssetException(label + ": sha256 mismatch with manifest");
        }
        if (!computed.equals(document.sha256())) {
            throw new PromptAssetSupport.AssetException(label + ": sha256 mismatch with document");
        }
        if (PromptAssetSupport.codePointCount(document.text()) != entry.chars()) {
            throw new PromptAssetSupport.AssetException(label + ": chars mismatch");
        }
        List<String> actualVariables = PromptAssetSupport.extractVariables(document.text());
        if (document.variables() == null || !actualVariables.equals(document.variables())) {
            throw new PromptAssetSupport.AssetException(label + ": variables mismatch");
        }
        return new PromptTemplate(key, version, entry.sha256(), entry.chars(),
                document.text(), document.variables());
    }

    private void schedulePeriodicRefresh() {
        Duration interval = properties.refreshInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            log.info("prompt catalog periodic refresh disabled: source={} interval={}", SOURCE, interval);
            return;
        }
        long millis;
        try {
            millis = Math.max(1L, interval.toMillis());
        } catch (ArithmeticException tooLarge) {
            millis = Long.MAX_VALUE;
        }
        scheduler.scheduleWithFixedDelay(this::refresh, millis, millis, TimeUnit.MILLISECONDS);
        log.info("prompt catalog periodic refresh scheduled: source={} interval={}", SOURCE, interval);
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static String reasonOf(RuntimeException failure) {
        if (failure instanceof PromptAssetSupport.AssetException asset) {
            return asset.reason();
        }
        return "unexpected failure";
    }

    private static String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("prompt.registry.base-url 不能为空");
        }
        return value;
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "prompt-catalog-refresh");
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 一次成功批次的不可变快照；失败批次不会被构造，因此不存在半套数据。 */
    private record Snapshot(Map<String, PromptTemplate> templates,
                            Map<String, String> versions,
                            String fingerprint) {

        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), PromptAssetSupport.fingerprint(Map.of()));
        }

        static Snapshot of(Map<String, PromptTemplate> templates) {
            Map<String, String> versions = new LinkedHashMap<>();
            templates.forEach((key, template) -> versions.put(key, template.version()));
            return new Snapshot(Map.copyOf(templates), Map.copyOf(versions),
                    PromptAssetSupport.fingerprint(templates));
        }
    }
}
