package com.shitulelv.aicollab.common.ai.prompt;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 占位符渲染冻结规则（§3.1）：原样写入、变量集合严格匹配、value 不参与占位符扫描。 */
class PromptRendererTest {

    private static PromptTemplate template(String text, String... variables) {
        return new PromptTemplate("test.key", "2026-11-11.1", "0".repeat(64), 0, text,
                List.of(variables));
    }

    @Test
    void replacesSingleVariable() {
        PromptTemplate template = template("你好，{{ name }}！", "name");

        assertThat(PromptRenderer.render(template, Map.of("name", "世界")))
                .isEqualTo("你好，世界！");
    }

    @Test
    void replacesMultipleVariables_withChineseAndEnglish() {
        PromptTemplate template = template(
                "<QUESTION>\n{{ question }}\n</QUESTION>\n\n<SOURCES>\n{{ sources }}\n</SOURCES>",
                "question", "sources");

        String rendered = PromptRenderer.render(template, Map.of(
                "question", "如何安排迭代？",
                "sources", "[文档A] 迭代计划应当覆盖风险。"));

        assertThat(rendered).isEqualTo(
                "<QUESTION>\n如何安排迭代？\n</QUESTION>\n\n<SOURCES>\n[文档A] 迭代计划应当覆盖风险。\n</SOURCES>");
    }

    @Test
    void valueIsWrittenVerbatim_withNewlinesBracesAndNoTrimming() {
        PromptTemplate template = template("前缀\n{{ body }}\n后缀", "body");
        String value = "  第一行\n第二行 {{ 未闭合\n第三行  ";

        assertThat(PromptRenderer.render(template, Map.of("body", value)))
                .isEqualTo("前缀\n" + value + "\n后缀");
    }

    @Test
    void untrustedValueContainingPlaceholderForms_isDataNotTemplate() {
        // 用户完全可以在问题里手打 {{ question }}；必须原样输出，不得报错（否则可被用来制造 500）。
        PromptTemplate template = template("Q={{ question }}", "question");

        assertThat(PromptRenderer.render(template,
                Map.of("question", "用户写了 {{ question }} 和 {{ sources }}")))
                .isEqualTo("Q=用户写了 {{ question }} 和 {{ sources }}");
    }

    @Test
    void regexAndDollarMetacharactersInValueAreNotInterpreted() {
        PromptTemplate template = template("A={{ x }}B", "x");

        assertThat(PromptRenderer.render(template, Map.of("x", "$1 \\ {{ y }} $2")))
                .isEqualTo("A=$1 \\ {{ y }} $2B");
    }

    @Test
    void missingVariableFails() {
        PromptTemplate template = template("{{ a }}{{ b }}", "a", "b");

        assertThatThrownBy(() -> PromptRenderer.render(template, Map.of("a", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少模板变量")
                .hasMessageContaining("b");
    }

    @Test
    void extraVariableFails() {
        PromptTemplate template = template("{{ a }}", "a");

        assertThatThrownBy(() -> PromptRenderer.render(template, Map.of("a", "1", "typo", "2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模板未声明的变量")
                .hasMessageContaining("typo");
    }

    @Test
    void nullValueFails() {
        PromptTemplate template = template("{{ a }}", "a");
        Map<String, String> values = new HashMap<>();
        values.put("a", null);

        assertThatThrownBy(() -> PromptRenderer.render(template, values))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void emptyMapOnTemplateWithoutPlaceholders_returnsTextUnchanged() {
        PromptTemplate template = template("没有占位符的正文\n第二行");

        assertThat(PromptRenderer.render(template, Map.of())).isEqualTo(template.text());
    }

    @Test
    void emptyMapOnTemplateWithPlaceholders_fails() {
        PromptTemplate template = template("{{ a }}", "a");

        assertThatThrownBy(() -> PromptRenderer.render(template, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少模板变量");
    }

    @Test
    void bracesInsideValueDoNotChangeTemplateStructure() {
        // 模板里只有一个占位符；value 里的同名形态也不应影响"模板已全部替换"的事实。
        PromptTemplate template = template("{{ a }}", "a");

        assertThat(PromptRenderer.render(template, Map.of("a", "{{ a }}")))
                .isEqualTo("{{ a }}");
    }
}
