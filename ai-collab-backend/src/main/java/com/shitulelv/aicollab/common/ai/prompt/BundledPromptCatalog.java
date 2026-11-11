package com.shitulelv.aicollab.common.ai.prompt;

import com.shitulelv.aicollab.common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * classpath 只读 fallback 目录（{@code prompt.registry.enabled=false}，默认路径）。
 *
 * <p>资产位置固定为 {@code classpath:promptpack/manifest.json} +
 * {@code classpath:promptpack/texts/<key>/<version>.md}，由 Python PromptPack 的
 * {@code export-java} 生成，禁止手改。加载时逐条复算 {@code sha256}（正文 UTF-8 字节）、
 * {@code chars}（Unicode 码点）与 {@code variables}（占位符集合），任一不符即视为
 * <b>该条目</b>损坏；同一 key 有多个版本时按 {@link PromptVersion} 取最大版本。</p>
 *
 * <p>启动语义（对齐交接文档 §7）：类构造<b>绝不抛异常</b>——资源缺失或 manifest 非法
 * 时只记录 {@link #readinessFailure()}，不阻断应用启动；之后每次 {@code require}
 * 都抛 {@link BusinessException}（{@code AI_PROMPT_UNAVAILABLE}）。单条损坏只影响该 key，
 * 其余可用条目照常服务（清单是逐条校验的，不存在"半套新数据"的换入过程）。</p>
 *
 * <p>本类不依赖 Spring、不发起任何网络请求，可由 {@link PromptCatalogs#bundled()}
 * 在无容器场景直接构造（每次调用返回新实例，14 个小文件不值得做静态缓存）。</p>
 */
public final class BundledPromptCatalog implements PromptCatalog {

    static final String SOURCE = "BUNDLED";
    static final String MANIFEST_RESOURCE = "promptpack/manifest.json";
    static final String TEMPLATE_RESOURCE_PREFIX = "promptpack/texts/";

    private static final Logger log = LoggerFactory.getLogger(BundledPromptCatalog.class);

    private final Map<String, PromptTemplate> templates;
    private final Map<String, String> versions;
    private final String fingerprint;
    private final String readinessFailure;
    private final PromptCatalogMetrics metrics;

    /** 生产入口：默认 classpath（线程上下文 classloader 优先）。 */
    public BundledPromptCatalog() {
        this(defaultClassLoader());
    }

    /** 测试入口：从指定 classloader 读取 {@code promptpack/} 资源。 */
    BundledPromptCatalog(ClassLoader classLoader) {
        this(classLoader, Metrics.globalRegistry);
    }

    BundledPromptCatalog(ClassLoader classLoader, MeterRegistry meterRegistry) {
        Objects.requireNonNull(classLoader, "classLoader");
        this.metrics = new PromptCatalogMetrics(meterRegistry);

        Map<String, PromptTemplate> loaded = new LinkedHashMap<>();
        String failure = null;
        try {
            PromptPackJson.Manifest manifest = readManifest(classLoader);
            validateHeader(manifest);
            Map<String, List<PromptPackJson.Entry>> byKey = index(manifest.prompts());
            List<String> problems = new ArrayList<>();
            for (String key : PromptKeys.all()) {
                try {
                    loaded.put(key, loadCurrent(classLoader, key, byKey.get(key)));
                } catch (PromptAssetSupport.AssetException corrupt) {
                    problems.add(corrupt.reason());
                }
            }
            if (!problems.isEmpty()) {
                failure = String.join("; ", problems);
            }
        } catch (PromptAssetSupport.AssetException loadFailure) {
            loaded.clear();
            failure = loadFailure.reason();
        }

        this.templates = Collections.unmodifiableMap(loaded);
        Map<String, String> currentVersions = new LinkedHashMap<>();
        loaded.forEach((key, template) -> currentVersions.put(key, template.version()));
        this.versions = Collections.unmodifiableMap(currentVersions);
        this.fingerprint = PromptAssetSupport.fingerprint(loaded);
        this.readinessFailure = failure;
        this.metrics.entries(SOURCE, this.templates, Map::size);

        if (failure == null) {
            log.info("prompt catalog ready: source={} entries={} fingerprint={}",
                    SOURCE, templates.size(), fingerprint);
        } else {
            // 只打印 key/version 与固定短语；绝不打印 Prompt 正文。
            log.error("prompt catalog not ready: source={} entries={} reason={}",
                    SOURCE, templates.size(), failure);
        }
    }

    @Override
    public PromptTemplate require(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key 不能为空");
        }
        PromptTemplate template = templates.get(key);
        if (template == null) {
            metrics.unavailable(key);
            throw PromptAssetSupport.unavailable();
        }
        return template;
    }

    @Override
    public Map<String, String> versions() {
        return versions;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public String manifestFingerprint() {
        return fingerprint;
    }

    /** 全部条目就绪返回 {@code null}；否则返回不含正文的失败原因（供诊断/健康检查）。 */
    public String readinessFailure() {
        return readinessFailure;
    }

    private static ClassLoader defaultClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : BundledPromptCatalog.class.getClassLoader();
    }

    private static PromptPackJson.Manifest readManifest(ClassLoader classLoader) {
        byte[] bytes;
        try (InputStream input = classLoader.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (input == null) {
                throw new PromptAssetSupport.AssetException("manifest missing");
            }
            bytes = input.readAllBytes();
        } catch (IOException unreadable) {
            throw new PromptAssetSupport.AssetException("manifest unreadable");
        }
        return PromptPackJson.readManifest(bytes);
    }

    private static void validateHeader(PromptPackJson.Manifest manifest) {
        if (manifest == null || manifest.schemaVersion() == null
                || manifest.schemaVersion() != 1 || manifest.prompts() == null) {
            throw new PromptAssetSupport.AssetException("manifest schema invalid");
        }
    }

    private static Map<String, List<PromptPackJson.Entry>> index(List<PromptPackJson.Entry> entries) {
        Map<String, List<PromptPackJson.Entry>> byKey = new HashMap<>();
        for (PromptPackJson.Entry entry : entries) {
            if (entry == null || entry.key() == null) {
                throw new PromptAssetSupport.AssetException("manifest entry invalid");
            }
            byKey.computeIfAbsent(entry.key(), ignored -> new ArrayList<>()).add(entry);
        }
        return byKey;
    }

    private static PromptTemplate loadCurrent(ClassLoader classLoader, String key,
                                              List<PromptPackJson.Entry> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new PromptAssetSupport.AssetException(key + ": missing from manifest");
        }
        Set<String> seenVersions = new HashSet<>();
        for (PromptPackJson.Entry candidate : candidates) {
            if (!key.equals(candidate.key())) {
                throw new PromptAssetSupport.AssetException(key + ": manifest key mismatch");
            }
            if (candidate.version() == null || !seenVersions.add(candidate.version())) {
                throw new PromptAssetSupport.AssetException(key + ": duplicate or missing version");
            }
            if (!PromptVersion.isValid(candidate.version())) {
                throw new PromptAssetSupport.AssetException(key + ": invalid version");
            }
            if (candidate.sha256() == null
                    || !PromptAssetSupport.SHA256.matcher(candidate.sha256()).matches()) {
                throw new PromptAssetSupport.AssetException(key + ": invalid sha256");
            }
            if (candidate.chars() == null || candidate.chars() < 1) {
                throw new PromptAssetSupport.AssetException(key + ": invalid chars");
            }
            if (candidate.variables() == null) {
                throw new PromptAssetSupport.AssetException(key + ": invalid variables");
            }
            for (String variable : candidate.variables()) {
                if (variable == null) {
                    throw new PromptAssetSupport.AssetException(key + ": invalid variables");
                }
            }
        }
        PromptPackJson.Entry current = candidates.stream()
                .max(Comparator.comparing(PromptPackJson.Entry::version, PromptVersion.COMPARATOR))
                .orElseThrow();
        String label = PromptAssetSupport.label(key, current.version());

        byte[] bytes;
        String resource = TEMPLATE_RESOURCE_PREFIX + key + "/" + current.version() + ".md";
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new PromptAssetSupport.AssetException(label + ": template missing");
            }
            bytes = input.readAllBytes();
        } catch (IOException unreadable) {
            throw new PromptAssetSupport.AssetException(label + ": template unreadable");
        }

        String text;
        try {
            text = PromptAssetSupport.decodePromptFile(bytes);
        } catch (PromptAssetSupport.AssetException invalid) {
            throw new PromptAssetSupport.AssetException(label + ": " + invalid.reason());
        }
        PromptAssetSupport.verifyText(key, current.version(), current.sha256(),
                current.chars(), List.copyOf(current.variables()), text);
        return new PromptTemplate(key, current.version(), current.sha256(), current.chars(),
                text, current.variables());
    }
}
