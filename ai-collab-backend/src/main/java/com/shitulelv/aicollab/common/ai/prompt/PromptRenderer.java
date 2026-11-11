package com.shitulelv.aicollab.common.ai.prompt;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 占位符渲染（纯函数，无状态）。
 *
 * <p>冻结规则（交接文档 §3.1）：</p>
 * <ul>
 *   <li>语法 {@code {{ name }}}，允许花括号内空白；</li>
 *   <li>替换 = value <b>原样写入</b>，不 trim、不转义、不改换行；</li>
 *   <li>模板里每个占位符都必须被提供；提供模板里没有的变量 = 错误（防拼写漂移）；</li>
 *   <li>模板字面量中不得残留 {@code {{ name }}} 形态的占位符（不静默留空）。</li>
 * </ul>
 *
 * <p><b>不可信输入边界：</b>残留检查只作用于模板自身的字面量，<b>绝不扫描 value</b>。
 * 知识问答会把用户问题原样注入 value，用户完全可能手打 {@code {{ question }}}；
 * 这属于数据而不是模板结构，必须原样输出，不能报错（否则用户可以稳定制造 500）。
 * 由于模板中每个占位符都已被强制要求提供，真正的"漏替换"在结构上不可能发生，
 * 这里的字面量检查只是防御性不变量。</p>
 *
 * <p>所有失败都是编程错误，抛 {@link IllegalArgumentException}，不属于运行期外部故障，
 * 也不应被翻译成 {@code AI_PROMPT_UNAVAILABLE}。安全转义（如 XML 转义）由调用方在
 * 传入 value 之前完成，模板与渲染器都不做转义。</p>
 */
public final class PromptRenderer {

    private PromptRenderer() {
    }

    /**
     * 用给定变量渲染模板。
     *
     * @throws IllegalArgumentException 缺变量、多变量、变量值为 null，或模板字面量残留占位符
     */
    public static String render(PromptTemplate template, Map<String, String> variables) {
        Objects.requireNonNull(template, "template");
        Map<String, String> values = variables == null ? Map.of() : variables;
        String text = template.text();

        Matcher matcher = PromptAssetSupport.PLACEHOLDER.matcher(text);
        Set<String> required = new LinkedHashSet<>();
        while (matcher.find()) {
            required.add(matcher.group(1));
        }
        for (String name : required) {
            if (!values.containsKey(name)) {
                throw new IllegalArgumentException("缺少模板变量: " + name);
            }
            if (values.get(name) == null) {
                throw new IllegalArgumentException("模板变量值不能为空: " + name);
            }
        }
        for (String name : values.keySet()) {
            if (name == null || !required.contains(name)) {
                throw new IllegalArgumentException("模板未声明的变量: " + name);
            }
        }

        matcher.reset();
        StringBuilder rendered = new StringBuilder(text.length());
        int literalStart = 0;
        while (matcher.find()) {
            String literal = text.substring(literalStart, matcher.start());
            if (PromptAssetSupport.PLACEHOLDER.matcher(literal).find()) {
                throw new IllegalArgumentException("模板中存在未替换的占位符");
            }
            // value 原样写入：不做 trim/转义/换行归一，也不参与占位符扫描。
            rendered.append(literal).append(values.get(matcher.group(1)));
            literalStart = matcher.end();
        }
        String tail = text.substring(literalStart);
        if (PromptAssetSupport.PLACEHOLDER.matcher(tail).find()) {
            throw new IllegalArgumentException("模板中存在未替换的占位符");
        }
        rendered.append(tail);
        return rendered.toString();
    }
}
