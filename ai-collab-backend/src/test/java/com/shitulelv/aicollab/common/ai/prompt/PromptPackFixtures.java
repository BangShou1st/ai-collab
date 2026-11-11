package com.shitulelv.aicollab.common.ai.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试夹具：独立于生产实现重建 PromptPack（直接读 classpath 资源 + 自己算 sha/chars），
 * 用于构造远端 HTTP 响应。刻意不复用 {@code BundledPromptCatalog}，避免"用被测代码
 * 生成期望值"的循环论证。
 */
final class PromptPackFixtures {

    static final String BASE_URL = "http://ai-worker.test";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MANIFEST_RESOURCE = "promptpack/manifest.json";
    private static final String TEMPLATE_PREFIX = "promptpack/texts/";

    private PromptPackFixtures() {
    }

    record Entry(String key, String version, String sha256, int chars, List<String> variables) {
    }

    record Pack(Map<String, Entry> entries, Map<String, String> texts) {
    }

    /** 用真实 classpath 资产构造一份 Pack（manifest 声明的 sha/chars/variables + 文件正文）。 */
    static Pack realPack() {
        try (InputStream manifestStream = PromptPackFixtures.class.getClassLoader()
                .getResourceAsStream(MANIFEST_RESOURCE)) {
            if (manifestStream == null) {
                throw new IllegalStateException("缺少测试资源 " + MANIFEST_RESOURCE);
            }
            JsonNode root = MAPPER.readTree(manifestStream.readAllBytes());
            Map<String, List<Entry>> candidates = new LinkedHashMap<>();
            for (JsonNode node : root.path("prompts")) {
                String key = node.path("key").asText();
                String version = node.path("version").asText();
                String sha = node.path("sha256").asText();
                int chars = node.path("chars").asInt();
                List<String> variables = new ArrayList<>();
                node.path("variables").forEach(variable -> variables.add(variable.asText()));
                candidates.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new Entry(key, version, sha, chars, List.copyOf(variables)));
            }
            Map<String, Entry> entries = new LinkedHashMap<>();
            Map<String, String> texts = new LinkedHashMap<>();
            for (Map.Entry<String, List<Entry>> group : candidates.entrySet()) {
                Entry current = group.getValue().stream()
                        .max(Comparator.comparing(Entry::version, PromptVersion.COMPARATOR))
                        .orElseThrow();
                String text = readTemplate(current.key(), current.version());
                if (!sha256(text).equals(current.sha256())) {
                    throw new IllegalStateException("夹具与 manifest sha256 不一致: " + current.key());
                }
                if (chars(text) != current.chars()) {
                    throw new IllegalStateException("夹具与 manifest chars 不一致: " + current.key());
                }
                if (!variables(text).equals(current.variables())) {
                    throw new IllegalStateException("夹具与 manifest variables 不一致: " + current.key());
                }
                entries.put(current.key(), current);
                texts.put(current.key(), text);
            }
            return new Pack(Collections.unmodifiableMap(entries), Collections.unmodifiableMap(texts));
        } catch (IOException failure) {
            throw new IllegalStateException("读取真实 PromptPack 失败", failure);
        }
    }

    /** 一批 14 条人工正文；revision 变化时版本与正文一起变化，便于断言整批替换。 */
    static Pack syntheticPack(String revision) {
        String version = "2026-11-11." + revision;
        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, String> texts = new LinkedHashMap<>();
        for (String key : PromptKeys.all()) {
            String text = "PACK-" + revision + " " + key + "\n中文第二行";
            entries.put(key, new Entry(key, version, sha256(text), chars(text), List.of()));
            texts.put(key, text);
        }
        return new Pack(Collections.unmodifiableMap(entries), Collections.unmodifiableMap(texts));
    }

    /** 替换一条正文并重算指纹（保持 manifest/文档自洽，用于验证"新批次内容"本身）。 */
    static Pack withText(Pack base, String key, String text, List<String> variables) {
        Map<String, Entry> entries = new LinkedHashMap<>(base.entries());
        Map<String, String> texts = new LinkedHashMap<>(base.texts());
        Entry previous = entries.get(key);
        entries.put(key, new Entry(key, previous.version(), sha256(text), chars(text),
                List.copyOf(variables)));
        texts.put(key, text);
        return new Pack(Collections.unmodifiableMap(entries), Collections.unmodifiableMap(texts));
    }

    /** 只改 manifest 的 chars（正文与 sha 都不变），用于隔离 chars 校验。 */
    static Pack withChars(Pack base, String key, int chars) {
        Map<String, Entry> entries = new LinkedHashMap<>(base.entries());
        Entry previous = entries.get(key);
        entries.put(key, new Entry(key, previous.version(), previous.sha256(), chars,
                previous.variables()));
        return new Pack(Collections.unmodifiableMap(entries), Collections.unmodifiableMap(base.texts()));
    }

    static String manifestJson(Pack pack) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", 1);
        ArrayNode prompts = root.putArray("prompts");
        pack.entries().values().stream()
                .sorted(Comparator.comparing(Entry::key))
                .forEach(entry -> {
                    ObjectNode node = prompts.addObject();
                    node.put("key", entry.key());
                    node.put("version", entry.version());
                    node.put("sha256", entry.sha256());
                    node.put("chars", entry.chars());
                });
        return root.toString();
    }

    static String documentJson(Pack pack, String key) {
        Entry entry = pack.entries().get(key);
        return documentJson(entry, pack.texts().get(key), entry.sha256(), entry.variables());
    }

    static String documentJson(Entry entry, String text, String sha256, List<String> variables) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("schemaVersion", 1);
        node.put("key", entry.key());
        node.put("version", entry.version());
        node.put("sha256", sha256);
        node.put("text", text);
        ArrayNode vars = node.putArray("variables");
        variables.forEach(vars::add);
        return node.toString();
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }    static int chars(String text) {
        return text.codePointCount(0, text.length());
    }

    /** 独立复算 manifest 指纹：排序后 {@code key@version:sha256\n} 的 SHA-256。 */
    static String fingerprint(Pack pack) {
        StringBuilder payload = new StringBuilder();
        pack.entries().keySet().stream().sorted()
                .forEach(key -> {
                    Entry entry = pack.entries().get(key);
                    payload.append(entry.key()).append('@').append(entry.version())
                            .append(':').append(entry.sha256()).append('\n');
                });
        return sha256(payload.toString());
    }

    static List<String> variables(String text) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        java.util.regex.Matcher matcher = PromptAssetSupport.PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return List.copyOf(names);
    }

    /**
     * 把真实 classpath PromptPack 逐字节复制到临时 classpath 根目录（保留 \n\n 结尾），
     * 供"故意破坏一条资产"的测试使用。
     */
    static void copyRealPackTo(java.nio.file.Path classpathRoot) throws IOException {
        java.nio.file.Path packRoot = classpathRoot.resolve("promptpack");
        java.nio.file.Files.createDirectories(packRoot.resolve("texts"));
        try (InputStream manifest = PromptPackFixtures.class.getClassLoader()
                .getResourceAsStream(MANIFEST_RESOURCE)) {
            if (manifest == null) {
                throw new IOException("缺少测试资源 " + MANIFEST_RESOURCE);
            }
            java.nio.file.Files.write(packRoot.resolve("manifest.json"), manifest.readAllBytes());
        }
        Pack real = realPack();
        for (String key : PromptKeys.all()) {
            Entry entry = real.entries().get(key);
            java.nio.file.Path target = packRoot.resolve("texts")
                    .resolve(key).resolve(entry.version() + ".md");
            java.nio.file.Files.createDirectories(target.getParent());
            try (InputStream template = PromptPackFixtures.class.getClassLoader()
                    .getResourceAsStream(TEMPLATE_PREFIX + key + "/" + entry.version() + ".md")) {
                if (template == null) {
                    throw new IOException("缺少测试资源 " + key + "@" + entry.version());
                }
                java.nio.file.Files.write(target, template.readAllBytes());
            }
        }
    }

    /** 用临时目录构造隔离 classloader（parent=null，只看到被复制的资源）。 */
    static java.net.URLClassLoader classLoaderFor(java.nio.file.Path classpathRoot)
            throws IOException {
        return new java.net.URLClassLoader(
                new java.net.URL[]{classpathRoot.toUri().toURL()}, null);
    }

    private static String readTemplate(String key, String version) throws IOException {
        String resource = TEMPLATE_PREFIX + key + "/" + version + ".md";
        try (InputStream input = PromptPackFixtures.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("缺少测试资源 " + resource);
            }
            String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (!raw.endsWith("\n")) {
                throw new IOException("Prompt 文件必须以换行结尾: " + resource);
            }
            return raw.substring(0, raw.length() - 1);
        }
    }
}
