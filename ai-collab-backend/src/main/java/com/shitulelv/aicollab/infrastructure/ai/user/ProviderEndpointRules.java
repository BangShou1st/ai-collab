package com.shitulelv.aicollab.infrastructure.ai.user;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Base URL 与 API Path 的组合规则：重复版本前缀检测与端点预览。
 *
 * <p>历史问题：用户填写的 Base URL 常包含 /v1，而表单默认 API Path 又以 /v1 开头，
 * 模型发现请求被拼成 /v1/v1/models。此处在发起任何网络请求之前识别这类拼接错误。</p>
 *
 * <p>刻意保守：只识别「Base URL 末段与 API Path 首段完全相同的版本段」
 * （v1、v2、v1beta、v1alpha 等 {@code v<数字>[后缀]} 形态），不做任何通用
 * 路径去重——自定义网关的特殊路径与 Gemini /v1beta、Anthropic /v1 等合法
 * 协议路径不受影响（它们的 Base URL 不含版本段，不构成拼接重复）。</p>
 */
public final class ProviderEndpointRules {
    private ProviderEndpointRules() {
    }

    /** 版本段形态：v1、v2、v1beta、v1alpha2 等。 */
    private static final Pattern VERSION_SEGMENT = Pattern.compile("^v\\d+[a-z0-9]*$");

    /**
     * 检测 Base URL 与 API Path 拼接处的重复版本前缀。
     *
     * @return 重复的版本段（如 {@code v1}）；无重复返回空
     */
    public static Optional<String> duplicateVersionJunction(String baseUrl, String apiPath) {
        String base = baseUrl == null ? "" : baseUrl.strip();
        String path = apiPath == null ? "" : apiPath.strip();
        if (base.isEmpty() || path.isEmpty() || !path.startsWith("/")) return Optional.empty();
        URI baseUri;
        try {
            baseUri = URI.create(base);
        } catch (RuntimeException malformed) {
            return Optional.empty(); // Base URL 文法校验由保存校验负责，这里不重复报错
        }
        List<String> baseSegments = segments(baseUri.getRawPath());
        if (baseSegments.isEmpty()) return Optional.empty();
        String lastBaseSegment = baseSegments.getLast();
        String firstPathSegment = path.substring(1).split("/", 2)[0];
        if (firstPathSegment.isEmpty()) return Optional.empty();
        if (VERSION_SEGMENT.matcher(lastBaseSegment).matches()
                && lastBaseSegment.equals(firstPathSegment)) {
            return Optional.of(lastBaseSegment);
        }
        return Optional.empty();
    }

    /** 建议修正后的 API Path：去掉与 Base URL 末段重复的版本前缀。 */
    public static Optional<String> stripDuplicateFromPath(String baseUrl, String apiPath) {
        return duplicateVersionJunction(baseUrl, apiPath)
                .map(dup -> apiPath.strip().substring(1 + dup.length()));
    }

    /** 建议修正后的 Base URL：去掉末尾的重复版本段（保留结尾斜杠风格不变）。 */
    public static Optional<String> stripDuplicateFromBase(String baseUrl, String apiPath) {
        return duplicateVersionJunction(baseUrl, apiPath)
                .map(dup -> baseUrl.strip().replaceAll("/" + Pattern.quote(dup) + "/?$", "/"));
    }

    /** 推理端点预览（Base + Path 直接拼接，供发起请求前展示）。 */
    public static String inferenceUrlPreview(String baseUrl, String apiPath) {
        String base = (baseUrl == null ? "" : baseUrl.strip());
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String path = (apiPath == null ? "" : apiPath.strip());
        if (path.isEmpty()) return base;
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    private static List<String> segments(String rawPath) {
        List<String> out = new ArrayList<>();
        if (rawPath == null) return out;
        for (String segment : rawPath.split("/")) {
            if (!segment.isBlank()) out.add(segment);
        }
        return out;
    }
}
