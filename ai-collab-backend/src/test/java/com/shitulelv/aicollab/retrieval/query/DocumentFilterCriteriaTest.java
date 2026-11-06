package com.shitulelv.aicollab.retrieval.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentFilterCriteriaTest {

    @Test
    @DisplayName("空过滤器列表应解析为空 criteria")
    void shouldHandleEmptyFilters() {
        DocumentFilterCriteria c1 = DocumentFilterCriteria.fromFilters(null);
        assertThat(c1.isEmpty()).isTrue();
        assertThat(c1.hasFilters()).isFalse();

        DocumentFilterCriteria c2 = DocumentFilterCriteria.fromFilters(List.of());
        assertThat(c2.isEmpty()).isTrue();
        assertThat(c2.hasFilters()).isFalse();
    }

    @Test
    @DisplayName("应该正确映射文件名与标题过滤条件")
    void shouldMapFilenameAndHeadingFilters() {
        List<MetadataFilter> filters = List.of(
                MetadataFilter.eq("originalFilename", "report.pdf"),
                MetadataFilter.contains("originalFilename", "annual"),
                MetadataFilter.in("originalFilename", List.of("a.pdf", "b.pdf")),
                MetadataFilter.eq("sectionHeading", "第四章 性能测试"),
                MetadataFilter.contains("sectionHeading", "性能")
        );

        DocumentFilterCriteria criteria = DocumentFilterCriteria.fromFilters(filters);
        assertThat(criteria.hasFilters()).isTrue();
        assertThat(criteria.getExactFilename()).isEqualTo("report.pdf");
        assertThat(criteria.getFilenameContains()).isEqualTo("annual");
        assertThat(criteria.getFilenameIn()).containsExactly("a.pdf", "b.pdf");
        assertThat(criteria.getExactHeading()).isEqualTo("第四章 性能测试");
        assertThat(criteria.getHeadingContains()).isEqualTo("性能");
    }

    @Test
    @DisplayName("应该正确解析时间范围与状态、标签、MimeType")
    void shouldMapTemporalAndAttributesFilters() {
        OffsetDateTime now = OffsetDateTime.of(2026, 11, 6, 12, 0, 0, 0, ZoneOffset.UTC);
        List<MetadataFilter> filters = List.of(
                MetadataFilter.gte("createdAt", "2026-10-01T00:00:00Z"),
                MetadataFilter.lte("createdAt", now),
                MetadataFilter.gte("updatedAt", "2026-11-01"),
                MetadataFilter.eq("status", "ready"),
                MetadataFilter.in("status", List.of("READY", "INDEXING")),
                MetadataFilter.contains("tags", "core_arch"),
                MetadataFilter.eq("mimeType", "APPLICATION/PDF")
        );

        DocumentFilterCriteria criteria = DocumentFilterCriteria.fromFilters(filters);
        assertThat(criteria.hasFilters()).isTrue();
        assertThat(criteria.getCreatedAfter()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        assertThat(criteria.getCreatedBefore()).isEqualTo(now);
        assertThat(criteria.getUpdatedAfter()).isNotNull();
        assertThat(criteria.getStatus()).isEqualTo("READY");
        assertThat(criteria.getStatusIn()).containsExactly("READY", "INDEXING");
        assertThat(criteria.getTagContains()).isEqualTo("core_arch");
        assertThat(criteria.getMimeType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("parseOffsetDateTime 应该健壮支持多种日期格式与对象")
    void shouldParseVariousTemporalFormats() {
        Instant instant = Instant.now();
        OffsetDateTime parsedFromInstant = DocumentFilterCriteria.parseOffsetDateTime(instant);
        assertThat(parsedFromInstant).isNotNull();

        OffsetDateTime parsedFromDateStr = DocumentFilterCriteria.parseOffsetDateTime("2026-10-05");
        assertThat(parsedFromDateStr).isNotNull();
        assertThat(parsedFromDateStr.getYear()).isEqualTo(2026);
        assertThat(parsedFromDateStr.getMonthValue()).isEqualTo(10);
        assertThat(parsedFromDateStr.getDayOfMonth()).isEqualTo(5);

        assertThat(DocumentFilterCriteria.parseOffsetDateTime(null)).isNull();
        assertThat(DocumentFilterCriteria.parseOffsetDateTime("   ")).isNull();
        assertThat(DocumentFilterCriteria.parseOffsetDateTime("invalid-date-string")).isNull();
    }
}
