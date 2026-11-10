package com.shitulelv.aicollab.retrieval.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 检索元数据硬过滤标准对象。
 * 用于将高层 MetadataFilter 条件列表解析并映射为可在数据库层下推的安全参数化查询条件。
 */
public class DocumentFilterCriteria {

    private String exactFilename;
    private String filenameContains;
    private List<String> filenameIn;
    private String exactHeading;
    private String headingContains;
    private OffsetDateTime createdAfter;
    private OffsetDateTime createdBefore;
    private OffsetDateTime updatedAfter;
    private OffsetDateTime updatedBefore;
    private String status;
    private List<String> statusIn;
    private String tagContains;
    private String mimeType;

    public DocumentFilterCriteria() {
    }

    public static DocumentFilterCriteria fromFilters(List<MetadataFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return new DocumentFilterCriteria();
        }

        DocumentFilterCriteria criteria = new DocumentFilterCriteria();
        for (MetadataFilter filter : filters) {
            if (filter == null) continue;
            String field = filter.field();
            FilterOperator op = filter.operator();
            Object val = filter.value();
            if (field == null || op == null || val == null) continue;

            switch (field) {
                case "originalFilename", "displayName" -> {
                    switch (op) {
                        case EQ -> criteria.setExactFilename(val.toString().trim());
                        case CONTAINS -> criteria.setFilenameContains(val.toString().trim());
                        case IN -> criteria.setFilenameIn(toStringList(val));
                        default -> {}
                    }
                }
                case "sectionHeading" -> {
                    switch (op) {
                        case EQ -> criteria.setExactHeading(val.toString().trim());
                        case CONTAINS -> criteria.setHeadingContains(val.toString().trim());
                        default -> {}
                    }
                }
                case "createdAt" -> {
                    OffsetDateTime time = parseOffsetDateTime(val);
                    if (time != null) {
                        switch (op) {
                            case GT, GTE -> criteria.setCreatedAfter(time);
                            case LT, LTE -> criteria.setCreatedBefore(time);
                            case EQ -> {
                                criteria.setCreatedAfter(time);
                                criteria.setCreatedBefore(time);
                            }
                            default -> {}
                        }
                    }
                }
                case "updatedAt" -> {
                    OffsetDateTime time = parseOffsetDateTime(val);
                    if (time != null) {
                        switch (op) {
                            case GT, GTE -> criteria.setUpdatedAfter(time);
                            case LT, LTE -> criteria.setUpdatedBefore(time);
                            case EQ -> {
                                criteria.setUpdatedAfter(time);
                                criteria.setUpdatedBefore(time);
                            }
                            default -> {}
                        }
                    }
                }
                case "status" -> {
                    switch (op) {
                        case EQ -> criteria.setStatus(val.toString().trim().toUpperCase(Locale.ROOT));
                        case IN -> criteria.setStatusIn(toStringList(val));
                        default -> {}
                    }
                }
                case "tags" -> {
                    switch (op) {
                        case EQ, CONTAINS -> criteria.setTagContains(val.toString().trim());
                        case IN -> {
                            List<String> tags = toStringList(val);
                            if (!tags.isEmpty()) {
                                criteria.setTagContains(tags.getFirst());
                            }
                        }
                        default -> {}
                    }
                }
                case "mimeType" -> {
                    if (op == FilterOperator.EQ) {
                        criteria.setMimeType(val.toString().trim().toLowerCase(Locale.ROOT));
                    }
                }
                default -> {}
            }
        }
        return criteria;
    }

    public static OffsetDateTime parseOffsetDateTime(Object val) {
        if (val == null) return null;
        if (val instanceof OffsetDateTime odt) return odt;
        if (val instanceof Instant inst) return inst.atOffset(ZoneOffset.UTC);
        if (val instanceof Date d) return d.toInstant().atOffset(ZoneOffset.UTC);
        String s = val.toString().trim();
        if (s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(s).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(s).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    private static List<String> toStringList(Object val) {
        if (val == null) return List.of();
        if (val instanceof Collection<?> coll) {
            return coll.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        if (val.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(val);
            List<String> list = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                Object item = java.lang.reflect.Array.get(val, i);
                if (item != null && !item.toString().isBlank()) {
                    list.add(item.toString().trim());
                }
            }
            return list;
        }
        return List.of(val.toString().trim());
    }

    public boolean isEmpty() {
        return exactFilename == null
                && filenameContains == null
                && (filenameIn == null || filenameIn.isEmpty())
                && exactHeading == null
                && headingContains == null
                && createdAfter == null
                && createdBefore == null
                && updatedAfter == null
                && updatedBefore == null
                && status == null
                && (statusIn == null || statusIn.isEmpty())
                && tagContains == null
                && mimeType == null;
    }

    public boolean hasFilters() {
        return !isEmpty();
    }

    public String getExactFilename() {
        return exactFilename;
    }

    public void setExactFilename(String exactFilename) {
        this.exactFilename = exactFilename;
    }

    public String getFilenameContains() {
        return filenameContains;
    }

    public void setFilenameContains(String filenameContains) {
        this.filenameContains = filenameContains;
    }

    public List<String> getFilenameIn() {
        return filenameIn;
    }

    public void setFilenameIn(List<String> filenameIn) {
        this.filenameIn = filenameIn;
    }

    public String getExactHeading() {
        return exactHeading;
    }

    public void setExactHeading(String exactHeading) {
        this.exactHeading = exactHeading;
    }

    public String getHeadingContains() {
        return headingContains;
    }

    public void setHeadingContains(String headingContains) {
        this.headingContains = headingContains;
    }

    public OffsetDateTime getCreatedAfter() {
        return createdAfter;
    }

    public void setCreatedAfter(OffsetDateTime createdAfter) {
        this.createdAfter = createdAfter;
    }

    public OffsetDateTime getCreatedBefore() {
        return createdBefore;
    }

    public void setCreatedBefore(OffsetDateTime createdBefore) {
        this.createdBefore = createdBefore;
    }

    public OffsetDateTime getUpdatedAfter() {
        return updatedAfter;
    }

    public void setUpdatedAfter(OffsetDateTime updatedAfter) {
        this.updatedAfter = updatedAfter;
    }

    public OffsetDateTime getUpdatedBefore() {
        return updatedBefore;
    }

    public void setUpdatedBefore(OffsetDateTime updatedBefore) {
        this.updatedBefore = updatedBefore;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<String> getStatusIn() {
        return statusIn;
    }

    public void setStatusIn(List<String> statusIn) {
        this.statusIn = statusIn;
    }

    public String getTagContains() {
        return tagContains;
    }

    public void setTagContains(String tagContains) {
        this.tagContains = tagContains;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }
}
