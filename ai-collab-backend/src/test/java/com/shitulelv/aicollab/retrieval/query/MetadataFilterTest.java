package com.shitulelv.aicollab.retrieval.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataFilterTest {

    @Test
    @DisplayName("应该允许白名单字段创建过滤条件")
    void shouldAllowWhitelistedFields() {
        for (String field : MetadataFilter.ALLOWED_FIELDS) {
            MetadataFilter filter = switch (field) {
                case "createdAt", "updatedAt" -> MetadataFilter.gte(field, "2026-10-01");
                case "tags" -> MetadataFilter.contains(field, "backend");
                default -> MetadataFilter.eq(field, "test-value");
            };
            assertThat(filter.field()).isEqualTo(field);
        }
    }

    @Test
    @DisplayName("应该严格拒绝非白名单字段，防止任意 SQL 注入")
    void shouldRejectNonWhitelistedFields() {
        List<String> illegalFields = List.of(
                "password",
                "secret_key",
                "table_name",
                "drop_table",
                "user_id; DELETE FROM users; --",
                "customProperty"
        );

        for (String illegal : illegalFields) {
            assertThatThrownBy(() -> MetadataFilter.eq(illegal, "value"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Illegal metadata filter field");
        }
    }

    @Test
    @DisplayName("应该拒绝不支持的操作符")
    void shouldRejectUnsupportedOperators() {
        // mimeType 不支持 GT 操作符
        assertThatThrownBy(() -> new MetadataFilter("mimeType", FilterOperator.GT, "pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not supported for field");

        // status 不支持 CONTAINS 操作符
        assertThatThrownBy(() -> new MetadataFilter("status", FilterOperator.CONTAINS, "READY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not supported for field");
    }

    @Test
    @DisplayName("应该拒绝空值与含有空字节的非法值")
    void shouldRejectInvalidValues() {
        assertThatThrownBy(() -> MetadataFilter.eq("originalFilename", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> MetadataFilter.eq("originalFilename", "   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> MetadataFilter.eq("originalFilename", "doc\0.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains null byte");

        assertThatThrownBy(() -> MetadataFilter.in("status", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be empty");
    }

    @Test
    @DisplayName("内存匹配：EQ, NEQ, CONTAINS, IN 操作符应正确生效")
    void shouldMatchMemoryMetadataCorrectly() {
        MetadataFilter eqFilter = MetadataFilter.eq("status", "READY");
        MetadataFilter neqFilter = MetadataFilter.neq("status", "FAILED");
        MetadataFilter containsFilter = MetadataFilter.contains("originalFilename", "report");
        MetadataFilter inFilter = MetadataFilter.in("status", List.of("READY", "INDEXING"));

        Map<String, Object> meta = Map.of(
                "status", "READY",
                "originalFilename", "annual_report_2026.pdf"
        );

        assertThat(eqFilter.matches(meta)).isTrue();
        assertThat(neqFilter.matches(meta)).isTrue();
        assertThat(containsFilter.matches(meta)).isTrue();
        assertThat(inFilter.matches(meta)).isTrue();

        Map<String, Object> nonMatching = Map.of(
                "status", "FAILED",
                "originalFilename", "spec.docx"
        );

        assertThat(eqFilter.matches(nonMatching)).isFalse();
        assertThat(neqFilter.matches(nonMatching)).isFalse();
        assertThat(containsFilter.matches(nonMatching)).isFalse();
        assertThat(inFilter.matches(nonMatching)).isFalse();
    }

    @Test
    @DisplayName("内存匹配：时间比较 (GTE, LTE) 支持 ISO 字符串与时间对象")
    void shouldMatchTemporalFilters() {
        OffsetDateTime now = OffsetDateTime.now();
        MetadataFilter gteFilter = MetadataFilter.gte("createdAt", now.minusDays(1).toString());
        MetadataFilter lteFilter = MetadataFilter.lte("createdAt", now.plusDays(1).toString());

        Map<String, Object> meta = Map.of("createdAt", now.toString());

        assertThat(gteFilter.matches(meta)).isTrue();
        assertThat(lteFilter.matches(meta)).isTrue();

        MetadataFilter futureFilter = MetadataFilter.gte("createdAt", now.plusDays(2).toString());
        assertThat(futureFilter.matches(meta)).isFalse();
    }

    @Test
    @DisplayName("内存匹配：sectionHeading 应该支持 heading 字段别名")
    void shouldMatchSectionHeadingWithHeadingAlias() {
        MetadataFilter filter = MetadataFilter.contains("sectionHeading", "鉴权设计");

        Map<String, Object> metaWithHeading = Map.of("heading", "第三章 鉴权设计与安全模型");
        assertThat(filter.matches(metaWithHeading)).isTrue();

        Map<String, Object> metaWithSectionHeading = Map.of("sectionHeading", "鉴权设计");
        assertThat(filter.matches(metaWithSectionHeading)).isTrue();
    }
}
