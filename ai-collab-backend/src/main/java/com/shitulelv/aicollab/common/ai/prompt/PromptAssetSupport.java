package com.shitulelv.aicollab.common.ai.prompt;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PromptPack 字节口径的单一实现：占位符语法、SHA-256、码点数、文件解码、manifest 指纹。
 *
 * <p>口径来源：{@code ai-collab-ai-worker/src/ai_collab_worker/contract/prompts.py}。
 * 任何一处口径变化都必须两侧同时修改，否则会在跨语言一致性用例上暴露。</p>
 *
 * <p>本类只处理"资产是否可信"，不负责错误对外表达；失败统一抛
 * {@link AssetException}，其 {@link AssetException#reason()} 只含 key/version 与固定
 * 短语，<b>绝不含正文</b>。</p>
 */
final class PromptAssetSupport {

    /** 占位符：{@code {{ name }}}，允许花括号内空白；名字与 Python 契约一致为 ASCII 标识符。 */
    static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\}");

    /** SHA-256 十六进制小写。 */
    static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private PromptAssetSupport() {
    }

    /** 模板中出现的占位符名字，排序去重。 */
    static List<String> extractVariables(String text) {
        Objects.requireNonNull(text, "text");
        TreeSet<String> names = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return List.copyOf(names);
    }

    /** 正文 UTF-8 字节的 SHA-256（十六进制小写）。 */
    static String sha256Hex(String text) {
        Objects.requireNonNull(text, "text");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }

    /** 正文的 Unicode 码点数量（不是 UTF-16 {@code length()}）。 */
    static int codePointCount(String text) {
        Objects.requireNonNull(text, "text");
        return text.codePointCount(0, text.length());
    }

    /**
     * 按冻结口径把 Prompt 文件解码成模板正文：UTF-8 无 BOM、LF、去掉末尾唯一一个换行。
     *
     * <p>契约宽松化（交接文档 §1.1 F2）：正文自身允许以 {@code \n} 结尾，即文件以
     * {@code \n\n} 结尾；这里只去掉最后一个换行，其余原样保留。</p>
     */
    static String decodePromptFile(byte[] bytes) {
        if (bytes == null) {
            throw new AssetException("template missing");
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String raw;
        try {
            raw = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidEncoding) {
            throw new AssetException("template encoding invalid");
        }
        if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
            throw new AssetException("template encoding invalid");
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.endsWith("\n")) {
            throw new AssetException("template must end with a newline");
        }
        String body = normalized.substring(0, normalized.length() - 1);
        if (body.isEmpty()) {
            throw new AssetException("template body empty");
        }
        return body;
    }

    /** 复算正文的三项指纹；任一不符即视为该条目损坏。 */
    static void verifyText(String key, String version, String sha256, int chars,
                           List<String> variables, String text) {
        if (!sha256Hex(text).equals(sha256)) {
            throw new AssetException(label(key, version) + ": sha256 mismatch");
        }
        if (codePointCount(text) != chars) {
            throw new AssetException(label(key, version) + ": chars mismatch");
        }
        if (!extractVariables(text).equals(variables)) {
            throw new AssetException(label(key, version) + ": variables mismatch");
        }
    }

    /**
     * manifest 指纹：对排序后的 {@code key@version:sha256\n} 的 UTF-8 字节取 SHA-256。
     *
     * <p>只覆盖传进来的条目（即当前真正可用的条目），空集合时是空字节的 SHA-256，
     * 因此本地 fallback 与远端缓存可用同一算法互相校验。</p>
     */
    static String fingerprint(Map<String, PromptTemplate> templates) {
        Objects.requireNonNull(templates, "templates");
        StringBuilder payload = new StringBuilder();
        templates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> payload.append(entry.getKey())
                        .append('@')
                        .append(entry.getValue().version())
                        .append(':')
                        .append(entry.getValue().sha256())
                        .append('\n'));
        return sha256Hex(payload.toString());
    }

    static BusinessException unavailable() {
        return new BusinessException(ErrorCode.AI_PROMPT_UNAVAILABLE);
    }

    static String label(String key, String version) {
        return key + "@" + version;
    }

    /** 资产校验失败；{@code reason} 可安全写日志与诊断，不含 Prompt 正文。 */
    static final class AssetException extends RuntimeException {

        private final String reason;

        AssetException(String reason) {
            super(reason);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
