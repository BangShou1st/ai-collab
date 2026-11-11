package com.shitulelv.aicollab.common.ai.prompt;

import java.util.List;
import java.util.Objects;

/**
 * 一条已校验的 Prompt 模板（正文尚未渲染）。
 *
 * <p>不可变值对象：{@link #variables()} 由 {@link List#copyOf} 固化，调用方无法通过
 * 修改原集合影响本对象。{@code sha256} / {@code chars} 是资产清单（manifest）声明的
 * 指纹，加载方必须先用正文复算并校验后才能构造本对象；渲染层只信任已校验的正文。</p>
 *
 * @param key       稳定 key，例如 {@code knowledge.system}
 * @param version   {@code YYYY-MM-DD.N} 形式，比较规则见 {@link PromptVersion}
 * @param sha256    正文 UTF-8 字节的 SHA-256（十六进制小写）
 * @param chars     正文的 Unicode 码点数量（不是 {@link String#length()}）
 * @param text      模板正文（含 {@code {{ name }}} 占位符，未渲染）
 * @param variables 模板占位符名字集合，排序去重
 */
public record PromptTemplate(String key, String version, String sha256, int chars,
                             String text, List<String> variables) {

    public PromptTemplate {
        key = Objects.requireNonNull(key, "key");
        version = Objects.requireNonNull(version, "version");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        text = Objects.requireNonNull(text, "text");
        variables = List.copyOf(Objects.requireNonNull(variables, "variables"));
        if (chars < 0) {
            throw new IllegalArgumentException("chars 不能为负数");
        }
    }
}
