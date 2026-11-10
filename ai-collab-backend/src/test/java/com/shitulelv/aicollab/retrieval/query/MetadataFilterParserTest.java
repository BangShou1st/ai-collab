package com.shitulelv.aicollab.retrieval.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataFilterParserTest {

    private MetadataFilterParser parser;

    @BeforeEach
    void setUp() {
        parser = new MetadataFilterParser();
    }

    @Test
    @DisplayName("解析自然语言中的书名号文件名")
    void shouldParseBookTitleFromNaturalQuery() {
        String query = "在《架构设计文档.md》中 核心网关的配置参数有哪些？";
        StructuredQuery structured = parser.parse(query);

        assertThat(structured.hasFilters()).isTrue();
        assertThat(structured.filters()).hasSize(1);

        MetadataFilter filter = structured.filters().get(0);
        assertThat(filter.field()).isEqualTo("originalFilename");
        assertThat(filter.operator()).isEqualTo(FilterOperator.CONTAINS);
        assertThat(filter.value()).isEqualTo("架构设计文档.md");

        assertThat(structured.semanticQuery()).isEqualTo("核心网关的配置参数有哪些？");
    }

    @Test
    @DisplayName("解析自然语言中的文件类型 mentions")
    void shouldParseMimeTypeMentions() {
        String query = "查找 PDF 格式的项目中期总结报告";
        StructuredQuery structured = parser.parse(query);

        assertThat(structured.hasFilters()).isTrue();
        MetadataFilter filter = structured.filters().get(0);
        assertThat(filter.field()).isEqualTo("mimeType");
        assertThat(filter.operator()).isEqualTo(FilterOperator.EQ);
        assertThat(filter.value()).isEqualTo("application/pdf");

        assertThat(structured.semanticQuery()).isEqualTo("查找 项目中期总结报告");
    }

    @Test
    @DisplayName("解析显式 key:value 过滤语法")
    void shouldParseExplicitKeyValueSyntax() {
        String query = "file:deployment.yaml status:READY type:md 如何配置端口";
        StructuredQuery structured = parser.parse(query);

        assertThat(structured.hasFilters()).isTrue();
        assertThat(structured.filters()).hasSize(3);

        List<MetadataFilter> fileFilters = structured.getFiltersForField("originalFilename");
        assertThat(fileFilters).hasSize(1);
        assertThat(fileFilters.get(0).value()).isEqualTo("deployment.yaml");

        List<MetadataFilter> statusFilters = structured.getFiltersForField("status");
        assertThat(statusFilters).hasSize(1);
        assertThat(statusFilters.get(0).value()).isEqualTo("READY");

        List<MetadataFilter> typeFilters = structured.getFiltersForField("mimeType");
        assertThat(typeFilters).hasSize(1);
        assertThat(typeFilters.get(0).value()).isEqualTo("text/markdown");

        assertThat(structured.semanticQuery()).isEqualTo("如何配置端口");
    }

    @Test
    @DisplayName("解析显式时间范围语法 since/after")
    void shouldParseTimeRangeFilters() {
        String query = "since:2026-10-01 系统的更新说明";
        StructuredQuery structured = parser.parse(query);

        assertThat(structured.hasFilters()).isTrue();
        MetadataFilter filter = structured.filters().get(0);
        assertThat(filter.field()).isEqualTo("createdAt");
        assertThat(filter.operator()).isEqualTo(FilterOperator.GTE);
        assertThat(filter.value()).isEqualTo("2026-10-01");

        assertThat(structured.semanticQuery()).isEqualTo("系统的更新说明");
    }

    @Test
    @DisplayName("遇到未知/非白名单字段显式语法时必须拒绝，严禁静默扩大搜索范围")
    void shouldRejectIllegalFieldInQuery() {
        String maliciousQuery = "table:users author:alice SELECT * FROM user";

        assertThatThrownBy(() -> parser.parse(maliciousQuery))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Disallowed metadata filter field");
    }

    @Test
    @DisplayName("解析结构化参数 Map，严格校验字段白名单")
    void shouldParseStructuredMapWithStrictValidation() {
        Map<String, Object> validMap = Map.of(
                "originalFilename", "test.pdf",
                "status", "READY"
        );

        List<MetadataFilter> filters = parser.parseStructuredMap(validMap);
        assertThat(filters).hasSize(2);

        Map<String, Object> maliciousMap = Map.of(
                "status", "READY",
                "injection_field", "value"
        );

        assertThatThrownBy(() -> parser.parseStructuredMap(maliciousMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Disallowed metadata filter field in map");
    }

    @Test
    @DisplayName("解析结构化参数列表 parseStructuredFilters")
    void shouldParseStructuredFilterList() {
        List<Map<String, Object>> specs = List.of(
                Map.of("field", "status", "operator", "EQ", "value", "READY"),
                Map.of("field", "tags", "operator", "CONTAINS", "value", "release")
        );

        List<MetadataFilter> filters = parser.parseStructuredFilters(specs);
        assertThat(filters).hasSize(2);

        List<Map<String, Object>> invalidSpecs = List.of(
                Map.of("field", "unsupported_field", "value", "abc")
        );

        assertThatThrownBy(() -> parser.parseStructuredFilters(invalidSpecs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Disallowed metadata filter field");
    }
}
